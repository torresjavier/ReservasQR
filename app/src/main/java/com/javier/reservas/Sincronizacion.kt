package com.javier.reservas

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Cola de registros por enviar al servidor. Se guarda en un archivo para no perder nada
 * si se apaga el equipo o no hay internet.
 */
object Bandeja {
    private const val ARCHIVO = "bandeja.json"

    @Volatile var pendientes: Int = -1
        private set

    private fun archivo(ctx: Context) = File(ctx.applicationContext.filesDir, ARCHIVO)

    private fun leer(ctx: Context): JSONArray = try {
        val f = archivo(ctx)
        if (f.exists()) JSONArray(f.readText()) else JSONArray()
    } catch (e: Exception) {
        JSONArray()
    }

    private fun escribir(ctx: Context, a: JSONArray) {
        val f = archivo(ctx)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(f)) {
            f.writeText(a.toString()); tmp.delete()
        }
        pendientes = a.length()
    }

    @Synchronized fun agregar(ctx: Context, ev: JSONObject) {
        val a = leer(ctx)
        a.put(ev)
        escribir(ctx, a)
    }

    /** Agrega un registro que llegó de otro equipo, salvo que ya esté en la cola. @return true si lo agregó */
    @Synchronized fun agregarSiNuevo(ctx: Context, ev: JSONObject): Boolean {
        val id = ev.optString("id_evento", "")
        val a = leer(ctx)
        if (id.isNotEmpty()) {
            for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id_evento") == id) return false
        }
        a.put(ev)
        escribir(ctx, a)
        return true
    }

    /** Cuántos registros de la cola cumplen el [filtro] (todos si no se da). */
    @Synchronized fun contar(ctx: Context, filtro: ((JSONObject) -> Boolean)?): Int {
        if (filtro == null) return cantidad(ctx)
        val a = leer(ctx)
        var n = 0
        for (i in 0 until a.length()) if (filtro(a.getJSONObject(i))) n++
        return n
    }

    @Synchronized fun cantidad(ctx: Context): Int {
        if (pendientes < 0) pendientes = leer(ctx).length()
        return pendientes
    }

    /** Los primeros [n] registros de la cola que cumplen el [filtro] (todos si no se da). */
    @Synchronized fun primeros(ctx: Context, n: Int, filtro: ((JSONObject) -> Boolean)? = null): List<JSONObject> {
        val a = leer(ctx)
        val r = ArrayList<JSONObject>()
        for (i in 0 until a.length()) {
            if (r.size >= n) break
            val o = a.getJSONObject(i)
            if (filtro == null || filtro(o)) r.add(o)
        }
        return r
    }

    /** Quita de la cola los registros que el servidor confirmó. Devuelve los que se quitaron. */
    @Synchronized fun quitar(ctx: Context, ids: Set<String>): List<JSONObject> {
        val a = leer(ctx)
        val queda = JSONArray()
        val quitados = ArrayList<JSONObject>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("id_evento") !in ids) queda.put(o) else quitados.add(o)
        }
        escribir(ctx, queda)
        return quitados
    }

    /** Códigos y placas (normalizados) de las reservas que todavía tienen registros sin enviar. */
    @Synchronized fun claves(ctx: Context): Set<String> {
        val a = leer(ctx)
        val r = HashSet<String>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            Operacion.clave(o.optString("codigo_reserva", "")).takeIf { it.isNotEmpty() }?.let { r.add(it) }
            Operacion.clave(o.optString("placa", "")).takeIf { it.isNotEmpty() }?.let { r.add(it) }
        }
        return r
    }
}

/** Conexión simple por HTTPS/HTTP sin librerías extra. */
object Http {
    fun pedir(metodo: String, url: String, token: String, cuerpo: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = metodo
            c.connectTimeout = 15_000
            c.readTimeout = 45_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "ReservasQR/" + Build.MODEL)
            if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
            if (cuerpo != null) {
                val bytes = cuerpo.toByteArray(Charsets.UTF_8)
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val codigo = c.responseCode
            val flujo = if (codigo < 400) c.inputStream else c.errorStream
            val texto = flujo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return codigo to texto
        } finally {
            c.disconnect()
        }
    }
}

/**
 * Programa los envíos y descargas. Cuida la batería:
 *  - Solo trabaja con internet (WorkManager con restricción de red; nada de sondeos constantes).
 *  - Un «latido» cada N minutos (configurable) mientras la app está viva: envía lo pendiente y trae reservas.
 *  - Al volver el internet envía lo que haya quedado.
 *  - Aunque la app se cierre, una tarea periódica de Android lo intenta cada 15 minutos.
 * Si un envío falla, el registro sigue en la cola con el mismo id y se reintenta en el siguiente latido.
 */
