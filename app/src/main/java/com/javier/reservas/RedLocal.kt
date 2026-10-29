package com.javier.reservas

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier

/**
 * Une la app con la red entre equipos ([RedEquipos]): arranca y detiene el motor según los Ajustes, anota lo que hace
 * este equipo para avisar a los demás y entrega a [Operacion] lo que hicieron los otros.
 *
 * Todo lo que toca la red se hace en un hilo aparte; los avisos a la pantalla y los cambios a las reservas se hacen
 * en el hilo principal.
 */
object RedLocal : RedEquipos.Oyente {

    /** Carpeta (dentro de los archivos de la app) donde el satélite guarda las fotos que le entregan los otros equipos. */
    const val DIR_FOTOS_RED = "fotos_red/"

    /** Cuántos registros se mandan al satélite sin esperar el acuse del anterior. */
    private const val VENTANA = 4
    private const val ACUSE_MS = 25_000L
    private const val RECHAZO_MS = 60_000L
    private const val CADA_REVISION_MS = 15_000L
    /** Tamaño de cada trozo de la tabla de reservas (cabe en un mensaje de la red). */
    private const val TROZO = 900_000

    /** Puertos en los que escucha este equipo. Solo las pruebas automáticas los cambian; en la app siempre son los de siempre. */
    @Volatile internal var puertoUdpPropio = RedEquipos.PUERTO_UDP
    @Volatile internal var puertoTcpPropio = RedEquipos.PUERTO_TCP

    private val main = Handler(Looper.getMainLooper())
    private val hilo = Executors.newSingleThreadExecutor { r -> Thread(r, "red-local").apply { isDaemon = true } }
    private val escuchas = CopyOnWriteArrayList<() -> Unit>()

    private var registro: RegistroEventos? = null
    @Volatile private var red: RedEquipos? = null
    private var huella = ""

    // Estado del reparto de datos entre equipos. Solo se toca desde el hilo 'hilo'.
    private val enVuelo = HashMap<String, Long>()        // id del registro -> cuándo se mandó al satélite
    private val rechazados = HashMap<String, Long>()     // id del registro -> cuándo lo rechazó el satélite

    private class TablaPendiente(val huella: String, val ids: MutableSet<String>, val desde: Long)

    private val tablaPendiente = HashMap<String, TablaPendiente>()   // equipo -> tabla en camino
    private val tablaEnviada = HashMap<String, String>()             // equipo -> huella de la última tabla que confirmó
    private val ensamblador = TablaRed.Ensamblador()

    /** Cada cuánto se revisa si hay registros por entregar al satélite (por si se perdió un acuse o apareció un satélite). */
    private val revision = object : Runnable {
        override fun run() {
            if (!Operacion.ajustes.redActiva) return
            hilo.execute { empujarBandeja() }
            main.postDelayed(this, CADA_REVISION_MS)
        }
    }

    /** Por qué no está funcionando la red (puertos ocupados…); null si todo bien. */
    @Volatile var error: String? = null
        private set

    /** Red Wi-Fi a la que está conectado el equipo (para que los envíos salgan siempre por ahí, aunque el 4G esté encendido). */
    @Volatile private var wifi: Network? = null
    private var vigilante: ConnectivityManager.NetworkCallback? = null

    private val atador = object : RedEquipos.Atador {
        override fun atar(s: Socket) {
            wifi?.bindSocket(s)
        }

        override fun atar(s: DatagramSocket) {
            wifi?.bindSocket(s)
        }
    }

    // ---------------------------------------------------------------- avisos a las pantallas

    fun escuchar(f: () -> Unit) { escuchas.add(f) }
    fun dejarDeEscuchar(f: () -> Unit) { escuchas.remove(f) }
    private fun avisarEstado() { main.post { escuchas.forEach { it() } } }

    override fun alCambiarEquipos() = avisarEstado()

    /** Avisos de otros equipos que esperan ser aplicados (se juntan unos instantes para guardar una sola vez). */
    private val pendientes = ConcurrentLinkedQueue<EventoRed>()
    private val vaciando = AtomicBoolean(false)
    private val vaciar = Runnable {
        vaciando.set(false)
        val lote = ArrayList<EventoRed>()
        while (true) lote.add(pendientes.poll() ?: break)
        if (lote.isNotEmpty()) Operacion.aplicarDeEquipos(lote)
    }

