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

    private val main = Handler(Looper.getMainLooper())
    private val hilo = Executors.newSingleThreadExecutor { r -> Thread(r, "red-local").apply { isDaemon = true } }
    private val escuchas = CopyOnWriteArrayList<() -> Unit>()

    private var registro: RegistroEventos? = null
    @Volatile private var red: RedEquipos? = null
    private var huella = ""

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
        c.atador = atador
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
        // Lo que ya se sabe por los otros equipos se aplica al listado (por si llegó mientras la app estaba cerrada).
        main.post { Operacion.sincronizarConEquipos() }
    }

    /** Apaga la red entre equipos. Lo que haga este equipo mientras tanto no se anota. */
    fun detener() {
        hilo.execute { detenerYa() }
    }

    @Synchronized
    private fun detenerYa() {
        red?.detener()
        avisarEstado()
    }

    /** Aplica los Ajustes: arranca o detiene el servicio en segundo plano y el motor. */
    fun aplicarAjustes(ctx: Context) {
        if (Operacion.ajustes.redActiva) {
            iniciarServicio(ctx)
            arrancar()
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
    fun publicar(tipo: String, fila: Int, hora: Long) {
        val a = Operacion.ajustes
        if (!a.redActiva) return
        val l = Operacion.listado ?: return
        val codigo = l.valor(fila, Listado.Campo.CODIGO)
        val placa = l.placaNormal(fila)
        if (codigo.isBlank() && placa.isBlank()) return
        try {
            val r = red ?: synchronized(this) { red ?: crear() }
            r.publicar(tipo, codigo, placa, hora, a.operador)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- lo que se sabe de los demás

    fun deReserva(codigo: String, placa: String): List<EventoRed> =
        if (Operacion.ajustes.redActiva) registro().deReserva(codigo, placa) else emptyList()

    fun eventosDesde(hora: Long): List<EventoRed> =
        if (Operacion.ajustes.redActiva) registro().desde(hora) else emptyList()
}
