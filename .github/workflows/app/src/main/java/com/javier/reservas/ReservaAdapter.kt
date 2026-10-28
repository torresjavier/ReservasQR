package com.javier.reservas

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReservaAdapter(
    private val listado: () -> Listado?,
    private val alTocar: (Int) -> Unit
) : RecyclerView.Adapter<ReservaAdapter.VH>() {

    private var filas: List<Int> = emptyList()
    private val hora = SimpleDateFormat("hh:mm a", Locale("es", "CO"))

    @SuppressLint("NotifyDataSetChanged")
    fun actualizar(nuevas: List<Int>) {
        filas = nuevas
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val placa: TextView = v.findViewById(R.id.itPlaca)
        val vip: TextView = v.findViewById(R.id.itVip)
        val hora: TextView = v.findViewById(R.id.itHora)
        val nombre: TextView = v.findViewById(R.id.itNombre)
        val detalle: TextView = v.findViewById(R.id.itDetalle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_reserva, parent, false))

    override fun getItemCount(): Int = filas.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val l = listado() ?: return
        val f = filas[position]
        val placa = l.valor(f, Listado.Campo.PLACA)
        h.placa.text = placa.uppercase().ifBlank { "SIN PLACA" }
        h.vip.visibility = if (l.esVip(f)) View.VISIBLE else View.GONE

        val t = l.ingresos[f]
        if (t != null) {
            h.hora.text = "● " + hora.format(Date(t))
            h.hora.setTextColor(h.itemView.context.getColor(R.color.verde))
        } else {
            h.hora.text = "Pendiente"
            h.hora.setTextColor(h.itemView.context.getColor(R.color.texto_suave))
        }

        val nombre = l.valor(f, Listado.Campo.NOMBRE)
        h.nombre.text = nombre.ifBlank { l.titulo(f) }
        val partes = listOf(
            l.valor(f, Listado.Campo.EVENTO),
            l.valor(f, Listado.Campo.CODIGO).let { if (it.isNotBlank()) "Reserva $it" else "" },
            l.valor(f, Listado.Campo.TIPO).let { if (it.isNotBlank() && !l.esVip(f)) it.uppercase() else "" }
        ).filter { it.isNotBlank() }
        h.detalle.text = partes.joinToString("  ·  ")
        h.detalle.visibility = if (partes.isEmpty()) View.GONE else View.VISIBLE
        h.itemView.setOnClickListener { alTocar(f) }
    }
}
