package com.javier.reservas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Guarda el listado y los ingresos en el teléfono para no perder nada si se cierra la app. */
object Persistencia {

    fun guardar(archivo: File, l: Listado?) {
        if (l == null) {
            archivo.delete(); return
        }
        val o = JSONObject()
        o.put("v", 2)
        o.put("nombre", l.nombreArchivo)
        o.put("encabezados", JSONArray(l.encabezados))
        o.put("filas", JSONArray().apply { l.filas.forEach { put(JSONArray(it)) } })
        o.put("columnas", JSONArray().apply { l.columnas.forEach { put(it) } })
        o.put("ingresos", JSONObject().apply { l.ingresos.forEach { (k, v) -> put(k.toString(), v) } })
        val tmp = File(archivo.path + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(archivo)) {
            archivo.writeText(o.toString())
            tmp.delete()
        }
    }

    fun cargar(archivo: File): Listado? {
        if (!archivo.exists()) return null
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
            l
        } catch (e: Exception) {
            null
        }
    }
}

/** Datos del operador y textos del ticket. */
class Ajustes(ctx: Context) {
    private val p = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    var operador: String
        get() = p.getString("operador", null)?.takeIf { it.isNotBlank() } ?: "Operador"
        set(v) { p.edit().putString("operador", v.trim()).apply() }

    var negocio: String
        get() = p.getString("negocio", null) ?: "PARQUEADERO"
        set(v) { p.edit().putString("negocio", v.trim()).apply() }

    var bienvenida: String
        get() = p.getString("bienvenida", null) ?: BIENVENIDA
        set(v) { p.edit().putString("bienvenida", v.trim()).apply() }

    var mensajeVip: String
        get() = p.getString("vip", null) ?: VIP
        set(v) { p.edit().putString("vip", v.trim()).apply() }

    var autoImprimir: Boolean
        get() = p.getBoolean("autoImprimir", true)
        set(v) { p.edit().putBoolean("autoImprimir", v).apply() }

    var imprimirQr: Boolean
        get() = p.getBoolean("imprimirQr", true)
        set(v) { p.edit().putBoolean("imprimirQr", v).apply() }

    /** Reemplaza {nombre} y {evento} en el mensaje. */
    fun textoBienvenida(nombre: String, evento: String): String =
        bienvenida.replace("{nombre}", nombre.ifBlank { "" })
            .replace("{evento}", evento.ifBlank { "el evento" })
            .replace(Regex(",\\s*!"), "!")
            .replace(Regex("\\s+"), " ")
            .trim()

    companion object {
        const val BIENVENIDA = "¡Bienvenido(a), {nombre}! Gracias por su reserva para {evento}. Conserve este ticket para la salida."
        const val VIP = "Cliente VIP: acceso preferencial. ¡Gracias por acompañarnos!"
    }
}
