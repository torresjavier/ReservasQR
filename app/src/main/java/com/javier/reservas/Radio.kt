package com.javier.reservas

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Radio (walkie-talkie): mientras se mantiene pulsado el botón, la voz sale a todos los equipos y se oye en todos los
 * que tengan la radio encendida. Viaja por dos caminos a la vez:
 *  - por el Wi-Fi, directo a los equipos conectados (la red entre equipos), con poco retraso;
 *  - por internet, a través del servidor (dirección en Ajustes), para los equipos que están lejos, en otro Wi-Fi o con 4G.
 * Si un equipo recibe el mismo trozo por los dos caminos, lo reproduce una sola vez.
 *
 * La radio es «en vivo»: lo que se pierde por una mala señal no se repite.
 */
object Radio {

    enum class Estado { REPOSO, HABLANDO, ESCUCHANDO }

    @Volatile var estado = Estado.REPOSO
        private set

    /** Nombre de quien se está oyendo (vacío si nadie). */
    @Volatile var quien = ""
        private set

    private val main = Handler(Looper.getMainLooper())
    private val escuchas = CopyOnWriteArrayList<() -> Unit>()
    private val recepcion = Voz.Recepcion()
    private val reproduccion = Executors.newSingleThreadExecutor { r -> Thread(r, "radio-audio").apply { isDaemon = true } }

    @Volatile private var hablando = false
    private var ultimoTx = 0L

    @Volatile private var pista: AudioTrack? = null
    @Volatile private var vence = 0L

    // ---- internet
    private val cola = LinkedBlockingQueue<ByteArray>(200)
    @Volatile private var activo = false
    @Volatile private var generacion = 0

    fun escuchar(f: () -> Unit) { escuchas.add(f) }
    fun dejarDeEscuchar(f: () -> Unit) { escuchas.remove(f) }

    private fun cambiar(e: Estado, q: String) {
        estado = e
        quien = q
        main.post { escuchas.forEach { it() } }
    }

    fun encendida(): Boolean = Operacion.ajustes.radioActiva

