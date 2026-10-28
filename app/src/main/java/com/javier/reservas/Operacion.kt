package com.javier.reservas

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Centro de la operación: listado, ingresos (con foto), ticket y cola de envío.
 * Lo usan la pantalla principal, la cámara de placas y los procesos en segundo plano.
 * Todo cambio se hace en el hilo principal.
 */
object Operacion {

    enum class Origen(val api: String) {
        QR("QR"), BUSQUEDA("BUSQUEDA_MANUAL"), PLACA("PLACA_CAMARA")
    }

    sealed class Registro {
        data class Nuevo(val fila: Int) : Registro()
        data class Repetido(val fila: Int, val hora: Long) : Registro()
    }

    private var iniciado = false
    lateinit var app: Context
        private set
    lateinit var ajustes: Ajustes
        private set

    @Volatile var listado: Listado? = null
        private set
    val info = HashMap<Int, InfoIngreso>()

    private val escuchas = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val co = Locale("es", "CO")
    private val fmtFecha = SimpleDateFormat("dd/MM/yyyy hh:mm a", co)

    private val archivo: File get() = File(app.filesDir, "reservas.json")
    private val dirFotos: File get() = File(app.filesDir, "fotos").apply { mkdirs() }

    @Synchronized
    fun iniciar(ctx: Context) {
        if (iniciado) return
        app = ctx.applicationContext
        ajustes = Ajustes(app)
        val (l, inf) = Persistencia.cargar(archivo)
        listado = l
        info.putAll(inf)
        iniciado = true
    }

    // ---------------------------------------------------------------- avisos a las pantallas

    fun escuchar(f: () -> Unit) { escuchas.add(f) }
    fun dejarDeEscuchar(f: () -> Unit) { escuchas.remove(f) }
    fun avisar() { main.post { escuchas.forEach { it() } } }

    /** Ejecuta en el hilo principal y espera (para los procesos en segundo plano). */
    fun <T> enPrincipal(bloque: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return bloque()
        var r: Result<T>? = null
        val espera = CountDownLatch(1)
        main.post {
            r = runCatching(bloque)
            espera.countDown()
        }
        espera.await(30, TimeUnit.SECONDS)
        return r?.getOrThrow() ?: throw IllegalStateException("Tiempo de espera agotado")
    }

