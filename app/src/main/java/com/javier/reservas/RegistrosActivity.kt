package com.javier.reservas

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registros de la jornada con su foto: cada validación en la vía y cada ingreso, de este equipo y de los demás equipos
 * (las fotos viajan por el Wi-Fi), del más reciente al más viejo. Un toque en la foto la muestra completa.
 * Hecha con código (sin archivo de diseño), igual que las demás pantallas de la app.
 */
class RegistrosActivity : BaseActivity() {

    private enum class Filtro(val titulo: String) {
        TODOS("Todos"), CON_FOTO("Con foto"), INGRESOS("Ingresos"), VIA("Vía")
    }

    private var filtro = Filtro.TODOS
    private var densidad = 1f
    private val hora = SimpleDateFormat("dd/MM hh:mm a", Locale("es", "CO"))

    private lateinit var txtResumen: TextView
    private lateinit var txtVacio: TextView
    private lateinit var lista: RecyclerView
    private val chips = LinkedHashMap<Filtro, TextView>()
    private val adaptador = Adaptador()

    private val alCambiar: () -> Unit = { pintar() }

    private fun dp(v: Int) = (v * densidad).toInt()

    private fun fondo(color: Int, radio: Int = 12, borde: Int = 0): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radio).toFloat()
        setColor(color)
        if (borde != 0) setStroke(dp(1), borde)
    }

    private fun texto(t: String, tam: Float = 14f, color: Int = Color.WHITE, negrita: Boolean = false): TextView =
        TextView(this).apply {
            text = t
            textSize = tam
            setTextColor(color)
            if (negrita) typeface = Typeface.DEFAULT_BOLD
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Operacion.iniciar(this)
        densidad = resources.displayMetrics.density

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }

        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        barra.addView(texto("←", 26f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            minimumWidth = dp(48)      // botón de volver con el mismo tamaño mínimo que los demás
            minimumHeight = dp(48)
            setOnClickListener { finish() }
        })
        barra.addView(texto("REGISTROS", 18f, Color.WHITE, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        raiz.addView(barra)

        txtResumen = texto("", 13f, Color.rgb(160, 160, 160)).apply { setPadding(dp(4), dp(2), 0, dp(8)) }
        raiz.addView(txtResumen)

        val filtros = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (f in Filtro.values()) {
            val chip = texto(f.titulo, 14f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                minimumHeight = dp(40)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener {
                    filtro = f
                    pintar()
                }
            }
            chips[f] = chip
            filtros.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            })
        }
        raiz.addView(filtros)

        txtVacio = texto("", 14f, Color.rgb(150, 150, 150)).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(40), dp(16), dp(16))
        }
        raiz.addView(txtVacio)

        lista = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@RegistrosActivity)
            adapter = adaptador
            clipToPadding = false
            setPadding(0, dp(10), 0, dp(10))
        }
        raiz.addView(lista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(raiz)
        pintar()
    }

    override fun onResume() {
        super.onResume()
        if (Bloqueo.requiereClave) {   // la app está bloqueada: primero se desbloquea en la pantalla principal
            finish()
            return
        }
        Operacion.escuchar(alCambiar)
        RedLocal.fotosCambio()   // por si falta repartir alguna foto
        pintar()
    }

    override fun onPause() {
        super.onPause()
        Operacion.dejarDeEscuchar(alCambiar)
    }

    private fun pintar() {
        val todos = Operacion.registros()
        val conFoto = todos.count { it.foto != null }
        val ver = when (filtro) {
            Filtro.TODOS -> todos
            Filtro.CON_FOTO -> todos.filter { it.foto != null }
            Filtro.INGRESOS -> todos.filter { it.tipo == FotosRed.INGRESO }
            Filtro.VIA -> todos.filter { it.tipo == FotosRed.VALIDACION }
        }
        txtResumen.text = "${todos.size} registros · $conFoto con foto" +
            (if (Operacion.ajustes.redActiva) " · las fotos de los demás equipos llegan por el Wi-Fi" else "")
        for ((f, chip) in chips) {
            val activo = f == filtro
            chip.background = fondo(if (activo) Color.rgb(225, 6, 0) else Color.rgb(32, 32, 32), 18, if (activo) 0 else Color.rgb(70, 70, 70))
        }
        adaptador.actualizar(ver)
        lista.visibility = if (ver.isEmpty()) View.GONE else View.VISIBLE
        txtVacio.visibility = if (ver.isEmpty()) View.VISIBLE else View.GONE
        txtVacio.text = when {
            Operacion.listado == null -> "Todavía no hay un listado de reservas cargado."
            todos.isEmpty() -> "Todavía no hay registros. Aquí aparecerán, con su foto, las validaciones y los ingresos de todos los equipos."
            else -> "No hay registros con ese filtro."
        }
    }

    // ---------------------------------------------------------------- lista

    private inner class Adaptador : RecyclerView.Adapter<Adaptador.Fila>() {
        private var datos: List<Operacion.Registro> = emptyList()

        @Suppress("NotifyDataSetChanged")
        fun actualizar(nuevos: List<Operacion.Registro>) {
            datos = nuevos
            notifyDataSetChanged()
        }

        inner class Fila(
            v: View,
            val foto: ImageView,
            val sinFoto: TextView,
            val placa: TextView,
            val nombre: TextView,
            val estado: TextView,
            val detalle: TextView
        ) : RecyclerView.ViewHolder(v)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Fila {
            val tarjeta = LinearLayout(this@RegistrosActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = fondo(Color.rgb(14, 14, 14), 14, Color.rgb(38, 38, 38))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }
            val marco = android.widget.FrameLayout(this@RegistrosActivity)
            val foto = ImageView(this@RegistrosActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = fondo(Color.rgb(26, 26, 26), 10, Color.rgb(51, 51, 51))
                clipToOutline = true
            }
            val sinFoto = texto("Sin foto", 11f, Color.rgb(120, 120, 120)).apply { gravity = Gravity.CENTER }
            marco.addView(foto, android.widget.FrameLayout.LayoutParams(dp(96), dp(96)))
            marco.addView(sinFoto, android.widget.FrameLayout.LayoutParams(dp(96), dp(96)))
            tarjeta.addView(marco, LinearLayout.LayoutParams(dp(96), dp(96)).apply { marginEnd = dp(12) })

            val col = LinearLayout(this@RegistrosActivity).apply { orientation = LinearLayout.VERTICAL }
            val placa = texto("", 20f, Color.BLACK, true).apply {
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setBackgroundResource(R.drawable.placa)
                setPadding(dp(10), dp(2), dp(10), dp(2))
            }
            val nombre = texto("", 15f, Color.WHITE, true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, 0)
            }
            val estado = texto("", 13f, Color.WHITE, true).apply { setPadding(0, dp(2), 0, 0) }
            val detalle = texto("", 12f, Color.rgb(160, 160, 160))
            col.addView(placa, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            col.addView(nombre)
            col.addView(estado)
            col.addView(detalle)
            tarjeta.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return Fila(tarjeta, foto, sinFoto, placa, nombre, estado, detalle)
        }

        override fun getItemCount(): Int = datos.size

        override fun onBindViewHolder(h: Fila, position: Int) {
            val r = datos[position]
            h.placa.text = r.placa.ifBlank { "SIN PLACA" }
            h.nombre.text = r.nombre
            val ingreso = r.tipo == FotosRed.INGRESO
            h.estado.text = (if (ingreso) "✓ Ingresó · " else "Validado en la vía · ") + hora.format(Date(r.hora))
            h.estado.setTextColor(if (ingreso) Color.WHITE else Color.rgb(255, 167, 38))
            h.detalle.text = "Registró: " + r.quien + (if (r.placaLeida.isNotBlank()) " · leyó " + r.placaLeida else "")

            Fotos.cargar(h.foto, r.foto, 300)
            h.foto.visibility = if (r.foto != null) View.VISIBLE else View.GONE
            h.sinFoto.visibility = if (r.foto == null) View.VISIBLE else View.GONE
            val abrir = View.OnClickListener {
                r.foto?.let { f -> Fotos.ver(this@RegistrosActivity, f, (r.placa.ifBlank { r.nombre }) + " · " + hora.format(Date(r.hora)) + " · " + r.quien) }
            }
            h.foto.setOnClickListener(abrir)
            h.itemView.setOnClickListener(abrir)
        }
    }
}