    override fun alEventoRemoto(e: EventoRed) {
        pendientes.add(e)
        if (vaciando.compareAndSet(false, true)) main.postDelayed(vaciar, 120)
    }

    // ---------------------------------------------------------------- arranque y parada

    private fun huellaActual(): String {
        val a = Operacion.ajustes
        return listOf(a.idDispositivo, a.nombreEquipo, a.redClaveEfectiva, a.redIps).joinToString("\u0001")
    }

    @Synchronized
    private fun registro(): RegistroEventos {
        registro?.let { return it }
        val r = RegistroEventos(File(Operacion.app.filesDir, "red_eventos.tsv"))
        r.podar(System.currentTimeMillis() - RedEquipos.RETENCION_MS)
        registro = r
        return r
    }

    @Synchronized
    private fun crear(): RedEquipos {
        val a = Operacion.ajustes
        val c = RedEquipos.Config()
        c.idEquipo = a.idDispositivo
        c.nombre = a.nombreEquipo
        c.clave = a.redClaveEfectiva
        c.operador = Supplier { Operacion.ajustes.operador }
        c.rol = Supplier { Operacion.ajustes.satelite }
        c.atador = atador
        c.puertoUdp = puertoUdpPropio
        c.puertoTcp = puertoTcpPropio
        c.destinosUdp = Supplier { RedEquipos.destinosDifusion(RedEquipos.PUERTO_UDP, Operacion.ajustes.redIps) }
        val r = RedEquipos(c, registro(), this)
        red = r
        huella = huellaActual()
        return r
    }

    /** Enciende la red entre equipos (o la reinicia si cambiaron el nombre, la clave o las IP). No espera: trabaja aparte. */
    fun arrancar() {
        hilo.execute { arrancarYa() }
    }

    @Synchronized
    private fun arrancarYa() {
        val a = Operacion.ajustes
        if (!a.redActiva) {
            detenerYa()
            return
        }
        val actual = red
        if (actual != null && actual.activa() && huella == huellaActual()) return
        actual?.detener()
        limpiarEstadoDeReparto()
        val r = crear()
        vigilarWifi()
        error = try {
            r.iniciar()
            null
        } catch (e: IOException) {
            "No se pudo abrir la red (otra aplicación usa los puertos ${RedEquipos.PUERTO_UDP} y ${RedEquipos.PUERTO_TCP})"
        } catch (e: SecurityException) {
            "Sin permiso de red"
        }
        avisarEstado()
        main.removeCallbacks(revision)
        main.postDelayed(revision, CADA_REVISION_MS)
        // Lo que ya se sabe por los otros equipos se aplica al listado (por si llegó mientras la app estaba cerrada).
        main.post { Operacion.sincronizarConEquipos() }
    }

    private fun limpiarEstadoDeReparto() {
        enVuelo.clear()
        rechazados.clear()
        tablaPendiente.clear()
        tablaEnviada.clear()
    }

    /** Apaga la red entre equipos. Lo que haga este equipo mientras tanto no se anota. */
    fun detener() {
        hilo.execute { detenerYa() }
    }

    @Synchronized
    private fun detenerYa() {
        red?.detener()
        main.removeCallbacks(revision)
        limpiarEstadoDeReparto()
        avisarEstado()
    }

    /** Aplica los Ajustes: arranca o detiene el servicio en segundo plano y el motor. */
    fun aplicarAjustes(ctx: Context) {
        if (Operacion.ajustes.redActiva) {
            iniciarServicio(ctx)
            arrancar()
            // Cambió el operario o el papel de este equipo: se avisa ya y se retoma lo pendiente con el nuevo papel.
            hilo.execute {
                red?.takeIf { it.activa() }?.actualizarPerfil()
                empujarBandeja()
                enviarTablaYa(false, null)
            }
        } else {
            try {
                ctx.stopService(Intent(ctx, ServicioRed::class.java))
            } catch (_: Exception) {
            }
            detener()
        }
    }

