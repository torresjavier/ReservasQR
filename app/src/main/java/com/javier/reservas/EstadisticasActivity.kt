package com.javier.reservas

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.HorizontalBarChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Estadísticas de la jornada con gráficas (librería MPAndroidChart): cómo van las reservas, a qué hora se valida y se
 * ingresa, cuánto se espera, carros y motos, qué equipo registra más y qué alertas hubo. Se actualiza sola cuando llega
 * algo nuevo (de este equipo o de otros). Los números salen de [Estadisticas]; aquí solo se dibujan.
 * Hecha con código (sin archivo de diseño), igual que las demás pantallas de la app.
 */
class EstadisticasActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    private val horaFmt = SimpleDateFormat("hh:mm:ss a", Locale("es", "CO"))
    private var densidad = 1f

    private lateinit var txtActualizado: TextView
    private lateinit var txtVacio: TextView
    private lateinit var contenido: LinearLayout

    // Las fichas de números de arriba
    private lateinit var fReservas: TextView
    private lateinit var fIngresadas: TextView
    private lateinit var fPendientes: TextView
    private lateinit var fEsperaMedia: TextView
    private lateinit var fEsperaMaxima: TextView
    private lateinit var fUltimaHora: TextView

    private lateinit var graficaEstado: PieChart
    private lateinit var graficaHoras: BarChart
    private lateinit var graficaVehiculos: PieChart
    private lateinit var graficaClientes: PieChart
    private lateinit var graficaEquipos: HorizontalBarChart
    private lateinit var graficaEventos: HorizontalBarChart
    private lateinit var tarjetaEventos: LinearLayout
    private lateinit var graficaAlertas: HorizontalBarChart
    private lateinit var tarjetaAlertas: LinearLayout

    private var ultimoPintado = 0L
    private var pendiente = false

    private val alCambiar: () -> Unit = { pintarPronto() }

    /** Cada 10 segundos se rehace sola (por ejemplo, «ingresos de la última hora» cambia con el tiempo). */
    private val reloj = object : Runnable {
        override fun run() {
            pintar()
            main.postDelayed(this, 10_000)
        }
    }

    private companion object {
        val VERDE = Color.rgb(76, 175, 80)
        val NARANJA = Color.rgb(255, 167, 38)
        val GRIS = Color.rgb(110, 110, 110)
        val ROJO = Color.rgb(225, 6, 0)
        val AZUL = Color.rgb(66, 165, 245)
        val MORADO = Color.rgb(171, 71, 188)
    }

    private fun dp(v: Int) = (v * densidad).toInt()

    private fun fondo(color: Int, radio: Int = 14, borde: Int = 0): GradientDrawable = GradientDrawable().apply {
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

    /** Un número grande con su título abajo. */
    private fun ficha(titulo: String): Pair<LinearLayout, TextView> {
        val valor = texto("—", 22f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        val tarjeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(12), dp(6), dp(10))
            background = fondo(Color.rgb(14, 14, 14), 14, Color.rgb(38, 38, 38))
        }
        tarjeta.addView(valor)
        tarjeta.addView(texto(titulo, 11f, Color.rgb(160, 160, 160)).apply { gravity = Gravity.CENTER })
        return Pair(tarjeta, valor)
    }

    private fun filaDeFichas(vararg fichas: LinearLayout): LinearLayout {
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, f) in fichas.withIndex()) {
            fila.addView(f, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i < fichas.size - 1) marginEnd = dp(8)
            })
        }
        return fila
    }

    /** Una tarjeta con título que contiene una gráfica. */
    private fun tarjeta(titulo: String, grafica: View, alto: Int): LinearLayout {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(8))
            background = fondo(Color.rgb(14, 14, 14), 14, Color.rgb(38, 38, 38))
        }
        t.addView(texto(titulo, 14f, Color.WHITE, true))
        t.addView(grafica, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(alto)).apply { topMargin = dp(6) })
        return t
    }

    private fun conSeparacion(v: View): View {
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        }
        return v
    }

    // ---------------------------------------------------------------- cómo se ven las gráficas

    private fun tortaBase(g: PieChart) {
        g.getDescription().setEnabled(false)
        g.setDrawEntryLabels(false)
        g.setHoleColor(Color.TRANSPARENT)
        g.setHoleRadius(56f)
        g.setTransparentCircleRadius(60f)
        g.setTransparentCircleColor(Color.argb(40, 255, 255, 255))
        g.setCenterTextColor(Color.WHITE)
        g.setCenterTextSize(16f)
        g.setRotationEnabled(false)
        g.setNoDataText("Sin datos todavía")
        g.setNoDataTextColor(Color.rgb(150, 150, 150))
        g.setExtraOffsets(4f, 4f, 4f, 4f)
        g.getLegend().setTextColor(Color.WHITE)
        g.getLegend().setTextSize(12f)
        g.getLegend().setWordWrapEnabled(true)
    }

    private fun barrasBase(g: BarChart) {
        g.getDescription().setEnabled(false)
        g.setDrawGridBackground(false)
        g.setScaleEnabled(false)
        g.setPinchZoom(false)
        g.setDoubleTapToZoomEnabled(false)
        g.setNoDataText("Sin datos todavía")
        g.setNoDataTextColor(Color.rgb(150, 150, 150))
        g.getAxisRight().setEnabled(false)
        g.getAxisLeft().setTextColor(Color.rgb(190, 190, 190))
        g.getAxisLeft().setGridColor(Color.argb(60, 255, 255, 255))
        g.getAxisLeft().setDrawAxisLine(false)
        g.getAxisLeft().setAxisMinimum(0f)
        g.getAxisLeft().setGranularity(1f)
        g.getAxisLeft().setValueFormatter(enteroEje)
        g.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM)
        g.getXAxis().setTextColor(Color.rgb(220, 220, 220))
        g.getXAxis().setDrawGridLines(false)
        g.getXAxis().setAxisLineColor(Color.argb(90, 255, 255, 255))
        g.getXAxis().setGranularity(1f)
        g.getLegend().setTextColor(Color.WHITE)
        g.getLegend().setTextSize(12f)
        g.getLegend().setWordWrapEnabled(true)
    }

    /** Números enteros; el cero no se escribe (para no llenar la gráfica de ceros). */
    private val entero = object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String = if (value < 0.5f) "" else value.toInt().toString()
    }

    /** Para los ejes, donde el cero sí se escribe. */
    private val enteroEje = object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String = value.toInt().toString()
    }

    // ---------------------------------------------------------------- pantalla

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
            setPadding(dp(4), dp(4), dp(14), dp(4))
            setOnClickListener { finish() }
        })
        barra.addView(texto("ESTADÍSTICAS", 18f, Color.WHITE, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        raiz.addView(barra)

        txtActualizado = texto("", 12f, Color.rgb(150, 150, 150)).apply { setPadding(dp(4), dp(2), 0, dp(8)) }
        raiz.addView(txtActualizado)

        txtVacio = texto("", 14f, Color.rgb(150, 150, 150)).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(40), dp(16), dp(16))
            visibility = View.GONE
        }
        raiz.addView(txtVacio)

        contenido = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val (c1, v1) = ficha("RESERVAS"); fReservas = v1
        val (c2, v2) = ficha("INGRESARON"); fIngresadas = v2
        val (c3, v3) = ficha("PENDIENTES"); fPendientes = v3
        contenido.addView(filaDeFichas(c1, c2, c3))
        val (c4, v4) = ficha("ESPERA MEDIA\nvía → ingreso"); fEsperaMedia = v4
        val (c5, v5) = ficha("ESPERA MÁXIMA"); fEsperaMaxima = v5
        val (c6, v6) = ficha("INGRESOS\núltima hora"); fUltimaHora = v6
        contenido.addView(conSeparacion(filaDeFichas(c4, c5, c6)))

        graficaEstado = PieChart(this).also { tortaBase(it) }
        contenido.addView(conSeparacion(tarjeta("Estado de las reservas", graficaEstado, 250)))

        graficaHoras = BarChart(this).also { barrasBase(it) }
        contenido.addView(conSeparacion(tarjeta("Validaciones e ingresos por hora", graficaHoras, 260)))

        graficaVehiculos = PieChart(this).also { tortaBase(it) }
        contenido.addView(conSeparacion(tarjeta("Carros y motos (los que ya pasaron por la vía o ingresaron)", graficaVehiculos, 230)))

        graficaClientes = PieChart(this).also { tortaBase(it) }
        contenido.addView(conSeparacion(tarjeta("Clientes que ingresaron: VIP y normales", graficaClientes, 230)))

        graficaEquipos = HorizontalBarChart(this).also { barrasBase(it) }
        contenido.addView(conSeparacion(tarjeta("Registros por equipo", graficaEquipos, 200)))

        graficaEventos = HorizontalBarChart(this).also { barrasBase(it) }
        tarjetaEventos = tarjeta("Ingresos por evento", graficaEventos, 200)
        contenido.addView(conSeparacion(tarjetaEventos))

        graficaAlertas = HorizontalBarChart(this).also { barrasBase(it) }
        tarjetaAlertas = tarjeta("Alertas por motivo", graficaAlertas, 200)
        contenido.addView(conSeparacion(tarjetaAlertas))

        val scroll = ScrollView(this).apply { addView(contenido) }
        raiz.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

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
        pintar()
        main.removeCallbacks(reloj)
        main.postDelayed(reloj, 10_000)
    }

    override fun onPause() {
        super.onPause()
        Operacion.dejarDeEscuchar(alCambiar)
        main.removeCallbacks(reloj)
    }

    /** Se rehace como mucho una vez por segundo (si llegan muchos registros juntos). */
    private fun pintarPronto() {
        val t = System.currentTimeMillis()
        if (t - ultimoPintado >= 1_000) {
            pintar()
        } else if (!pendiente) {
            pendiente = true
            main.postDelayed({
                pendiente = false
                pintar()
            }, 1_000 - (t - ultimoPintado))
        }
    }

    private fun pintar() {
        ultimoPintado = System.currentTimeMillis()
        val r = Operacion.estadisticas(this)
        if (r == null || !r.hayDatos()) {
            contenido.visibility = View.GONE
            txtVacio.visibility = View.VISIBLE
            txtVacio.text = if (r == null) "Todavía no hay un listado de reservas cargado." else "El listado no tiene reservas."
            txtActualizado.text = ""
            return
        }
        txtVacio.visibility = View.GONE
        contenido.visibility = View.VISIBLE
        txtActualizado.text = "Actualizado " + horaFmt.format(Date()) + " · se actualiza solo" +
            (if (Operacion.ajustes.redActiva) " · incluye lo que registran los demás equipos" else "")

        fReservas.text = r.reservas.toString()
        fIngresadas.text = r.ingresadas.toString() + "\n" + Estadisticas.porcentaje(r.ingresadas, r.reservas) + "%"
        fPendientes.text = (r.pendientes + r.validadasSinIngresar).toString()
        fEsperaMedia.text = if (r.conEspera > 0) Estadisticas.duracion(r.esperaMediaMs) else "—"
        fEsperaMaxima.text = if (r.conEspera > 0) Estadisticas.duracion(r.esperaMaximaMs) else "—"
        fUltimaHora.text = r.ingresosUltimaHora.toString()

        pintarEstado(r)
        pintarHoras(r)
        pintarTorta(
            graficaVehiculos,
            listOf(Triple("Carros", r.carros, AZUL), Triple("Motos", r.motos, NARANJA), Triple("Sin placa", r.sinPlaca, GRIS)),
            (r.carros + r.motos + r.sinPlaca).let { if (it > 0) "$it\nvehículos" else "" }
        )
        pintarTorta(
            graficaClientes,
            listOf(Triple("VIP", r.vipIngresadas, MORADO), Triple("Normales", r.normalIngresadas, AZUL)),
            r.ingresadas.let { if (it > 0) "$it\ningresos" else "" }
        )
        pintarEquipos(r)
        pintarEventos(r)
        pintarAlertas(r)
    }

    // ---------------------------------------------------------------- cada gráfica

    private fun pintarTorta(g: PieChart, partes: List<Triple<String, Int, Int>>, centro: String) {
        val con = partes.filter { it.second > 0 }
        if (con.isEmpty()) {
            g.clear()
            g.invalidate()
            return
        }
        val conjunto = PieDataSet(con.map { PieEntry(it.second.toFloat(), it.first) }, "")
        conjunto.setColors(con.map { it.third })
        conjunto.setSliceSpace(2f)
        conjunto.setValueTextColor(Color.WHITE)
        conjunto.setValueTextSize(13f)
        conjunto.setValueTypeface(Typeface.DEFAULT_BOLD)
        val datos = PieData(conjunto)
        datos.setValueFormatter(enteroEje)
        g.setData(datos)
        g.setCenterText(centro)
        g.invalidate()
    }

    private fun pintarEstado(r: Estadisticas.Resultado) {
        pintarTorta(
            graficaEstado,
            listOf(
                Triple("Ingresaron", r.ingresadas, VERDE),
                Triple("Validadas en la vía, sin ingresar", r.validadasSinIngresar, NARANJA),
                Triple("Sin llegar", r.pendientes, GRIS)
            ),
            Estadisticas.porcentaje(r.ingresadas, r.reservas).toString() + "%\ningresó"
        )
    }

    /** Dos barras por hora: validadas en la vía (naranja) e ingresadas (verde). */
    private fun pintarHoras(r: Estadisticas.Resultado) {
        val g = graficaHoras
        if (r.totalRegistros == 0) {
            g.clear()
            g.invalidate()
            return
        }
        val rango = Estadisticas.rangoHoras(r)
        val desde = rango[0]
        val n = rango[1] - rango[0] + 1
        val validadas = ArrayList<BarEntry>()
        val ingresadas = ArrayList<BarEntry>()
        val etiquetas = ArrayList<String>()
        for (i in 0 until n) {
            validadas.add(BarEntry(i.toFloat(), r.validadasPorHora[desde + i].toFloat()))
            ingresadas.add(BarEntry(i.toFloat(), r.ingresadasPorHora[desde + i].toFloat()))
            etiquetas.add((desde + i).toString() + "h")
        }
        val a = BarDataSet(validadas, "Validadas en la vía")
        a.setColor(NARANJA)
        val b = BarDataSet(ingresadas, "Ingresaron")
        b.setColor(VERDE)
        for (s in listOf(a, b)) {
            s.setValueTextColor(Color.WHITE)
            s.setValueTextSize(9f)
            s.setValueFormatter(entero)
        }
        val datos = BarData(a, b)
        datos.setBarWidth(0.45f)
        g.setData(datos)
        g.getXAxis().setValueFormatter(IndexAxisValueFormatter(etiquetas))
        g.getXAxis().setCenterAxisLabels(true)
        g.getXAxis().setAxisMinimum(0f)
        g.getXAxis().setAxisMaximum(n.toFloat())
        g.getXAxis().setLabelCount(minOf(n, 12))
        g.groupBars(0f, 0.06f, 0.02f)   // (0,45 + 0,02) × 2 barras + 0,06 de aire = 1 por hora
        g.invalidate()
    }

    /** Barras horizontales de «nombre → cantidad»; la primera queda arriba. */
    private fun pintarBarras(g: HorizontalBarChart, filas: List<Pair<String, Int>>, color: Int, nombre: String) {
        if (filas.isEmpty()) {
            g.clear()
            g.invalidate()
            return
        }
        val abajoArriba = filas.reversed()
        val conjunto = BarDataSet(abajoArriba.mapIndexed { i, f -> BarEntry(i.toFloat(), f.second.toFloat()) }, nombre)
        conjunto.setColor(color)
        conjunto.setValueTextColor(Color.WHITE)
        conjunto.setValueTextSize(11f)
        conjunto.setValueFormatter(entero)
        val datos = BarData(conjunto)
        datos.setBarWidth(0.6f)
        g.setData(datos)
        g.getXAxis().setValueFormatter(IndexAxisValueFormatter(abajoArriba.map { it.first }))
        g.getXAxis().setLabelCount(abajoArriba.size)
        g.getLegend().setEnabled(false)
        alturaPara(g, filas.size)
        g.invalidate()
    }

    private fun alturaPara(g: View, filas: Int) {
        val alto = maxOf(150, 34 * filas + 50)
        val lp = g.layoutParams
        if (lp != null && lp.height != dp(alto)) {
            lp.height = dp(alto)
            g.layoutParams = lp
        }
    }

    private fun pintarEquipos(r: Estadisticas.Resultado) {
        pintarBarras(graficaEquipos, r.porEquipo.entries.take(10).map { Pair(it.key, it.value) }, AZUL, "Registros")
    }

    /** Por evento: lo que ya ingresó (verde) y lo que falta (gris), una sobre otra. Solo si hay más de un evento. */
    private fun pintarEventos(r: Estadisticas.Resultado) {
        if (r.porEvento.isEmpty()) {
            tarjetaEventos.visibility = View.GONE
            return
        }
        tarjetaEventos.visibility = View.VISIBLE
        val g = graficaEventos
        val eventos = r.porEvento.entries.take(10).toList().reversed()
        val conjunto = BarDataSet(
            eventos.mapIndexed { i, e -> BarEntry(i.toFloat(), floatArrayOf(e.value[1].toFloat(), (e.value[0] - e.value[1]).toFloat())) },
            ""
        )
        conjunto.setColors(listOf(VERDE, GRIS))
        conjunto.setStackLabels(arrayOf("Ingresaron", "Faltan"))
        conjunto.setValueTextColor(Color.WHITE)
        conjunto.setValueTextSize(10f)
        conjunto.setValueFormatter(entero)
        val datos = BarData(conjunto)
        datos.setBarWidth(0.6f)
        g.setData(datos)
        g.getXAxis().setValueFormatter(IndexAxisValueFormatter(eventos.map { it.key }))
        g.getXAxis().setLabelCount(eventos.size)
        g.getLegend().setEnabled(true)
        alturaPara(g, eventos.size)
        g.invalidate()
    }

    private fun pintarAlertas(r: Estadisticas.Resultado) {
        if (r.alertas.isEmpty()) {
            tarjetaAlertas.visibility = View.GONE
            return
        }
        tarjetaAlertas.visibility = View.VISIBLE
        pintarBarras(graficaAlertas, r.alertas.entries.map { Pair(Alertas.titulo(it.key), it.value) }, ROJO, "Alertas")
    }
}