object Sincronizacion {

    /** Cantidad de registros que se juntan para enviar un lote (también es el máximo por envío). */
    val LOTES = intArrayOf(5, 10, 20)
    /** Minutos entre envíos / descargas. */
    val INTERVALOS = intArrayOf(1, 2, 5, 10, 15, 30)

    private const val ENVIO = "envio_registros"
    private const val ENVIO_PERIODICO = "envio_registros_periodico"
    private const val RESERVAS = "descarga_reservas"
    private const val RESERVAS_PERIODICO = "descarga_reservas_periodico"

    @Volatile var ultimoEnvio: Long = 0L
    @Volatile var ultimoError: String? = null
    @Volatile var ultimaDescarga: Long = 0L

    private val conInternet = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private val main = Handler(Looper.getMainLooper())
    private var latido: Runnable? = null
    private var redRegistrada = false
    private var ultimoDisparoRed = 0L

    /** Solo el satélite habla con el servidor: los demás equipos entregan sus registros al satélite por el Wi-Fi. */
    fun configuradoEnvio(ctx: Context) = Ajustes(ctx).let { it.urlEnvio.isNotBlank() && it.esSatelite }
    fun configuradoReservas(ctx: Context) = Ajustes(ctx).let { it.urlReservas.isNotBlank() && it.esSatelite }

    /** Registros de la cola que sube este equipo (los del momento del que es satélite). */
    fun propios(ctx: Context): (JSONObject) -> Boolean {
        val aj = Ajustes(ctx)
        return { o -> aj.cubre(o.optString("momento", "INTERIOR")) }
    }

    /** Registros de la cola que este equipo no sube: espera a entregárselos a su satélite. */
    fun ajenos(ctx: Context): (JSONObject) -> Boolean {
        val aj = Ajustes(ctx)
        return { o -> !aj.cubre(o.optString("momento", "INTERIOR")) }
    }

    /** Se llama después de cada registro: lo entrega al satélite si este equipo no lo sube, o envía cuando se junta el lote. */
    fun alRegistrar(ctx: Context) {
        RedLocal.entregarPendientes()
        if (!configuradoEnvio(ctx)) return
        if (Bandeja.contar(ctx, propios(ctx)) >= Ajustes(ctx).loteEnvio) enviarAhora(ctx)
    }

    fun enviarAhora(ctx: Context) {
        if (!configuradoEnvio(ctx)) return
        val w = OneTimeWorkRequestBuilder<EnvioWorker>()
            .setConstraints(conInternet)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // KEEP: si ya hay un envío en marcha no se interrumpe ni se duplica
        WorkManager.getInstance(ctx).enqueueUniqueWork(ENVIO, ExistingWorkPolicy.KEEP, w)
    }

    fun actualizarReservasAhora(ctx: Context) {
        if (!configuradoReservas(ctx)) return
        val w = OneTimeWorkRequestBuilder<ReservasWorker>()
            .setConstraints(conInternet)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(RESERVAS, ExistingWorkPolicy.KEEP, w)
    }

    /** Tareas periódicas de respaldo (cada 15 min, el mínimo de Android) y reinicio del latido con el intervalo actual. */
    fun programar(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (configuradoEnvio(ctx)) {
            wm.enqueueUniquePeriodicWork(
                ENVIO_PERIODICO, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<EnvioWorker>(15, TimeUnit.MINUTES).setConstraints(conInternet).build()
            )
        } else wm.cancelUniqueWork(ENVIO_PERIODICO)

        if (configuradoReservas(ctx) && Ajustes(ctx).autoReservas) {
            wm.enqueueUniquePeriodicWork(
                RESERVAS_PERIODICO, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReservasWorker>(15, TimeUnit.MINUTES).setConstraints(conInternet).build()
            )
        } else wm.cancelUniqueWork(RESERVAS_PERIODICO)

        iniciarLatido(ctx)
    }

    // ---------------------------------------------------------------- latido y red

    private fun unaVuelta(ctx: Context) {
        RedLocal.entregarPendientes()
        if (configuradoEnvio(ctx) && Bandeja.contar(ctx, propios(ctx)) > 0) enviarAhora(ctx)
        if (configuradoReservas(ctx) && Ajustes(ctx).autoReservas) actualizarReservasAhora(ctx)
    }

