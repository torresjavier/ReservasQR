package com.javier.reservas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Datos de cada ingreso que no están en el Excel. */
data class InfoIngreso(
    val id: String,
    val origen: String,
    val placaLeida: String,
    val foto: String?
)

/** Guarda el listado, los ingresos y sus fotos/datos en el equipo. */
object Persistencia {

    /** Lo que se recupera del archivo: listado, datos de cada ingreso y de cada validación en la vía. */
    class Datos(val listado: Listado?, val info: Map<Int, InfoIngreso>, val infoVal: Map<Int, InfoIngreso>)

    private fun infoAJson(info: Map<Int, InfoIngreso>) = JSONObject().apply {
        info.forEach { (k, v) ->
            put(k.toString(), JSONObject().apply {
                put("id", v.id)
                put("origen", v.origen)
                put("placaLeida", v.placaLeida)
                if (v.foto != null) put("foto", v.foto)
            })
        }
    }

    private fun jsonAInfo(inf: JSONObject?): Map<Int, InfoIngreso> {
        val info = HashMap<Int, InfoIngreso>()
        if (inf == null) return info
        for (k in inf.keys()) {
            val fila = k.toIntOrNull() ?: continue
            val x = inf.getJSONObject(k)
            info[fila] = InfoIngreso(
                x.optString("id", UUID.randomUUID().toString()),
                x.optString("origen", "QR"),
                x.optString("placaLeida", ""),
                if (x.has("foto")) x.optString("foto") else null
            )
        }
        return info
    }

