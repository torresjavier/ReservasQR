package com.javier.reservas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Registro en el equipo de las alertas de la jornada (intentos de colarse, QR repetidos, sin reserva…).
 * También se envían al servidor como eventos de tipo ALERTA; esto es solo para que el operador las vea.
 */
object Alertas {

    const val SIN_RESERVA_VIA = "SIN_RESERVA_VIA"
    const val SIN_RESERVA_INTERIOR = "SIN_RESERVA_INTERIOR"
    const val REPETIDA_EN_VIA = "REPETIDA_EN_VIA"
    const val INGRESO_SIN_VALIDAR = "INGRESO_SIN_VALIDAR"
    const val BLOQUEADO_SIN_VALIDAR = "BLOQUEADO_SIN_VALIDAR"
    const val TIPO_NO_COINCIDE = "TIPO_NO_COINCIDE"
    const val VALIDADO_SIN_INGRESAR = "VALIDADO_SIN_INGRESAR"

    class Item(val t: Long, val motivo: String, val placa: String, val nombre: String, val detalle: String)

    private const val MAX = 300
    private const val ARCHIVO = "alertas.json"
    private val lista = ArrayList<Item>()
    private var cargado = false

    fun titulo(motivo: String): String = when (motivo) {
        SIN_RESERVA_VIA -> "Sin reserva (en la vía)"
        SIN_RESERVA_INTERIOR -> "Vehículo adentro sin reserva"
        REPETIDA_EN_VIA -> "QR o placa repetida en la vía"
        INGRESO_SIN_VALIDAR -> "Ingresó sin pasar por la vía"
        BLOQUEADO_SIN_VALIDAR -> "Bloqueado: no pasó por la vía"
        TIPO_NO_COINCIDE -> "Tipo de vehículo no coincide"
        VALIDADO_SIN_INGRESAR -> "Validado hace rato y sin ingresar"
        else -> motivo
    }

    @Synchronized private fun cargar(ctx: Context) {
        if (cargado) return
        cargado = true
        try {
            val f = File(ctx.applicationContext.filesDir, ARCHIVO)
            if (!f.exists()) return
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                lista.add(Item(o.optLong("t"), o.optString("m"), o.optString("p"), o.optString("n"), o.optString("d")))
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized fun agregar(ctx: Context, motivo: String, placa: String, nombre: String, detalle: String) {
        cargar(ctx)
        lista.add(0, Item(System.currentTimeMillis(), motivo, placa, nombre, detalle))
        while (lista.size > MAX) lista.removeAt(lista.size - 1)
        try {
            val a = JSONArray()
            lista.forEach { a.put(JSONObject().put("t", it.t).put("m", it.motivo).put("p", it.placa).put("n", it.nombre).put("d", it.detalle)) }
            File(ctx.applicationContext.filesDir, ARCHIVO).writeText(a.toString())
        } catch (_: Exception) {
        }
    }

    @Synchronized fun todas(ctx: Context): List<Item> {
        cargar(ctx)
        return ArrayList(lista)
    }

    @Synchronized fun cantidad(ctx: Context): Int {
        cargar(ctx)
        return lista.size
    }

    @Synchronized fun borrar(ctx: Context) {
        lista.clear()
        cargado = true
        File(ctx.applicationContext.filesDir, ARCHIVO).delete()
    }
}
