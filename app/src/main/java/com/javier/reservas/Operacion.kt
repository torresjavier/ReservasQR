package com.javier.reservas

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
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
 * Centro de la operación: listado, validaciones en la vía, ingresos con ticket, alertas y cola de envío.
 * Lo usan la pantalla principal, la cámara de placas y los procesos en segundo plano.
 * Todo cambio se hace en el hilo principal.
 *
 * La operación tiene DOS MOMENTOS:
 *  1. VÍA: se lee el QR o la placa y solo se VALIDA (da acceso). Rápido, sin detener el carro.
 *  2. INTERIOR: ya adentro se lee otra vez, se INGRESA la reserva y se imprime el ticket.
 * Qué hace cada lector (QR / placa) se configura en Ajustes con el PIN.
 */
object Operacion {

    enum class Origen(val api: String) {
        QR("QR"), BUSQUEDA("BUSQUEDA_MANUAL"), PLACA("PLACA_CAMARA")
    }

    enum class Accion { VALIDAR, INGRESAR }

    /** Qué pasó al procesar una lectura. [aviso] es una advertencia que no impide el paso. */
    sealed class Resultado {
        abstract val fila: Int
        data class Validado(override val fila: Int, val aviso: String?) : Resultado()
        /** [porEquipo]: nombre del otro equipo que la validó, si no fue este (red entre equipos). */
        data class YaValidado(override val fila: Int, val hora: Long, val sospechoso: Boolean, val porEquipo: String? = null) : Resultado()
        data class Ingresado(override val fila: Int, val sinValidar: Boolean, val aviso: String?) : Resultado()
        data class YaIngreso(override val fila: Int, val hora: Long, val porEquipo: String? = null) : Resultado()
        /** Adentro se exige haber pasado por la vía y esta reserva no pasó. */
        data class SinValidacion(override val fila: Int) : Resultado()
    }

    /** Dos lecturas iguales dentro de este tiempo se consideran la misma persona (no es una repetición sospechosa). */
    private const val DUPLICADO_MS = 20_000L
    private const val ENFRIA_ALERTA_MS = 30_000L

    /** Las validaciones e ingresos que llegaron de otro equipo se guardan con este origen seguido del nombre del equipo. */
    const val PREFIJO_EQUIPO = "EQUIPO:"

    private var iniciado = false
    lateinit var app: Context
        private set
    lateinit var ajustes: Ajustes
        private set

    @Volatile var listado: Listado? = null
        private set
    /** Datos del ingreso (con ticket) de cada fila. */
    val info = HashMap<Int, InfoIngreso>()
    /** Datos de la validación en la vía de cada fila. */
    val infoVal = HashMap<Int, InfoIngreso>()

    private val escuchas = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val co = Locale("es", "CO")
    private val fmtFecha = SimpleDateFormat("dd/MM/yyyy hh:mm a", co)
    private val alertaReciente = HashMap<String, Long>()

    private val archivo: File get() = File(app.filesDir, "reservas.json")
    private val dirFotos: File get() = File(app.filesDir, "fotos").apply { mkdirs() }