    /** Cada N minutos (Ajustes) envía lo pendiente y trae reservas. Solo mueve la red si hay algo que hacer. */
    fun iniciarLatido(ctx: Context) {
        val app = ctx.applicationContext
        latido?.let { main.removeCallbacks(it) }
        val cada = Ajustes(app).intervaloMin * 60_000L
        val r = object : Runnable {
            override fun run() {
                unaVuelta(app)
                main.postDelayed(this, cada)
            }
        }
        latido = r
        main.postDelayed(r, cada)
    }

    /** Cuando vuelve el internet, envía de una vez lo que haya quedado en la cola. */
    fun escucharRed(ctx: Context) {
        if (redRegistrada) return
        val app = ctx.applicationContext
        try {
            val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    main.post {
                        val ahora = System.currentTimeMillis()
                        if (ahora - ultimoDisparoRed < 10_000L) return@post
                        ultimoDisparoRed = ahora
                        unaVuelta(app)
                    }
                }
            })
            redRegistrada = true
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- reservas que llegan del servidor

    private val CLAVES_DE_ESTADO = setOf("estado", "fecha_validacion", "fecha_ingreso", "_actualizado")

    /** Agrega/actualiza las reservas y aplica el estado (validada / ingresada) que informa el servidor. */
    fun aplicarReservas(lista: JSONArray) {
        // Columnas = todas las claves que trae el servidor (menos las de estado)
        val encabezados = ArrayList<String>()
        for (i in 0 until lista.length()) {
            val o = lista.optJSONObject(i) ?: continue
            o.keys().forEach { k -> if (k !in encabezados && k !in CLAVES_DE_ESTADO) encabezados.add(k) }
        }
        val filas = ArrayList<List<String>>()
        if (encabezados.isNotEmpty()) {
            for (i in 0 until lista.length()) {
                val o = lista.optJSONObject(i) ?: continue
                filas.add(encabezados.map { k -> if (o.isNull(k)) "" else o.optString(k, "") })
            }
        }
        Operacion.enPrincipal {
            if (filas.isNotEmpty()) Operacion.fusionarDelServidor(encabezados, filas)
            Operacion.aplicarEstados(lista)
        }
        // Este equipo es el único que consulta al servidor: lo que trajo se lo reparte a los demás por el Wi-Fi.
        RedLocal.tablaCambio()
    }
}

