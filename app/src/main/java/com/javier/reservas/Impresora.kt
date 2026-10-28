package com.javier.reservas

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.print.PrintHelper
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.sunmi.peripheral.printer.InnerPrinterCallback
import com.sunmi.peripheral.printer.InnerPrinterManager
import com.sunmi.peripheral.printer.SunmiPrinterService
import java.util.concurrent.Executors

/** Una línea del ticket; se usa igual para la Sunmi y para la impresión de Android. */
sealed class Linea {
    data class Texto(val texto: String, val tam: Float = 24f, val negrita: Boolean = false, val centro: Boolean = false) : Linea()
    data class Qr(val dato: String) : Linea()
    data class Espacio(val lineas: Int = 1) : Linea()
}

object Ticket {
    private const val SEP = "--------------------------------"

    fun lineas(
        negocio: String,
        titulo: String,
        nombre: String,
        codigo: String,
        placa: String,
        evento: String,
        tipo: String,
        vip: Boolean,
        fechaHora: String,
        mensaje: String,
        mensajeVip: String,
        qr: String?,
        reimpresion: Boolean,
        operador: String
    ): List<Linea> {
        val l = mutableListOf<Linea>()
        if (negocio.isNotBlank()) l += Linea.Texto(negocio.uppercase(), 28f, negrita = true, centro = true)
        if (reimpresion) l += Linea.Texto("(REIMPRESIÓN)", 22f, centro = true)
        l += Linea.Texto(SEP, 24f, centro = true)
        l += Linea.Texto(titulo, 34f, negrita = true, centro = true)
        if (vip) l += Linea.Texto("*** VIP ***", 38f, negrita = true, centro = true)
        l += Linea.Texto(SEP, 24f, centro = true)
        if (placa.isNotBlank()) l += Linea.Texto("PLACA: ${placa.uppercase()}", 34f, negrita = true, centro = true)
        if (nombre.isNotBlank()) l += Linea.Texto("Nombre: $nombre", 26f)
        if (codigo.isNotBlank()) l += Linea.Texto("Reserva: $codigo", 26f)
        if (evento.isNotBlank()) l += Linea.Texto("Evento: $evento", 26f)
        if (tipo.isNotBlank()) l += Linea.Texto("Tipo: ${tipo.uppercase()}", 26f, negrita = vip)
        l += Linea.Texto("Ingreso: $fechaHora", 26f)
        if (operador.isNotBlank()) l += Linea.Texto("Atendió: $operador", 22f)
        l += Linea.Texto(SEP, 24f, centro = true)
        if (mensaje.isNotBlank()) l += Linea.Texto(mensaje, 28f, negrita = true, centro = true)
        if (vip && mensajeVip.isNotBlank()) {
            l += Linea.Espacio()
            l += Linea.Texto(mensajeVip, 26f, centro = true)
        }
        if (!qr.isNullOrBlank()) {
            l += Linea.Espacio()
            l += Linea.Qr(qr)
            l += Linea.Texto(qr, 22f, centro = true)
        }
        return l
    }
}

/** Impresora interna de los equipos Sunmi; si no hay, usa la impresión de Android. */
object Impresora {

    @Volatile private var servicio: SunmiPrinterService? = null
    var alCambiar: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val hilo = Executors.newSingleThreadExecutor()
    private var conectando = false

    val esSunmi: Boolean get() = servicio != null

    private val callback = object : InnerPrinterCallback() {
        override fun onConnected(service: SunmiPrinterService?) {
            servicio = service
            conectando = false
            main.post { alCambiar?.invoke() }
        }

        override fun onDisconnected() {
            servicio = null
            conectando = false
            main.post { alCambiar?.invoke() }
        }
    }

    fun conectar(ctx: Context) {
        if (servicio != null || conectando) return
        conectando = true
        try {
            val ok = InnerPrinterManager.getInstance().bindService(ctx.applicationContext, callback)
            if (!ok) conectando = false
        } catch (t: Throwable) {
            conectando = false
        }
    }

