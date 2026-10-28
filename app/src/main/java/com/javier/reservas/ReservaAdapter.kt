package com.javier.reservas

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReservaAdapter(
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
        val foto: ImageView = v.findViewById(R.id.itFoto)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_reserva, parent, false))

    override fun getItemCount(): Int = filas.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val l = Operacion.listado ?: return
        val f = filas[position]
        if (f >= l.filas.size) return
        val ctx = h.itemView.context
        h.placa.text = l.valor(f, Listado.Campo.PLACA).uppercase().ifBlank { "SIN PLACA" }
        h.vip.visibility = if (l.esVip(f)) View.VISIBLE else View.GONE

        val t = l.ingresos[f]
        if (t != null) {
            h.hora.text = "✓ " + hora.format(Date(t))
            h.hora.setTextColor(ctx.getColor(R.color.blanco))
        } else {
            h.hora.text = "PENDIENTE"
            h.hora.setTextColor(ctx.getColor(R.color.rojo))
        }
        h.foto.visibility = if (Operacion.info[f]?.foto != null) View.VISIBLE else View.GONE

        h.nombre.text = l.valor(f, Listado.Campo.NOMBRE).ifBlank { l.titulo(f) }
        val partes = listOf(
            l.valor(f, Listado.Campo.EVENTO),
            l.valor(f, Listado.Campo.CODIGO).let { if (it.isNotBlank()) "Reserva $it" else "" }
        ).filter { it.isNotBlank() }
        h.detalle.text = partes.joinToString("  ·  ")
        h.detalle.visibility = if (partes.isEmpty()) View.GONE else View.VISIBLE
        h.itemView.setOnClickListener { alTocar(f) }
    }
}
