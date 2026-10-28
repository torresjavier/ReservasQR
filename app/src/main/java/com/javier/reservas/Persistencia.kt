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

    fun guardar(archivo: File, l: Listado?, info: Map<Int, InfoIngreso>) {
        if (l == null) {
            archivo.delete(); return
        }
        val o = JSONObject()
        o.put("v", 3)
        o.put("nombre", l.nombreArchivo)
        o.put("encabezados", JSONArray(l.encabezados))
        o.put("filas", JSONArray().apply { l.filas.forEach { put(JSONArray(it)) } })
        o.put("columnas", JSONArray().apply { l.columnas.forEach { put(it) } })
        o.put("ingresos", JSONObject().apply { l.ingresos.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("info", JSONObject().apply {
            info.forEach { (k, v) ->
                put(k.toString(), JSONObject().apply {
                    put("id", v.id)
                    put("origen", v.origen)
                    put("placaLeida", v.placaLeida)
                    if (v.foto != null) put("foto", v.foto)
                })
            }
        })
        val texto = o.toString()
        val tmp = File(archivo.path + ".tmp")
        tmp.writeText(texto)
        if (!tmp.renameTo(archivo)) {
            archivo.writeText(texto)
            tmp.delete()
        }
    }

    fun cargar(archivo: File): Pair<Listado?, Map<Int, InfoIngreso>> {
        if (!archivo.exists()) return null to emptyMap()
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
            l.reindexar()

            val info = HashMap<Int, InfoIngreso>()
            val inf = o.optJSONObject("info")
            inf?.keys()?.forEach { k ->
                val fila = k.toIntOrNull() ?: return@forEach
                val x = inf.getJSONObject(k)
                info[fila] = InfoIngreso(
                    x.optString("id", UUID.randomUUID().toString()),
                    x.optString("origen", "QR"),
                    x.optString("placaLeida", ""),
                    if (x.has("foto")) x.optString("foto") else null
                )
            }
            l to info
        } catch (e: Exception) {
            null to emptyMap()
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

    /** Cada cuántos registros se envía un lote (1 a 10). */
    var loteEnvio: Int
        get() = p.getInt("lote", 5).coerceIn(1, Sincronizacion.MAX_PENDIENTES)
        set(v) = p.edit().putInt("lote", v.coerceIn(1, Sincronizacion.MAX_PENDIENTES)).apply()

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