/** Envía la cola en lotes de 5, 10 o 20 registros (según Ajustes), con su foto en base64. */
class EnvioWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        Operacion.iniciar(ctx)
        val aj = Ajustes(ctx)
        val url = aj.urlEnvio
        if (url.isBlank() || !aj.esSatelite) return Result.success()
        val propios = Sincronizacion.propios(ctx)

        var tamano = aj.loteEnvio
        var vueltas = 0
        while (vueltas++ < 60) { // hasta 60 lotes por ejecución
            if (isStopped) return Result.success()
            val lote = Bandeja.primeros(ctx, tamano, propios)
            if (lote.isEmpty()) {
                Sincronizacion.ultimoError = null
                Operacion.avisar()
                return Result.success()
            }
            val registros = JSONArray()
            val ids = HashSet<String>()
            for (ev in lote) {
                val r = JSONObject(ev.toString())
                val nombreFoto = r.optString("foto_archivo", "")
                r.remove("foto_archivo")
                if (nombreFoto.isNotBlank()) {
                    val f = File(ctx.filesDir, nombreFoto)
                    if (f.exists()) {
                        r.put("foto", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
                        r.put("foto_tipo", "image/jpeg")
                    }
                }
                registros.put(r)
                ids.add(ev.optString("id_evento"))
            }
            val cuerpo = JSONObject().apply {
                put("dispositivo", aj.idDispositivo)
                put("modelo", Build.MODEL ?: "")
                put("operador", aj.operador)
                put("enviado", Operacion.iso(System.currentTimeMillis()))
                put("cantidad", registros.length())
                put("registros", registros)
            }
            val respuesta = try {
                Http.pedir("POST", url, aj.token, cuerpo.toString())
            } catch (e: Exception) {
                // Sin internet o servidor caído: los registros siguen en la cola, con el mismo id, y se reintentan en el siguiente latido
                Sincronizacion.ultimoError = "Sin conexión con el servidor"
                Operacion.avisar()
                return Result.success()
            }
            val (codigo, texto) = respuesta
            when {
                codigo in 200..299 -> {
                    var recibidos: Set<String>? = null
                    var rechazados: Set<String> = emptySet()
                    var reservas: JSONArray? = null
                    try {
                        val o = JSONObject(texto)
                        o.optJSONArray("recibidos")?.let { a -> recibidos = (0 until a.length()).map { a.getString(it) }.toSet() }
                        o.optJSONArray("rechazados")?.let { a -> rechazados = (0 until a.length()).map { a.getString(it) }.toSet() }
                        reservas = o.optJSONArray("reservas")
                    } catch (_: Exception) {
                    }
                    // Si el servidor dice cuáles recibió se quitan solo esos; si no dice nada, todo el lote
                    val quitar = (recibidos ?: ids).intersect(ids) + rechazados.intersect(ids)
                    val quitados = Bandeja.quitar(ctx, quitar)
                    // Las fotos de las alertas y las que llegaron de otro equipo solo servían para este envío
                    quitados.filter { it.optString("tipo_evento") == "ALERTA" || it.optString("foto_archivo", "").startsWith(RedLocal.DIR_FOTOS_RED) }.forEach {
                        val f = it.optString("foto_archivo", "")
                        if (f.isNotBlank()) File(ctx.filesDir, f).delete()
                    }
                    reservas?.let { r -> if (r.length() > 0) try { Sincronizacion.aplicarReservas(r) } catch (_: Exception) {} }
                    Sincronizacion.ultimoEnvio = System.currentTimeMillis()
                    Sincronizacion.ultimoError = if (rechazados.isNotEmpty()) "El servidor rechazó ${rechazados.size} registro(s)" else null
                    Operacion.avisar()
                    if (quitados.isEmpty()) return Result.success() // el servidor no aceptó ninguno: no insistir en este mismo ciclo
                }
                codigo == 401 || codigo == 403 -> {
                    Sincronizacion.ultimoError = "El servidor rechazó la clave (token)"
                    Operacion.avisar()
                    return Result.failure()
                }
                codigo == 413 && tamano > 1 -> {
                    tamano = maxOf(1, tamano / 2) // demasiado grande (muchas fotos): se manda en lotes más pequeños
                }
                codigo in 400..499 && codigo != 408 && codigo != 429 -> {
                    Sincronizacion.ultimoError = "El servidor respondió $codigo"
                    Operacion.avisar()
                    return Result.failure()
                }
                else -> {
                    Sincronizacion.ultimoError = "El servidor respondió $codigo, se reintentará"
                    Operacion.avisar()
                    return Result.success()
                }
            }
        }
        return Result.success()
    }
}

/** Descarga reservas nuevas o cambiadas del servidor (con su estado) y las agrega al listado del equipo. */
class ReservasWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        Operacion.iniciar(ctx)
        val aj = Ajustes(ctx)
        val base = aj.urlReservas
        if (base.isBlank() || !aj.esSatelite) return Result.success()

        val sep = if (base.contains("?")) "&" else "?"
        val url = base + sep + "dispositivo=" + URLEncoder.encode(aj.idDispositivo, "UTF-8") +
            (if (aj.ultimaSincReservas.isNotBlank()) "&desde=" + URLEncoder.encode(aj.ultimaSincReservas, "UTF-8") else "")

        val (codigo, texto) = try {
            Http.pedir("GET", url, aj.token, null)
        } catch (e: Exception) {
            Sincronizacion.ultimoError = "No se pudieron descargar reservas"
            Operacion.avisar()
            return Result.success() // se reintenta en el siguiente latido
        }
        if (codigo !in 200..299) {
            Sincronizacion.ultimoError = "Reservas: el servidor respondió $codigo"
            Operacion.avisar()
            return if (codigo >= 500) Result.success() else Result.failure()
        }

        return try {
            val t = texto.trim()
            val lista: JSONArray
            var hora = ""
            if (t.startsWith("[")) {
                lista = JSONArray(t)
            } else {
                val o = JSONObject(t)
                lista = o.optJSONArray("reservas") ?: JSONArray()
                hora = o.optString("hora", "")
            }
            if (lista.length() > 0) Sincronizacion.aplicarReservas(lista)
            aj.ultimaSincReservas = hora.ifBlank { Operacion.iso(System.currentTimeMillis()) }
            Sincronizacion.ultimaDescarga = System.currentTimeMillis()
            Operacion.avisar()
            Result.success()
        } catch (e: Exception) {
            Sincronizacion.ultimoError = "Reservas: respuesta no válida del servidor"
            Operacion.avisar()
            Result.failure()
        }
    }
}