    fun guardar() {
        try {
            Persistencia.guardar(archivo, listado, info)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- listado

    fun reemplazarListado(l: Listado) {
        listado = l
        info.clear()
        dirFotos.listFiles()?.forEach { it.delete() }
        guardar()
        avisar()
    }

    /** Agrega/actualiza reservas recibidas del servidor. Devuelve {nuevas, actualizadas}. */
    fun fusionarDelServidor(encabezados: List<String>, filas: List<List<String>>): IntArray {
        val l = listado ?: Listado.vacio("Servidor").also { listado = it }
        val r = l.fusionar(encabezados, filas)
        guardar()
        avisar()
        return r
    }

    // ---------------------------------------------------------------- ingresos

    fun registrar(fila: Int, origen: Origen, placaLeida: String? = null, foto: Bitmap? = null): Registro {
        val l = listado ?: throw IllegalStateException("No hay listado")
        l.ingresos[fila]?.let { return Registro.Repetido(fila, it) }
        val ahora = System.currentTimeMillis()
        l.ingresos[fila] = ahora
        val id = UUID.randomUUID().toString()
        val archivoFoto = foto?.let { guardarFoto(id, it) }
        info[fila] = InfoIngreso(id, origen.api, placaLeida.orEmpty(), archivoFoto)
        guardar()
        Bandeja.agregar(app, evento(fila, "INGRESO"))
        Sincronizacion.alRegistrar(app)
        avisar()
        return Registro.Nuevo(fila)
    }

    fun anular(fila: Int) {
        val l = listado ?: return
        if (!l.ingresado(fila)) return
        Bandeja.agregar(app, evento(fila, "ANULACION"))
        l.ingresos.remove(fila)
        info.remove(fila)?.foto?.let { File(app.filesDir, it).delete() }
        guardar()
        Sincronizacion.alRegistrar(app)
        avisar()
    }

    fun borrarIngresos() {
        val l = listado ?: return
        l.ingresos.clear()
        info.clear()
        dirFotos.listFiles()?.forEach { it.delete() }
        guardar()
        avisar()
    }

    fun foto(fila: Int): File? =
        info[fila]?.foto?.let { File(app.filesDir, it) }?.takeIf { it.exists() }

    private fun guardarFoto(id: String, bmp: Bitmap): String? = try {
        val max = 1024
        val escala = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
        val b = if (escala < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * escala).toInt(), (bmp.height * escala).toInt(), true) else bmp
        val f = File(dirFotos, "$id.jpg")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 72, it) }
        "fotos/$id.jpg"
    } catch (e: Exception) {
        null
    }

    /** Registro tal como se envía al servidor (la foto se adjunta al enviar). */
    private fun evento(fila: Int, tipo: String): JSONObject {
        val l = listado!!
        val i = info[fila]
        val t = l.ingresos[fila] ?: System.currentTimeMillis()
        return JSONObject().apply {
            put("id_evento", UUID.randomUUID().toString())
            put("tipo_evento", tipo)
            put("id_ingreso", i?.id ?: "")
            put("codigo_reserva", l.valor(fila, Listado.Campo.CODIGO))
            put("placa", l.placaNormal(fila))
            put("nombre", l.valor(fila, Listado.Campo.NOMBRE))
            put("evento", l.valor(fila, Listado.Campo.EVENTO))
            put("tipo_cliente", l.valor(fila, Listado.Campo.TIPO).uppercase())
            put("vip", l.esVip(fila))
            put("fecha_ingreso", iso(t))
            put("fecha_evento", iso(System.currentTimeMillis()))
            put("origen", i?.origen ?: "")
            put("placa_leida", i?.placaLeida ?: "")
            put("operador", ajustes.operador)
            put("dispositivo", ajustes.idDispositivo)
            put("modelo", Build.MODEL ?: "")
            put("datos", JSONObject().apply {
                l.encabezados.forEachIndexed { c, h -> put(h, l.filas[fila][c]) }
            })
            if (tipo == "INGRESO" && i?.foto != null) put("foto_archivo", i.foto)
        }
    }

    fun iso(t: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
        f.timeZone = TimeZone.getDefault()
        return f.format(Date(t))
    }

    // ---------------------------------------------------------------- ticket

    fun lineasTicket(fila: Int, reimpresion: Boolean): List<Linea> {
        val l = listado ?: return emptyList()
        val t = l.ingresos[fila] ?: System.currentTimeMillis()
        val nombre = l.valor(fila, Listado.Campo.NOMBRE)
        val evento = l.valor(fila, Listado.Campo.EVENTO)
        val codigo = l.valor(fila, Listado.Campo.CODIGO)
        return Ticket.lineas(
            negocio = ajustes.negocio,
            titulo = "RESERVA INGRESADA",
            nombre = nombre,
            codigo = codigo,
            placa = l.valor(fila, Listado.Campo.PLACA),
            evento = evento,
            tipo = l.valor(fila, Listado.Campo.TIPO),
            vip = l.esVip(fila),
            fechaHora = fmtFecha.format(Date(t)),
            mensaje = ajustes.textoBienvenida(nombre, evento),
            mensajeVip = ajustes.mensajeVip,
            qr = if (ajustes.imprimirQr && codigo.isNotBlank()) codigo else null,
            reimpresion = reimpresion,
            operador = ajustes.operador.takeIf { it != "Operador" }.orEmpty()
        )
    }

    fun lineasPrueba(): List<Linea> = Ticket.lineas(
        negocio = ajustes.negocio,
        titulo = "RESERVA INGRESADA",
        nombre = "Cliente de prueba",
        codigo = "R-0000",
        placa = "ABC123",
        evento = "Evento de prueba",
        tipo = "VIP",
        vip = true,
        fechaHora = fmtFecha.format(Date()),
        mensaje = ajustes.textoBienvenida("Cliente de prueba", "Evento de prueba"),
        mensajeVip = ajustes.mensajeVip,
        qr = if (ajustes.imprimirQr) "R-0000" else null,
        reimpresion = false,
        operador = ajustes.operador.takeIf { it != "Operador" }.orEmpty()
    )
}