    /** Imprime y avisa el resultado: null = bien, texto = problema. */
    fun imprimir(activity: Activity, lineas: List<Linea>, alTerminar: (String?) -> Unit) {
        val s = servicio
        if (s == null) {
            imprimirAndroid(activity, lineas, alTerminar)
            return
        }
        hilo.execute {
            val error = imprimirSunmi(s, lineas)
            main.post { alTerminar(error) }
        }
    }

    private fun imprimirSunmi(s: SunmiPrinterService, lineas: List<Linea>): String? {
        try {
            val estado = try { s.updatePrinterState() } catch (e: Exception) { 1 }
            when (estado) {
                3 -> return "La impresora presenta una falla."
                4 -> return "La impresora no tiene papel."
                5 -> return "La impresora está sobrecalentada. Espere un momento."
                6 -> return "La tapa de la impresora está abierta."
                505 -> return "No se detecta la impresora."
            }
            s.printerInit(null)
            for (l in lineas) {
                when (l) {
                    is Linea.Texto -> {
                        s.setAlignment(if (l.centro) 1 else 0, null)
                        s.sendRAWData(byteArrayOf(0x1B, 0x45, (if (l.negrita) 1 else 0).toByte()), null)
                        s.printTextWithFont(l.texto + "\n", "", l.tam, null)
                    }
                    is Linea.Qr -> {
                        s.setAlignment(1, null)
                        val modulo = if (l.dato.length > 60) 5 else if (l.dato.length > 30) 6 else 8
                        s.printQRCode(l.dato, modulo, 1, null)
                        s.lineWrap(1, null)
                    }
                    is Linea.Espacio -> s.lineWrap(l.lineas, null)
                }
            }
            s.sendRAWData(byteArrayOf(0x1B, 0x45, 0), null)
            s.setAlignment(0, null)
            s.lineWrap(4, null)
            return null
        } catch (e: Exception) {
            return "Error al imprimir: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun imprimirAndroid(activity: Activity, lineas: List<Linea>, alTerminar: (String?) -> Unit) {
        if (!PrintHelper.systemSupportsPrint()) {
            alTerminar("Este equipo no tiene impresora Sunmi ni impresión de Android.")
            return
        }
        try {
            val bmp = dibujar(lineas)
            PrintHelper(activity).apply {
                scaleMode = PrintHelper.SCALE_MODE_FIT
                colorMode = PrintHelper.COLOR_MODE_MONOCHROME
            }.printBitmap("Ticket de reserva", bmp)
            alTerminar(null)
        } catch (e: Exception) {
            alTerminar("No se pudo imprimir: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Dibuja el ticket como imagen (ancho de rollo de 58 mm a 1,5x). */
    fun dibujar(lineas: List<Linea>): Bitmap {
        val ancho = 576
        val margen = 16
        val util = ancho - margen * 2
        val escala = 1.5f
        val bloques = mutableListOf<Pair<Int, (Canvas, Int) -> Unit>>()

        for (l in lineas) {
            when (l) {
                is Linea.Texto -> {
                    val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = l.tam * escala
                        typeface = if (l.negrita) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    }
                    val sl = StaticLayout.Builder.obtain(l.texto, 0, l.texto.length, p, util)
                        .setAlignment(if (l.centro) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
                        .build()
                    bloques += sl.height to { c: Canvas, y: Int ->
                        c.save(); c.translate(margen.toFloat(), y.toFloat()); sl.draw(c); c.restore()
                    }
                }
                is Linea.Qr -> {
                    val lado = 300
                    val qr = try { BarcodeEncoder().encodeBitmap(l.dato, BarcodeFormat.QR_CODE, lado, lado) } catch (e: Exception) { null }
                    if (qr != null) bloques += (lado + 8) to { c: Canvas, y: Int ->
                        c.drawBitmap(qr, ((ancho - lado) / 2).toFloat(), y.toFloat(), null)
                    }
                }
                is Linea.Espacio -> bloques += (20 * l.lineas) to { _: Canvas, _: Int -> }
            }
        }
        val alto = bloques.sumOf { it.first } + margen * 2 + 40
        val bmp = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        var y = margen
        for ((h, dibujo) in bloques) {
            dibujo(c, y); y += h
        }
        return bmp
    }
}