    fun tienePermiso(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Dirección del servidor de radio: la de Ajustes o, si está vacía, la del envío de registros cambiando el final. */
    fun urlInternet(a: Ajustes): String {
        val u = a.urlRadio.trim()
        if (u.isNotEmpty()) return u
        val e = a.urlEnvio.trim()
        return if (e.contains("/api/registros")) e.replace("/api/registros", "/api/radio") else ""
    }

    // ---------------------------------------------------------------- hablar

    /** Empieza a transmitir (se llama al apretar el botón). @return false si no se pudo (apagada, sin permiso, sin micrófono) */
    @Synchronized
    fun hablar(ctx: Context): Boolean {
        val a = Operacion.ajustes
        if (!a.radioActiva || hablando || !tienePermiso(ctx)) return false
        val minimo = AudioRecord.getMinBufferSize(Voz.FRECUENCIA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimo <= 0) return false
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, Voz.FRECUENCIA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimo, Voz.MUESTRAS_BLOQUE * 4)
            )
        } catch (_: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        hablando = true
        val tx = maxOf(ultimoTx + 1, System.currentTimeMillis())
        ultimoTx = tx
        cerrarPista(ya = true)
        cambiar(Estado.HABLANDO, "")
        encender()
        val hilo = Thread({ grabar(rec, tx, a.idDispositivo, a.operador.ifBlank { a.nombreEquipo }) }, "radio-mic")
        hilo.isDaemon = true
        hilo.start()
        return true
    }

    /** Deja de transmitir (se llama al soltar el botón). */
    fun soltar() {
        hablando = false
    }

    private fun grabar(rec: AudioRecord, tx: Long, de: String, nombre: String) {
        val buf = ShortArray(Voz.MUESTRAS_BLOQUE)
        var seq = 0
        val limite = System.currentTimeMillis() + Voz.MAX_SEGUNDOS * 1000L
        try {
            rec.startRecording()
            while (hablando && System.currentTimeMillis() < limite) {
                var n = 0
                while (n < buf.size && hablando) {
                    val r = rec.read(buf, n, buf.size - n)
                    if (r <= 0) break
                    n += r
                }
                if (n <= 0) break
                enviar(Voz.Paquete(de, nombre, tx, seq++, false, Voz.comprimir(buf, n)))
            }
        } catch (_: Exception) {
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        enviar(Voz.Paquete(de, nombre, tx, seq, true, ByteArray(0)))
        hablando = false
        cambiar(Estado.REPOSO, "")
    }

    private fun enviar(p: Voz.Paquete) {
        val bytes = Voz.empaquetar(p)
        RedLocal.difundirVoz("VOZ-" + p.de + "-" + p.tx + "-" + p.seq, p.nombre, bytes)
        if (urlInternet(Operacion.ajustes).isNotBlank()) {
            // Sin internet no se acumula voz vieja: si la cola se llena, se descarta lo más antiguo
            if (!cola.offer(bytes)) {
                cola.poll()
                cola.offer(bytes)
            }
        }
    }

    // ---------------------------------------------------------------- oír

    /** Llegó un trozo de voz por el Wi-Fi (red entre equipos). */
    fun recibirDeRed(meta: String, datos: ByteArray): Boolean {
        val p = Voz.leer(datos) ?: return false
        procesar(p)
        return true
    }

    private val vigila = object : Runnable {
        override fun run() {
            if (estado != Estado.ESCUCHANDO) return
            val falta = vence - System.currentTimeMillis()
            if (falta > 0) {
                main.postDelayed(this, falta)
                return
            }
            cambiar(Estado.REPOSO, "")
            cerrarPista(ya = false)
        }
    }

    private fun procesar(p: Voz.Paquete) {
        val a = Operacion.ajustes
        if (!a.radioActiva || p.de == a.idDispositivo) return
        if (!recepcion.aceptar(p)) return
        if (hablando) return   // mientras uno habla no oye a los demás (como un walkie-talkie)
        vence = System.currentTimeMillis() + if (p.fin) 500L else 1_500L
        val mostrado = p.nombre.ifBlank { "Un equipo" }
        if (estado != Estado.ESCUCHANDO || quien != mostrado) cambiar(Estado.ESCUCHANDO, mostrado)
        main.removeCallbacks(vigila)
        main.postDelayed(vigila, if (p.fin) 520L else 1_550L)
        if (p.audio.isNotEmpty()) {
            val pcm = Voz.descomprimir(p.audio, 0)
            reproduccion.execute { reproducir(pcm) }
        }
    }

    private fun reproducir(pcm: ShortArray) {
        var t = pista
        if (t == null) {
            t = crearPista() ?: return
            pista = t
            // Un colchón de silencio al empezar para que un retraso pequeño de la red no corte la voz
            val silencio = ShortArray(Voz.FRECUENCIA / 8)
            t.write(silencio, 0, silencio.size)
        }
        try {
            t.write(pcm, 0, pcm.size)
        } catch (_: Exception) {
        }
    }

    private fun crearPista(): AudioTrack? = try {
        val minimo = AudioTrack.getMinBufferSize(Voz.FRECUENCIA, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Voz.FRECUENCIA)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minimo, Voz.FRECUENCIA))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        t
    } catch (_: Exception) {
        null
    }

    /** Cierra el altavoz: [ya] corta lo que quede por sonar; si no, deja que termine de sonar. */
    private fun cerrarPista(ya: Boolean) {
        reproduccion.execute {
            val t = pista ?: return@execute
            pista = null
            try {
                if (ya) {
                    t.pause()
                    t.flush()
                } else t.stop()
            } catch (_: Exception) {
            }
            try { t.release() } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- internet

    /** Enciende los hilos que suben y bajan la voz por internet (si hay dirección en Ajustes). Se puede llamar muchas veces. */
    @Synchronized
    fun encender() {
        if (activo || !Operacion.ajustes.radioActiva) return
        activo = true
        val g = ++generacion
        Thread({ subir(g) }, "radio-sube").apply { isDaemon = true }.start()
        Thread({ bajar(g) }, "radio-baja").apply { isDaemon = true }.start()
    }

    /** Apaga la radio (por ejemplo, al quitarla en Ajustes). */
    @Synchronized
    fun apagar() {
        activo = false
        generacion++
        hablando = false
        cola.clear()
        cerrarPista(ya = true)
        cambiar(Estado.REPOSO, "")
    }

    /** Se llama al guardar Ajustes. */
    fun aplicarAjustes() {
        if (Operacion.ajustes.radioActiva) encender() else apagar()
    }

    private fun vigente(g: Int) = activo && g == generacion

    private fun subir(g: Int) {
        while (vigente(g)) {
            val primero = try { cola.poll(1, TimeUnit.SECONDS) } catch (_: InterruptedException) { null } ?: continue
            pausa(350)   // se juntan unos trozos en un solo envío
            val lote = ArrayList<ByteArray>()
            lote.add(primero)
            cola.drainTo(lote)
            val a = Operacion.ajustes
            val url = urlInternet(a)
            if (url.isBlank()) continue
            try {
                val cuerpo = JSONObject().apply {
                    put("canal", a.radioCanal)
                    put("de", a.idDispositivo)
                    put("paquetes", JSONArray().also { arr -> lote.forEach { arr.put(Base64.encodeToString(it, Base64.NO_WRAP)) } })
                }
                Http.pedir("POST", url, a.token, cuerpo.toString())
            } catch (_: Exception) {
                // Sin internet: es voz en vivo, no se guarda para después
            }
        }
    }

    private fun bajar(g: Int) {
        var desde = -1L
        while (vigente(g)) {
            val a = Operacion.ajustes
            val url = urlInternet(a)
            if (url.isBlank()) {
                pausa(3_000)
                continue
            }
            try {
                val sep = if (url.contains("?")) "&" else "?"
                val completa = url + sep + "canal=" + URLEncoder.encode(a.radioCanal, "UTF-8") +
                    "&yo=" + URLEncoder.encode(a.idDispositivo, "UTF-8") + "&desde=" + desde
                val (codigo, texto) = Http.pedir("GET", completa, a.token, null)
                if (codigo !in 200..299) {
                    pausa(3_000)
                    continue
                }
                val o = JSONObject(texto)
                desde = o.optLong("hasta", desde)
                val lista = o.optJSONArray("paquetes") ?: JSONArray()
                for (i in 0 until lista.length()) {
                    val b = lista.optJSONObject(i)?.optString("p", "").orEmpty()
                    if (b.isEmpty()) continue
                    val p = Voz.leer(Base64.decode(b, Base64.NO_WRAP)) ?: continue
                    procesar(p)
                }
            } catch (_: Exception) {
                pausa(3_000)
            }
        }
    }

    private fun pausa(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
}