    @Synchronized
    fun iniciar(ctx: Context) {
        if (iniciado) return
        app = ctx.applicationContext
        ajustes = Ajustes(app)
        val d = Persistencia.cargar(archivo)
        listado = d.listado
        info.putAll(d.info)
        infoVal.putAll(d.infoVal)
        iniciado = true
        Sincronizacion.escucharRed(app)
        Sincronizacion.iniciarLatido(app)
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
            Persistencia.guardar(archivo, listado, info, infoVal)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- modo de trabajo

    fun accionPara(origen: Origen): Accion {
        val v = when (origen) {
            Origen.QR -> ajustes.accionQr
            Origen.PLACA, Origen.BUSQUEDA -> ajustes.accionPlaca
        }
        return if (v == Accion.VALIDAR.name) Accion.VALIDAR else Accion.INGRESAR
    }

    /** Texto corto para la pantalla principal: qué hace este equipo. */
    fun descripcionModo(): String {
        val q = accionPara(Origen.QR)
        val p = accionPara(Origen.PLACA)
        return when {
            q == Accion.VALIDAR && p == Accion.VALIDAR -> "VÍA · solo valida"
            q == Accion.INGRESAR && p == Accion.INGRESAR -> "INTERIOR · registra e imprime"
            q == Accion.VALIDAR -> "MIXTO · QR valida, placa registra"
            else -> "MIXTO · QR registra, placa valida"
        }
    }

    // ---------------------------------------------------------------- listado

    fun reemplazarListado(l: Listado) {
        listado = l
        info.clear()
        infoVal.clear()
        dirFotos.listFiles()?.forEach { it.delete() }
        reaplicarEquipos()
        guardar()
        avisar()
    }

    /** Agrega/actualiza reservas recibidas del servidor. Devuelve {nuevas, actualizadas}. */
    fun fusionarDelServidor(encabezados: List<String>, filas: List<List<String>>): IntArray {
        val l = listado ?: Listado.vacio("Servidor").also { listado = it }
        val r = l.fusionar(encabezados, filas)
        reaplicarEquipos()
        guardar()
        avisar()
        return r
    }

    /**
     * El servidor puede decir en qué estado está cada reserva (VALIDADA / INGRESADA) según lo que
     * enviaron los otros equipos. Así el equipo de adentro sabe quién ya pasó por la vía.
     * Solo avanza el estado (pendiente → validada → ingresada) y no toca lo que este equipo aún no ha enviado.
     */
    fun aplicarEstados(lista: JSONArray): Int {
        val l = listado ?: return 0
        val enCola = Bandeja.claves(app)
        var cambios = 0
        for (i in 0 until lista.length()) {
            val o = lista.optJSONObject(i) ?: continue
            val estado = o.optString("estado", "").uppercase(Locale.ROOT)
            if (estado.isEmpty()) continue
            val codigo = primero(o, "codigo_reserva", "codigo", "reserva", "id_reserva", "id")
            val placa = primero(o, "placa")
            val fila = l.buscarFila(codigo, placa)
            if (fila < 0) continue
            if (clave(codigo) in enCola || clave(placa) in enCola) continue
            if (estado.startsWith("INGRES")) {
                if (!l.ingresos.containsKey(fila)) {
                    l.ingresos[fila] = analizarIso(o.optString("fecha_ingreso", "")) ?: System.currentTimeMillis()
                    cambios++
                }
            } else if (estado.startsWith("VALID")) {
                if (!l.ingresos.containsKey(fila) && !l.validaciones.containsKey(fila)) {
                    l.validaciones[fila] = analizarIso(o.optString("fecha_validacion", "")) ?: System.currentTimeMillis()
                    cambios++
                }
            }
        }
        if (cambios > 0) {
            guardar()
            avisar()
        }
        return cambios
    }

    private fun primero(o: JSONObject, vararg claves: String): String {
        for (k in claves) {
            if (o.has(k) && !o.isNull(k)) {
                val v = o.optString(k, "")
                if (v.isNotBlank()) return v
            }
        }
        return ""
    }

    fun clave(s: String): String = Listado.normPlaca(s)

    // ---------------------------------------------------------------- red entre equipos

    /** Nombre del otro equipo que hizo esta validación/ingreso, o null si lo hizo este equipo. */
    fun equipoDe(i: InfoIngreso?): String? =
        i?.origen?.takeIf { it.startsWith(PREFIJO_EQUIPO) }?.removePrefix(PREFIJO_EQUIPO)?.ifBlank { "otro equipo" }

    /**
     * Hora que se anota en un evento de esta reserva: la actual, o un instante después de lo último que se sabe de ella
     * si el reloj de otro equipo va adelantado (así una anulación siempre queda después de lo que anula).
     */
    private fun horaEvento(fila: Int): Long {
        val ahora = System.currentTimeMillis()
        val l = listado ?: return ahora
        val ultima = RedLocal.deReserva(l.valor(fila, Listado.Campo.CODIGO), l.placaNormal(fila)).maxOfOrNull { it.hora } ?: return ahora
        // Solo se corrige un adelanto pequeño; si el reloj de otro equipo está muy mal puesto, no se le hace caso.
        return if (ultima >= ahora && ultima - ahora <= 120_000L) ultima + 1 else ahora
    }

    /** Llegó un aviso de otro equipo (hilo principal): se aplica a la reserva si corresponde. */
    fun aplicarDeEquipo(e: EventoRed) = aplicarDeEquipos(listOf(e))

    /** Varios avisos juntos (por ejemplo, un equipo que se pone al día): se aplican y se guarda una sola vez. */
    fun aplicarDeEquipos(eventos: List<EventoRed>) {
        val l = listado ?: return
        if (!ajustes.redActiva) return
        val hoy = EventoRed.jornadaDe(System.currentTimeMillis())
        val corte = ajustes.redCorte
        val filas = LinkedHashSet<Int>()
        for (e in eventos) {
            if (e.jornada != hoy || e.hora <= corte) continue
            val fila = l.buscarFila(e.codigo, e.placa)
            if (fila >= 0) filas.add(fila)
        }
        var cambios = 0
        for (f in filas) if (aplicarFila(l, f, hoy, corte)) cambios++
        if (cambios > 0) {
            guardar()
            avisar()
        }
    }

    /** Aplica al listado todo lo que se sabe de los otros equipos hoy (por ejemplo, al abrir la app o cargar un listado). */
    fun sincronizarConEquipos() {
        if (reaplicarEquipos() > 0) {
            guardar()
            avisar()
        }
    }

    /** @return cuántas reservas cambiaron. Se puede repetir sin problema: el resultado no depende de cuántas veces se llame. */
    private fun reaplicarEquipos(): Int {
        val l = listado ?: return 0
        if (!ajustes.redActiva) return 0
        val hoy = EventoRed.jornadaDe(System.currentTimeMillis())
        val corte = ajustes.redCorte
        val filas = LinkedHashSet<Int>()
        for (e in RedLocal.eventosDesde(corte + 1)) {
            if (e.jornada != hoy) continue
            val fila = l.buscarFila(e.codigo, e.placa)
            if (fila >= 0) filas.add(fila)
        }
        var cambios = 0
        for (f in filas) if (aplicarFila(l, f, hoy, corte)) cambios++
        return cambios
    }

    private fun aplicarFila(l: Listado, fila: Int, hoy: String, corte: Long): Boolean {
        val codigo = l.valor(fila, Listado.Campo.CODIGO)
        val kc = clave(codigo)
        val eventos = RedLocal.deReserva(codigo, l.placaNormal(fila)).filter {
            it.jornada == hoy && it.hora > corte && (kc.isEmpty() || it.codigo.isBlank() || clave(it.codigo) == kc)
        }
        if (eventos.isEmpty()) return false
        val c = EstadoCompartido.calcular(eventos, l.validaciones[fila], l.ingresos[fila])
        if (!c.hayCambio()) return false
        if (c.quitarValidacion) {
            l.validaciones.remove(fila)
            infoVal.remove(fila)?.foto?.let { File(app.filesDir, it).delete() }
        }
        c.validacion?.let { hora ->
            l.validaciones[fila] = hora
            val o = c.origenValidacion
            infoVal[fila] = InfoIngreso(o?.id() ?: UUID.randomUUID().toString(), PREFIJO_EQUIPO + o?.equipo.orEmpty(), "", null)
        }
        if (c.quitarIngreso) {
            l.ingresos.remove(fila)
            info.remove(fila)?.foto?.let { File(app.filesDir, it).delete() }
        }
        c.ingreso?.let { hora ->
            l.ingresos[fila] = hora
            val o = c.origenIngreso
            info[fila] = InfoIngreso(o?.id() ?: UUID.randomUUID().toString(), PREFIJO_EQUIPO + o?.equipo.orEmpty(), "", null)
        }
        return true
    }

    // ---------------------------------------------------------------- lecturas: validar o ingresar

    /**
     * Procesa una lectura (QR, placa de la cámara o búsqueda manual) según el modo del equipo.
     * @param tipoVisto tipo de vehículo que vio la cámara (carro/moto), si se sabe
     * @param forzar    el operador decidió ingresar aunque no haya pasado por la vía
     * @param accion    para ignorar el modo del equipo (por ejemplo, ingresar a mano desde la lista)
     */
    fun procesar(
        fila: Int,
        origen: Origen,
        placaLeida: String? = null,
        foto: Bitmap? = null,
        tipoVisto: OcrPlaca.Tipo? = null,
        forzar: Boolean = false,
        accion: Accion? = null
    ): Resultado {
        val l = listado ?: throw IllegalStateException("No hay listado")
        l.ingresos[fila]?.let { return Resultado.YaIngreso(fila, it, equipoDe(info[fila])) }
        val ahora = System.currentTimeMillis()
        val aviso = avisoTipo(fila, tipoVisto, placaLeida, foto)

        if ((accion ?: accionPara(origen)) == Accion.VALIDAR) {
            val previa = l.validaciones[fila]
            if (previa != null) {
                val sospechoso = ahora - previa > DUPLICADO_MS
                if (sospechoso) {
                    alerta(
                        Alertas.REPETIDA_EN_VIA, fila, placaLeida.orEmpty(), foto,
                        "Ya se había validado a las ${hora(previa)}. Puede ser un QR o una placa repetida."
                    )
                }
                return Resultado.YaValidado(fila, previa, sospechoso, equipoDe(infoVal[fila]))
            }
            validar(fila, origen, placaLeida, foto)
            return Resultado.Validado(fila, aviso)
        }

        val validada = l.validaciones.containsKey(fila)
        if (!validada && ajustes.exigirValidacion && !forzar) {
            alerta(
                Alertas.BLOQUEADO_SIN_VALIDAR, fila, placaLeida.orEmpty(), foto,
                "Intentó ingresar sin haber sido validado en la vía."
            )
            return Resultado.SinValidacion(fila)
        }
        ingresar(fila, origen, placaLeida, foto)
        if (!validada) {
            alerta(
                Alertas.INGRESO_SIN_VALIDAR, fila, placaLeida.orEmpty(), null,
                "Se registró adentro sin pasar antes por la vía."
            )
        }
        return Resultado.Ingresado(fila, !validada, aviso)
    }

    private fun validar(fila: Int, origen: Origen, placaLeida: String?, foto: Bitmap?) {
        val l = listado ?: return
        val ahora = horaEvento(fila)
        l.validaciones[fila] = ahora
        val id = UUID.randomUUID().toString()
        val archivoFoto = foto?.let { guardarFoto(id, it) }
        infoVal[fila] = InfoIngreso(id, origen.api, placaLeida.orEmpty(), archivoFoto)
        guardar()
        RedLocal.publicar(EventoRed.VALIDACION, fila, ahora)
        if (ajustes.enviarAlValidar) {
            val e = nuevoEvento("VALIDACION", fila, "VALIDADA", "VIA")
            e.put("id_validacion", id)
            e.put("fecha_validacion", iso(ahora))
            e.put("origen", origen.api)
            e.put("placa_leida", placaLeida.orEmpty())
            if (archivoFoto != null) e.put("foto_archivo", archivoFoto)
            Bandeja.agregar(app, e)
            Sincronizacion.alRegistrar(app)
        }
        avisar()
    }

    private fun ingresar(fila: Int, origen: Origen, placaLeida: String?, foto: Bitmap?) {
        val l = listado ?: return
        val ahora = horaEvento(fila)
        l.ingresos[fila] = ahora
        val id = UUID.randomUUID().toString()
        val archivoFoto = foto?.let { guardarFoto(id, it) }
        info[fila] = InfoIngreso(id, origen.api, placaLeida.orEmpty(), archivoFoto)
        guardar()
        RedLocal.publicar(EventoRed.INGRESO, fila, ahora)
        val e = nuevoEvento("INGRESO", fila, "INGRESADA", "INTERIOR")
        e.put("id_ingreso", id)
        e.put("fecha_ingreso", iso(ahora))
        l.validaciones[fila]?.let {
            e.put("validado_en_via", true)
            e.put("fecha_validacion", iso(it))
        } ?: e.put("validado_en_via", false)
        e.put("origen", origen.api)
        e.put("placa_leida", placaLeida.orEmpty())
        if (archivoFoto != null) e.put("foto_archivo", archivoFoto)
        Bandeja.agregar(app, e)
        Sincronizacion.alRegistrar(app)
        avisar()
    }

    /** Anula el ingreso (vuelve a «validada» si había pasado por la vía) o, si solo estaba validada, la validación. */
    fun anular(fila: Int) {
        val l = listado ?: return
        val e: JSONObject
        val tipoRed: String
        val horaRed = horaEvento(fila)
        if (l.ingresado(fila)) {
            val previo = info[fila]
            val tiempo = l.ingresos[fila]
            tipoRed = EventoRed.ANULA_INGRESO
            l.ingresos.remove(fila)
            e = nuevoEvento("ANULACION", fila, if (l.validado(fila)) "VALIDADA" else "PENDIENTE", "INTERIOR")
            e.put("anula", "INGRESO")
            e.put("id_ingreso", previo?.id ?: "")
            if (tiempo != null) e.put("fecha_ingreso", iso(tiempo))
            info.remove(fila)?.foto?.let { File(app.filesDir, it).delete() }
        } else if (l.validado(fila)) {
            val previo = infoVal[fila]
            val tiempo = l.validaciones[fila]
            tipoRed = EventoRed.ANULA_VALIDACION
            l.validaciones.remove(fila)
            e = nuevoEvento("ANULACION", fila, "PENDIENTE", "VIA")
            e.put("anula", "VALIDACION")
            e.put("id_validacion", previo?.id ?: "")
            if (tiempo != null) e.put("fecha_validacion", iso(tiempo))
            infoVal.remove(fila)?.foto?.let { File(app.filesDir, it).delete() }
        } else return
        guardar()
        RedLocal.publicar(tipoRed, fila, horaRed)
        Bandeja.agregar(app, e)
        Sincronizacion.alRegistrar(app)
        avisar()
    }

    fun borrarIngresos() {
        val l = listado ?: return
        // Lo que los otros equipos avisaron hasta ahora ya no se vuelve a aplicar a este equipo.
        ajustes.redCorte = System.currentTimeMillis()
        l.ingresos.clear()
        l.validaciones.clear()
        info.clear()
        infoVal.clear()
        dirFotos.listFiles()?.forEach { it.delete() }
        guardar()
        avisar()
    }

    fun foto(fila: Int): File? =
        (info[fila]?.foto ?: infoVal[fila]?.foto)?.let { File(app.filesDir, it) }?.takeIf { it.exists() }

    // ---------------------------------------------------------------- alertas (intentos de colarse)

    /** Advierte si la cámara ve una placa de carro y la reserva es de moto (o al revés). No bloquea. */
    private fun avisoTipo(fila: Int, tipoVisto: OcrPlaca.Tipo?, placaLeida: String?, foto: Bitmap?): String? {
        val l = listado ?: return null
        if (tipoVisto == null) return null
        val placa = l.placaNormal(fila)
        if (placa.length < 5) return null
        val esperado = OcrPlaca.tipoDePlaca(placa)
        if (esperado == tipoVisto) return null
        val txt = "La cámara ve una placa de ${nombreTipo(tipoVisto)} y la reserva es de ${nombreTipo(esperado)}. Revise el vehículo."
        alerta(Alertas.TIPO_NO_COINCIDE, fila, placaLeida.orEmpty(), foto, txt)
        return txt
    }

    fun nombreTipo(t: OcrPlaca.Tipo) = if (t == OcrPlaca.Tipo.MOTO) "MOTO" else "CARRO"

    /**
     * Lectura que no está en el listado: en la vía es alguien sin reserva; adentro, un vehículo que se coló.
     * Devuelve true si se registró la alerta (no se repite para la misma placa durante 30 s).
     */
    fun alertaSinReserva(origen: Origen, textoLeido: String, foto: Bitmap?): Boolean {
        val motivo = if (accionPara(origen) == Accion.VALIDAR) Alertas.SIN_RESERVA_VIA else Alertas.SIN_RESERVA_INTERIOR
        val detalle = if (motivo == Alertas.SIN_RESERVA_VIA) "Quiso pasar sin reserva en el listado." else "Hay un vehículo adentro sin reserva en el listado."
        return alerta(motivo, null, textoLeido, foto, detalle)
    }

    fun alerta(motivo: String, fila: Int?, textoLeido: String, foto: Bitmap?, detalle: String): Boolean {
        val l = listado
        val k = motivo + ":" + (fila?.toString() ?: Listado.normPlaca(textoLeido))
        val ahora = System.currentTimeMillis()
        if ((alertaReciente[k] ?: 0L) > ahora) return false
        if (alertaReciente.size > 500) alertaReciente.entries.removeAll { it.value < ahora }
        alertaReciente[k] = ahora + ENFRIA_ALERTA_MS

        val placa = if (fila != null && l != null) l.placaNormal(fila) else Listado.normPlaca(textoLeido)
        val nombre = if (fila != null && l != null) l.valor(fila, Listado.Campo.NOMBRE) else ""
        Alertas.agregar(app, motivo, placa, nombre, detalle)
        if (ajustes.enviarAlertas) {
            val e = nuevoEvento("ALERTA", fila, if (fila != null) estadoActual(fila) else "SIN_RESERVA", momentoActual())
            e.put("motivo", motivo)
            e.put("detalle", detalle)
            e.put("placa_leida", textoLeido)
            if (fila == null) e.put("placa", Listado.normPlaca(textoLeido))
            if (foto != null) guardarFoto(e.getString("id_evento"), foto)?.let { e.put("foto_archivo", it) }
            Bandeja.agregar(app, e)
            Sincronizacion.alRegistrar(app)
        }
        avisar()
        return true
    }

    private fun momentoActual(): String =
        if (accionPara(Origen.PLACA) == Accion.VALIDAR && accionPara(Origen.QR) == Accion.VALIDAR) "VIA" else "INTERIOR"

    fun estadoActual(fila: Int): String {
        val l = listado ?: return "PENDIENTE"
        return when {
            l.ingresos.containsKey(fila) -> "INGRESADA"
            l.validaciones.containsKey(fila) -> "VALIDADA"
            else -> "PENDIENTE"
        }
    }

    /** Minutos que lleva esperando una reserva validada sin ingresar (0 si no aplica). */
    fun minutosEsperando(fila: Int): Long {
        val l = listado ?: return 0
        val v = l.validaciones[fila] ?: return 0
        if (l.ingresos.containsKey(fila)) return 0
        return (System.currentTimeMillis() - v) / 60_000L
    }

    // ---------------------------------------------------------------- fotos y eventos

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

    /** Identificador de la reserva en el servidor (columna id / id_reserva) o, si no hay, su código. */
    private fun idReserva(fila: Int): String {
        val l = listado ?: return ""
        for ((c, h) in l.encabezados.withIndex()) {
            val n = Listado.normPlaca(h)
            if (n == "ID" || n == "IDRESERVA" || n == "IDENTIFICADOR" || n == "IDEVENTO") {
                val v = l.filas[fila][c]
                if (v.isNotBlank()) return v
            }
        }
        return l.valor(fila, Listado.Campo.CODIGO)
    }

    /** Datos comunes de todo registro que se envía al servidor (la foto se adjunta al enviar). */
    private fun nuevoEvento(tipo: String, fila: Int?, estadoDespues: String, momento: String): JSONObject {
        val l = listado
        val o = JSONObject()
        o.put("id_evento", UUID.randomUUID().toString())
        o.put("tipo_evento", tipo)
        o.put("momento", momento)
        o.put("estado_reserva", estadoDespues)
        o.put("fecha_evento", iso(System.currentTimeMillis()))
        o.put("operador", ajustes.operador)
        o.put("dispositivo", ajustes.idDispositivo)
        o.put("modelo", Build.MODEL ?: "")
        if (fila != null && l != null && fila < l.filas.size) {
            o.put("id_reserva", idReserva(fila))
            o.put("codigo_reserva", l.valor(fila, Listado.Campo.CODIGO))
            o.put("placa", l.placaNormal(fila))
            o.put("tipo_vehiculo", if (l.placaNormal(fila).length >= 5) nombreTipo(OcrPlaca.tipoDePlaca(l.placaNormal(fila))) else "")
            o.put("nombre", l.valor(fila, Listado.Campo.NOMBRE))
            o.put("evento", l.valor(fila, Listado.Campo.EVENTO))
            o.put("tipo_cliente", l.valor(fila, Listado.Campo.TIPO).uppercase())
            o.put("vip", l.esVip(fila))
            o.put("datos", JSONObject().apply {
                l.encabezados.forEachIndexed { c, h -> put(h, l.filas[fila][c]) }
            })
        }
        return o
    }

    fun iso(t: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
        f.timeZone = TimeZone.getDefault()
        return f.format(Date(t))
    }

    fun analizarIso(s: String): Long? {
        if (s.isBlank()) return null
        val patrones = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (p in patrones) {
            try {
                val f = SimpleDateFormat(p, Locale.ROOT)
                if (p.endsWith("'Z'")) f.timeZone = TimeZone.getTimeZone("UTC")
                val d = f.parse(s)
                if (d != null) return d.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun hora(t: Long): String = SimpleDateFormat("hh:mm a", co).format(Date(t))

    // ---------------------------------------------------------------- ticket

    fun lineasTicket(fila: Int, reimpresion: Boolean, titulo: String = "RESERVA INGRESADA"): List<Linea> {
        val l = listado ?: return emptyList()
        val t = l.ingresos[fila] ?: l.validaciones[fila] ?: System.currentTimeMillis()
        val nombre = l.valor(fila, Listado.Campo.NOMBRE)
        val evento = l.valor(fila, Listado.Campo.EVENTO)
        val codigo = l.valor(fila, Listado.Campo.CODIGO)
        return Ticket.lineas(
            negocio = ajustes.negocio,
            titulo = titulo,
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

    /** Comprobante corto de la vía («ACCESO VALIDADO»). */
    fun lineasValidacion(fila: Int): List<Linea> = lineasTicket(fila, false, "ACCESO VALIDADO")

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