    fun guardar(archivo: File, l: Listado?, info: Map<Int, InfoIngreso>, infoVal: Map<Int, InfoIngreso>) {
        if (l == null) {
            archivo.delete(); return
        }
        val o = JSONObject()
        o.put("v", 4)
        o.put("nombre", l.nombreArchivo)
        o.put("encabezados", JSONArray(l.encabezados))
        o.put("filas", JSONArray().apply { l.filas.forEach { put(JSONArray(it)) } })
        o.put("columnas", JSONArray().apply { l.columnas.forEach { put(it) } })
        o.put("ingresos", JSONObject().apply { l.ingresos.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("validaciones", JSONObject().apply { l.validaciones.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("info", infoAJson(info))
        o.put("infoVal", infoAJson(infoVal))
        val texto = o.toString()
        val tmp = File(archivo.path + ".tmp")
        tmp.writeText(texto)
        if (!tmp.renameTo(archivo)) {
            archivo.writeText(texto)
            tmp.delete()
        }
    }

    fun cargar(archivo: File): Datos {
        if (!archivo.exists()) return Datos(null, emptyMap(), emptyMap())
        return try {
            val o = JSONObject(archivo.readText())
            val l = Listado()
            l.nombreArchivo = o.optString("nombre")
            val enc = o.getJSONArray("encabezados")
            l.encabezados = MutableList(enc.length()) { enc.optString(it) }
            val f = o.getJSONArray("filas")
            val filas = ArrayList<List<String>>(f.length())
            for (i in 0 until f.length()) {
                val a = f.getJSONArray(i)
                filas.add(MutableList(l.encabezados.size) { c -> if (c < a.length()) a.optString(c) else "" })
            }
            l.filas = filas
            val cols = o.optJSONArray("columnas")
            if (cols != null) {
                for (i in 0 until minOf(cols.length(), l.columnas.size)) l.columnas[i] = cols.optInt(i, -1)
            }
            val ing = o.optJSONObject("ingresos")
            ing?.keys()?.forEach { k -> k.toIntOrNull()?.let { l.ingresos[it] = ing.getLong(k) } }
            val vals = o.optJSONObject("validaciones")
            vals?.keys()?.forEach { k -> k.toIntOrNull()?.let { l.validaciones[it] = vals.getLong(k) } }
            l.reindexar()
            Datos(l, jsonAInfo(o.optJSONObject("info")), jsonAInfo(o.optJSONObject("infoVal")))
        } catch (e: Exception) {
            Datos(null, emptyMap(), emptyMap())
        }
    }
}

/** Datos del operador, textos del ticket y servidor. */
class Ajustes(ctx: Context) {
    private val p = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    private fun texto(clave: String, def: String) = p.getString(clave, null) ?: def
    private fun guardar(clave: String, v: String) = p.edit().putString(clave, v.trim()).apply()
    private fun guardar(clave: String, v: Boolean) = p.edit().putBoolean(clave, v).apply()

    var operador: String
        get() = texto("operador", "").ifBlank { "Operador" }
        set(v) = guardar("operador", v)

    var negocio: String
        get() = texto("negocio", "PARQUEADERO")
        set(v) = guardar("negocio", v)

    var bienvenida: String
        get() = texto("bienvenida", BIENVENIDA)
        set(v) = guardar("bienvenida", v)

    var mensajeVip: String
        get() = texto("vip", VIP)
        set(v) = guardar("vip", v)

    var autoImprimir: Boolean
        get() = p.getBoolean("autoImprimir", true)
        set(v) = guardar("autoImprimir", v)

    var imprimirQr: Boolean
        get() = p.getBoolean("imprimirQr", true)
        set(v) = guardar("imprimirQr", v)

    /** Usar el lector integrado del Sunmi para el QR (si no, la cámara). */
    var usarLectorSunmi: Boolean
        get() = p.getBoolean("lectorSunmi", true)
        set(v) = guardar("lectorSunmi", v)

    // ---- Servidor
    var urlEnvio: String
        get() = texto("urlEnvio", "")
        set(v) = guardar("urlEnvio", v)

    var urlReservas: String
        get() = texto("urlReservas", "")
        set(v) = guardar("urlReservas", v)

    var token: String
        get() = texto("token", "")
        set(v) = guardar("token", v)

    /** Cuántos registros se juntan para enviar un lote: 5, 10 o 20. */
    var loteEnvio: Int
        get() = p.getInt("lote", 5).let { if (it in Sincronizacion.LOTES) it else 5 }
        set(v) = p.edit().putInt("lote", if (v in Sincronizacion.LOTES) v else 5).apply()

    /** Cada cuántos minutos la app envía lo pendiente y trae reservas (mientras esté abierta, y solo con internet). */
    var intervaloMin: Int
        get() = p.getInt("intervaloMin", 2).let { if (it in Sincronizacion.INTERVALOS) it else 2 }
        set(v) = p.edit().putInt("intervaloMin", if (v in Sincronizacion.INTERVALOS) v else 2).apply()

    // ---- Cámara de placas (se cambian con los botones de la propia pantalla de la cámara)
    /** 0 = AUTOMÁTICO (detector de placa + zona fija), 1 = ZONA FIJA (solo lo que cae en el recuadro), 2 = TODA LA IMAGEN. */
    var metodoPlaca: Int
        get() = p.getInt("metodoPlaca", 0).coerceIn(0, 2)
        set(v) = p.edit().putInt("metodoPlaca", v.coerceIn(0, 2)).apply()

    /** Motor de lectura: 0 = IA con respaldo de ML Kit, 1 = solo IA, 2 = solo ML Kit (el de la versión 1.4). */
    var motorPlaca: Int
        get() = p.getInt("motorPlaca", 0).coerceIn(0, 2)
        set(v) = p.edit().putInt("motorPlaca", v.coerceIn(0, 2)).apply()

    /** Zoom de la cámara de placas (1, 1,5, 2 o 3 veces). */
    var zoomPlaca: Float
        get() = p.getFloat("zoomPlaca", 1f).coerceIn(1f, 4f)
        set(v) = p.edit().putFloat("zoomPlaca", v.coerceIn(1f, 4f)).apply()

    /** Enfoque: 0 = automático en la zona de lectura; otro valor = distancia fija en centímetros (70, 120, 200, 400). */
    var focoPlacaCm: Int
        get() = p.getInt("focoPlacaCm", 0).let { if (it == 0 || it in 30..1000) it else 0 }
        set(v) = p.edit().putInt("focoPlacaCm", v).apply()

    /** Vehículo en movimiento: 0 = normal, 1 = rápido (escena de acción y más cuadros por segundo), 2 = obturador rápido manual. */
    var movimientoPlaca: Int
        get() = p.getInt("movimientoPlaca", 1).coerceIn(0, 2)
        set(v) = p.edit().putInt("movimientoPlaca", v.coerceIn(0, 2)).apply()

    /** Analizar a 1920x1080 (más detalle para placas lejanas) en vez de 1280x720. */
    var altaResolucion: Boolean
        get() = p.getBoolean("altaResolucion", true)
        set(v) = guardar("altaResolucion", v)

    // ---- Operación en dos momentos (se cambia solo con el PIN de configuración)
    /** Qué hace la app al leer un QR: "VALIDAR" (solo da acceso en la vía) o "INGRESAR" (registra y da el ticket). */
    var accionQr: String
        get() = texto("accionQr", Operacion.Accion.INGRESAR.name)
        set(v) = guardar("accionQr", v)

    var accionPlaca: String
        get() = texto("accionPlaca", Operacion.Accion.INGRESAR.name)
        set(v) = guardar("accionPlaca", v)

    /** Al validar en la vía: imprimir un comprobante corto. */
    var imprimirAlValidar: Boolean
        get() = p.getBoolean("imprimirAlValidar", false)
        set(v) = guardar("imprimirAlValidar", v)

    /** Al validar en la vía: enviar el registro al servidor (así el equipo de adentro se entera). */
    var enviarAlValidar: Boolean
        get() = p.getBoolean("enviarAlValidar", true)
        set(v) = guardar("enviarAlValidar", v)

    /** Adentro: no dar ingreso a quien no pasó antes por la vía (posible colado). */
    var exigirValidacion: Boolean
        get() = p.getBoolean("exigirValidacion", false)
        set(v) = guardar("exigirValidacion", v)

    /** Enviar las alertas (sin reserva, repetidos, sin validar) al servidor. */
    var enviarAlertas: Boolean
        get() = p.getBoolean("enviarAlertas", true)
        set(v) = guardar("enviarAlertas", v)

    /** Minutos que puede esperar un vehículo validado en la vía sin recibir ticket adentro antes de marcarlo en rojo. */
    var minutosEspera: Int
        get() = p.getInt("minutosEspera", 30).coerceIn(5, 600)
        set(v) = p.edit().putInt("minutosEspera", v.coerceIn(5, 600)).apply()

    // ---- PIN de configuración
    private var pinSal: String
        get() = texto("pinSal", "")
        set(v) = guardar("pinSal", v)
    private var pinHuella: String
        get() = texto("pinHuella", "")
        set(v) = guardar("pinHuella", v)

    val tienePin: Boolean get() = pinHuella.isNotBlank()

    fun crearPin(pin: String) {
        val sal = Pin.nuevaSal()
        pinSal = sal
        pinHuella = Pin.huella(sal, pin)
    }

    fun pinCorrecto(pin: String): Boolean = Pin.coincide(pinSal, pinHuella, pin)

    fun borrarPin() {
        pinSal = ""
        pinHuella = ""
    }

    var autoReservas: Boolean
        get() = p.getBoolean("autoReservas", true)
        set(v) = guardar("autoReservas", v)

    /** Marca de tiempo que devolvió el servidor en la última descarga de reservas. */
    var ultimaSincReservas: String
        get() = texto("ultimaSinc", "")
        set(v) = guardar("ultimaSinc", v)

    // ---- Red entre equipos cercanos (Wi-Fi local, sin internet)
    /** Compartir en segundo plano lo que se lee con los otros equipos que estén en la misma red Wi-Fi. */
    var redActiva: Boolean
        get() = p.getBoolean("redActiva", true)
        set(v) = guardar("redActiva", v)

    /** Cómo se llama este equipo para los demás («VÍA 1», «INTERIOR 2»). Si está vacío se usa «Equipo ABCD». */
    var nombreEquipo: String
        get() = texto("nombreEquipo", "").ifBlank { "Equipo " + idDispositivo.filter { it.isLetterOrDigit() }.takeLast(4).uppercase() }
        set(v) = guardar("nombreEquipo", v)

    /** Clave de la red de equipos. Debe ser igual en todos; si se deja vacía se usa [CLAVE_RED]. */
    var redClave: String
        get() = texto("redClave", "")
        set(v) = guardar("redClave", v)

    val redClaveEfectiva: String get() = redClave.ifBlank { CLAVE_RED }

    /** IP de otros equipos escritas a mano (solo si no se encuentran solos), separadas por coma o espacio. */
    var redIps: String
        get() = texto("redIps", "")
        set(v) = guardar("redIps", v)

    /** Hora desde la que se aceptan avisos de otros equipos (se mueve al «Borrar ingresos»). */
    var redCorte: Long
        get() = p.getLong("redCorte", 0L)
        set(v) = p.edit().putLong("redCorte", v).apply()

    /** Ya se pidió el permiso de notificaciones (Android 13+) para el aviso del servicio. */
    var pidioNotificaciones: Boolean
        get() = p.getBoolean("pidioNotif", false)
        set(v) = guardar("pidioNotif", v)

    val idDispositivo: String
        get() {
            val actual = p.getString("idDispositivo", null)
            if (actual != null) return actual
            val nuevo = UUID.randomUUID().toString()
            p.edit().putString("idDispositivo", nuevo).apply()
            return nuevo
        }

    /** Reemplaza {nombre} y {evento} en el mensaje. */
    fun textoBienvenida(nombre: String, evento: String): String =
        bienvenida.replace("{nombre}", nombre)
            .replace("{evento}", evento.ifBlank { "el evento" })
            .replace(Regex(",\\s*!"), "!")
            .replace(Regex("\\s+"), " ")
            .trim()

    companion object {
        /** Clave que usan todos los equipos mientras no se escriba otra en Ajustes. */
        const val CLAVE_RED = "reservasqr-red"
        const val BIENVENIDA ="¡Bienvenido(a), {nombre}! Gracias por su reserva para {evento}. Conserve este ticket para la salida."
        const val VIP = "Cliente VIP: acceso preferencial. ¡Gracias por acompañarnos!"
    }
}
