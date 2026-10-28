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
        const val BIENVENIDA = "¡Bienvenido(a), {nombre}! Gracias por su reserva para {evento}. Conserve este ticket para la salida."
        const val VIP = "Cliente VIP: acceso preferencial. ¡Gracias por acompañarnos!"
    }
}