    /**
     * Pide el servicio que mantiene la red viva en segundo plano. Si el sistema no lo permite, la red igual funciona
     * mientras la app esté abierta.
     */
    fun iniciarServicio(ctx: Context) {
        if (!Operacion.ajustes.redActiva) return
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ServicioRed::class.java))
        } catch (_: Exception) {
            arrancar()
        }
    }

    private fun vigilarWifi() {
        if (vigilante != null) return
        try {
            val cm = Operacion.app.getSystemService(ConnectivityManager::class.java) ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    wifi = network
                }

                override fun onLost(network: Network) {
                    if (wifi == network) wifi = null
                }
            }
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb
            )
            vigilante = cb
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- lo que se muestra

    fun activa(): Boolean = red?.activa() == true

    fun equipos(): List<RedEquipos.Equipo> = red?.equipos().orEmpty()

    fun conectados(): Int = red?.conectados() ?: 0

    /** Texto corto del estado de la red para el indicador de la pantalla y la notificación. */
    fun resumen(): String {
        if (!Operacion.ajustes.redActiva) return "Red entre equipos apagada"
        if (!activa()) return error ?: "Red entre equipos detenida"
        return when (val n = conectados()) {
            0 -> "Sin equipos cerca"
            1 -> "1 equipo conectado"
            else -> "$n equipos conectados"
        }
    }

    /** Direcciones IP de este equipo en la red local (para escribirlas en los otros equipos si no se encuentran solos). */
    fun misDirecciones(): List<String> {
        val r = ArrayList<String>()
        try {
            val e = NetworkInterface.getNetworkInterfaces() ?: return r
            for (ni in e) {
                if (!ni.isUp || ni.isLoopback) continue
                for (d in ni.inetAddresses) {
                    if (d is Inet4Address && !d.isLoopbackAddress) r.add(d.hostAddress ?: continue)
                }
            }
        } catch (_: Exception) {
        }
        return r
    }

    /** Pide a los equipos conectados que comparen lo que saben ahora mismo (se pone al día lo que falte). */
    fun sincronizarAhora() {
        hilo.execute { red?.sincronizarAhora() }
    }

    // ---------------------------------------------------------------- lo que hace este equipo

    /**
     * Anota lo que acaba de hacer este equipo con una reserva y lo manda a los demás. Funciona aunque ahora no haya
     * ningún equipo cerca: queda guardado y se entrega cuando aparezcan.
     */
    fun publicar(tipo: String, fila: Int, hora: Long, equipo: String? = null) {
        val a = Operacion.ajustes
        if (!a.redActiva) return
        val l = Operacion.listado ?: return
        val codigo = l.valor(fila, Listado.Campo.CODIGO)
        val placa = l.placaNormal(fila)
        if (codigo.isBlank() && placa.isBlank()) return
        // Lo que se supo por el servidor solo le sirve a los demás si es de hoy
        if (equipo != null && EventoRed.jornadaDe(hora) != EventoRed.jornadaDe(System.currentTimeMillis())) return
        try {
            val r = red ?: synchronized(this) { red ?: crear() }
            if (equipo == null) r.publicar(tipo, codigo, placa, hora, a.operador)
            else r.publicar(tipo, codigo, placa, hora, a.operador, equipo)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- datos que van de un equipo a otro (registros, tablas)

    override fun alConectarEquipo(idEquipo: String) {
        // Apareció un equipo: se le entrega lo que estaba esperando y, si este es satélite, la tabla de reservas.
        hilo.execute {
            empujarBandeja()
            enviarTablaYa(false, idEquipo)
        }
    }

    override fun alDato(idEquipo: String, id: String, tipo: String, meta: String, datos: ByteArray): Boolean = when (tipo) {
        "EVT" -> recibirRegistro(id, meta, datos)
        "TAB" -> recibirTrozoDeTabla(idEquipo, meta, datos)
        "PIDE" -> {
            // Otro equipo pide la tabla de reservas ahora mismo
            if (!Operacion.ajustes.esSatelite) false
            else {
                hilo.execute { enviarTablaYa(true, idEquipo) }
                true
            }
        }
        else -> false
    }

    override fun alAcuse(idEquipo: String, id: String, entregado: Boolean) {
        hilo.execute {
            if (id.startsWith("TAB-")) {
                acuseDeTabla(idEquipo, id, entregado)
                return@execute
            }
            enVuelo.remove(id)
            if (entregado) {
                // El satélite lo guardó: ya no hace falta tenerlo en este equipo.
                val quitados = try { Bandeja.quitar(Operacion.app, setOf(id)) } catch (_: Exception) { emptyList() }
                for (q in quitados) {
                    val f = q.optString("foto_archivo", "")
                    if (f.isNotBlank() && (q.optString("tipo_evento") == "ALERTA" || f.startsWith(DIR_FOTOS_RED))) {
                        File(Operacion.app.filesDir, f).delete()
                    }
                }
                Operacion.avisar()
            } else {
                rechazados[id] = System.currentTimeMillis()
            }
            empujarBandeja()
        }
    }

    /** Pide que se revise ya si hay registros por entregar al satélite. */
    fun entregarPendientes() {
        if (Operacion.ajustes.redActiva) hilo.execute { empujarBandeja() }
    }

    /** Equipos conectados ahora que son satélites de algún momento. */
    fun satelites(): List<RedEquipos.Equipo> = equipos().filter { it.conectado && RedEquipos.esSatelite(it.rol) }

    /** Entrega al satélite los registros que este equipo no sube al servidor (con su foto), de a pocos y esperando el acuse. */
    private fun empujarBandeja() {
        val a = Operacion.ajustes
        val r = red ?: return
        if (!a.redActiva || !r.activa()) return
        val ctx = Operacion.app
        val ahora = System.currentTimeMillis()
        enVuelo.entries.removeAll { ahora - it.value > ACUSE_MS }
        rechazados.entries.removeAll { ahora - it.value > RECHAZO_MS }
        if (enVuelo.size >= VENTANA) return
        val destinos = r.equipos().filter { it.conectado && RedEquipos.esSatelite(it.rol) }
        if (destinos.isEmpty()) return
        val lote = try {
            Bandeja.primeros(ctx, 40 + enVuelo.size + rechazados.size, Sincronizacion.ajenos(ctx))
        } catch (_: Exception) {
            return
        }
        for (ev in lote) {
            if (enVuelo.size >= VENTANA) break
            val id = ev.optString("id_evento", "")
            if (id.isEmpty() || enVuelo.containsKey(id) || rechazados.containsKey(id)) continue
            val momento = ev.optString("momento", "INTERIOR")
            val destino = destinos.firstOrNull { RedEquipos.cubre(it.rol, momento) } ?: continue
            val datos = empaquetar(ctx, ev) ?: continue
            if (r.enviarDato(destino.id, id, "EVT", momento, datos)) enVuelo[id] = ahora
        }
    }

    /** Registro + foto en un solo bloque: [largo][JSON][largo][foto]. Sin foto, si no cabe en un mensaje. */
    private fun empaquetar(ctx: Context, ev: JSONObject): ByteArray? {
        return try {
            val o = JSONObject(ev.toString())
            val nombreFoto = o.optString("foto_archivo", "")
            o.remove("foto_archivo")
            var foto = ByteArray(0)
            if (nombreFoto.isNotBlank()) {
                val f = File(ctx.filesDir, nombreFoto)
                if (f.exists() && f.length() < RedEquipos.MAX_DATO) foto = f.readBytes()
            }
            val json = o.toString().toByteArray(Charsets.UTF_8)
            fun armar(imagen: ByteArray): ByteArray {
                val bos = ByteArrayOutputStream(json.size + imagen.size + 8)
                DataOutputStream(bos).use {
                    it.writeInt(json.size); it.write(json)
                    it.writeInt(imagen.size); it.write(imagen)
                }
                return bos.toByteArray()
            }
            val todo = armar(foto)
            if (todo.size <= RedEquipos.MAX_DATO) todo else armar(ByteArray(0)).takeIf { it.size <= RedEquipos.MAX_DATO }
        } catch (_: Exception) {
            null
        }
    }

    /** Llegó un registro de otro equipo: si este es el satélite de ese momento, lo deja en su cola para subirlo al servidor. */
    private fun recibirRegistro(id: String, momento: String, datos: ByteArray): Boolean {
        val a = Operacion.ajustes
        if (!a.esSatelite || !a.cubre(momento)) return false
        return try {
            val ctx = Operacion.app
            val e = DataInputStream(datos.inputStream())
            val nj = e.readInt()
            if (nj <= 0 || nj > datos.size) return false
            val jb = ByteArray(nj)
            e.readFully(jb)
            val nf = e.readInt()
            if (nf < 0 || nf > datos.size) return false
            val fb = ByteArray(nf)
            e.readFully(fb)
            val ev = JSONObject(String(jb, Charsets.UTF_8))
            if (ev.optString("id_evento") != id) return false
            if (fb.isNotEmpty()) {
                val rel = "$DIR_FOTOS_RED$id.jpg"
                val f = File(ctx.filesDir, rel)
                f.parentFile?.mkdirs()
                f.writeBytes(fb)
                ev.put("foto_archivo", rel)
            }
            Bandeja.agregarSiNuevo(ctx, ev)
            Sincronizacion.alRegistrar(ctx)
            Operacion.avisar()
            true
        } catch (_: Exception) {
            false
        }
    }

    // ---- tabla de reservas

    /** Algo cambió en la tabla de reservas de este equipo (la trajo del servidor o se cargó un Excel): si es satélite, la reparte. */
    fun tablaCambio() {
        if (Operacion.ajustes.redActiva && Operacion.ajustes.esSatelite) hilo.execute { enviarTablaYa(false, null) }
    }

    /** Pide a los satélites conectados que manden la tabla de reservas ahora (para los equipos que no consultan al servidor). */
    fun pedirTablas() {
        hilo.execute {
            val r = red ?: return@execute
            for (d in r.equipos()) {
                if (d.conectado && RedEquipos.esSatelite(d.rol)) r.enviarDato(d.id, "PIDE-" + System.nanoTime(), "PIDE", "", ByteArray(0))
            }
        }
    }

    /**
     * Manda la tabla de reservas a los equipos conectados (o solo a [solo]). Si ya se la confirmaron igual, no la repite,
     * salvo que se [forzar].
     */
    private fun enviarTablaYa(forzar: Boolean, solo: String?) {
        val a = Operacion.ajustes
        val r = red ?: return
        if (!a.redActiva || !r.activa() || !a.esSatelite) return
        val destinos = r.equipos().filter { it.conectado && (solo == null || it.id == solo) }
        if (destinos.isEmpty()) return
        val tabla = try {
            Operacion.enPrincipal {
                val l = Operacion.listado
                if (l == null || l.filas.isEmpty()) null else l.encabezados.toList() to l.filas.map { it.toList() }
            }
        } catch (_: Exception) {
            null
        } ?: return
        val bytes = TablaRed.codificar(tabla.first, tabla.second)
        val huella = TablaRed.huella(bytes)
        val trozos = TablaRed.trocear(bytes, TROZO)
        val ahora = System.currentTimeMillis()
        for (d in destinos) {
            if (!forzar && tablaEnviada[d.id] == huella) continue
            val p = tablaPendiente[d.id]
            if (!forzar && p != null && p.huella == huella && ahora - p.desde < 2 * ACUSE_MS) continue
            val ids = HashSet<String>()
            var completo = true
            for ((i, t) in trozos.withIndex()) {
                val idDato = "TAB-$huella-$i"
                if (r.enviarDato(d.id, idDato, "TAB", "$huella:$i:${trozos.size}", t)) ids.add(idDato) else { completo = false; break }
            }
            if (completo) tablaPendiente[d.id] = TablaPendiente(huella, ids, ahora)
        }
    }

    private fun acuseDeTabla(idEquipo: String, id: String, entregado: Boolean) {
        val p = tablaPendiente[idEquipo] ?: return
        if (!entregado) {
            tablaPendiente.remove(idEquipo)
            return
        }
        p.ids.remove(id)
        if (p.ids.isEmpty()) {
            tablaEnviada[idEquipo] = p.huella
            tablaPendiente.remove(idEquipo)
        }
    }

    /** Llegó un trozo de la tabla de un satélite. Cuando están todos, se junta con las reservas de este equipo. */
    private fun recibirTrozoDeTabla(idEquipo: String, meta: String, datos: ByteArray): Boolean {
        val x = meta.split(':')
        if (x.size != 3) return false
        val indice = x[1].toIntOrNull() ?: return false
        val total = x[2].toIntOrNull() ?: return false
        val completa = ensamblador.agregar(idEquipo + ":" + x[0], indice, total, datos) ?: return true
        hilo.execute {
            val t = TablaRed.decodificar(completa)
            if (t != null && t.filas.isNotEmpty()) {
                try {
                    Operacion.enPrincipal { Operacion.fusionarDelServidor(t.encabezados, t.filas, "Red de equipos") }
                } catch (_: Exception) {
                }
            }
        }
        return true
    }

    // ---------------------------------------------------------------- lo que se muestra en Ajustes

    /** Una línea de la lista de equipos: este equipo y los que se ven por el Wi-Fi. */
    class FilaEquipo(
        val esEste: Boolean,
        val id: String,
        val nombre: String,
        val operador: String,
        val rol: String,
        val conectado: Boolean,
        val ip: String,
        /** Cuándo llegó lo último que mandó ese equipo, y cuándo se le mandó lo último (0 = nunca). */
        val ultimaRecibida: Long,
        val ultimaEnviada: Long,
        val validadas: Int,
        val ingresadas: Int
    )

    fun detalleEquipos(): List<FilaEquipo> {
        val a = Operacion.ajustes
        val otros = equipos()
        val conteo = try {
            registro().contarPorOrigen(EventoRed.jornadaDe(System.currentTimeMillis()), Operacion.EQUIPO_SERVIDOR)
        } catch (_: Exception) {
            emptyMap<String, IntArray>()
        }
        val lista = ArrayList<FilaEquipo>()
        val mio = conteo[a.idDispositivo]
        lista.add(
            FilaEquipo(
                true, a.idDispositivo, a.nombreEquipo, a.operador, a.satelite, true,
                misDirecciones().firstOrNull().orEmpty(),
                otros.maxOfOrNull { it.ultimaRecibida } ?: 0L, otros.maxOfOrNull { it.ultimaEnviada } ?: 0L,
                mio?.get(0) ?: 0, mio?.get(1) ?: 0
            )
        )
        for (e in otros) {
            val k = conteo[e.id]
            lista.add(
                FilaEquipo(
                    false, e.id, e.nombre, e.operador, e.rol, e.conectado, e.ip,
                    e.ultimaRecibida, e.ultimaEnviada, k?.get(0) ?: 0, k?.get(1) ?: 0
                )
            )
        }
        return lista
    }

    /** Problemas con los satélites que se ven ahora: ninguno para un momento, o más de uno. */
    fun avisosDeSatelites(): List<String> {
        if (!Operacion.ajustes.redActiva) return emptyList()
        val activos = detalleEquipos().filter { it.conectado }
        val r = ArrayList<String>()
        for ((momento, nombre) in listOf("VIA" to "VÍA", "INTERIOR" to "INTERIOR")) {
            val sat = activos.filter { RedEquipos.cubre(it.rol, momento) }
            when {
                sat.isEmpty() -> r.add("⚠ No hay un satélite de $nombre conectado: lo de $nombre espera en cada equipo hasta que aparezca uno.")
                sat.size > 1 -> r.add("⚠ Hay ${sat.size} satélites de $nombre (${sat.joinToString { it.nombre.ifBlank { "sin nombre" } }}). Deje uno solo para no repetir envíos.")
            }
        }
        return r
    }

    // ---------------------------------------------------------------- lo que se sabe de los demás

    fun deReserva(codigo: String, placa: String): List<EventoRed> =
        if (Operacion.ajustes.redActiva) registro().deReserva(codigo, placa) else emptyList()

    fun eventosDesde(hora: Long): List<EventoRed> =
        if (Operacion.ajustes.redActiva) registro().desde(hora) else emptyList()
}
