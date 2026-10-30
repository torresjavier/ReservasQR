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
        val v = l.validaciones[f]
        when {
            t != null -> {
                h.hora.text = "✓ " + hora.format(Date(t))
                h.hora.setTextColor(ctx.getColor(R.color.blanco))
            }
            v != null -> {
                // Validado en la vía y todavía sin ticket adentro; en rojo si lleva demasiado esperando
                val min = Operacion.minutosEsperando(f)
                val tarde = min >= Operacion.ajustes.minutosEspera
                h.hora.text = "VALIDADO\n" + (if (tarde) "hace $min min" else hora.format(Date(v)))
                h.hora.setTextColor(ctx.getColor(if (tarde) R.color.rojo else R.color.blanco))
            }
            else -> {
                h.hora.text = "PENDIENTE"
                h.hora.setTextColor(ctx.getColor(R.color.rojo))
            }
        }
        // La foto del registro (la del ingreso, o la de la vía si no hay): miniatura; un toque la agranda
        val archivoFoto = Operacion.foto(f)
        h.foto.visibility = if (archivoFoto != null) View.VISIBLE else View.GONE
        Fotos.cargar(h.foto, archivoFoto, 200)
        if (archivoFoto != null) {
            h.foto.setOnClickListener { Fotos.ver(ctx, archivoFoto, l.valor(f, Listado.Campo.PLACA).uppercase().ifBlank { l.titulo(f) }) }
        } else {
            h.foto.setOnClickListener(null)
        }

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
