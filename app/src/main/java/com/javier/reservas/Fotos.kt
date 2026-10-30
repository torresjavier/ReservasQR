package com.javier.reservas

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * Ayudas para las fotos de los registros: miniaturas rápidas para las listas (se cargan aparte para no trabar el
 * desplazamiento), visor de la foto completa y la versión liviana que viaja por el Wi-Fi.
 */
object Fotos {

    private val main = Handler(Looper.getMainLooper())
    private val hilo = Executors.newFixedThreadPool(2) { r -> Thread(r, "fotos-miniaturas").apply { isDaemon = true } }

    /** Miniaturas ya cargadas (hasta ~8 MB); la clave incluye el tamaño del archivo para que una foto cambiada se recargue. */
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Fotos de hasta este tamaño viajan tal cual; las más grandes se reducen antes de mandarlas. */
    private const val MAX_TAL_CUAL = 260_000L
    private const val LADO_RED = 800

    private fun clave(f: File, lado: Int) = f.path + "#" + f.length() + "#" + lado

    /** Lee la foto reducida para que quepa en [lado] píxeles por el lado más largo. */
    fun leer(f: File, lado: Int): Bitmap? = try {
        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, medidas)
        var muestra = 1
        val mayor = maxOf(medidas.outWidth, medidas.outHeight)
        while (mayor / (muestra * 2) >= lado) muestra *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = muestra })
    } catch (_: Throwable) {
        null
    }

    /** Pone la miniatura de [f] en [vista] (o la esconde si no hay foto). Carga aparte y no se equivoca si la fila se reutiliza. */
    fun cargar(vista: ImageView, f: File?, lado: Int) {
        if (f == null || !f.exists()) {
            vista.setTag(R.id.itFoto, null)
            vista.setImageDrawable(null)
            return
        }
        val k = clave(f, lado)
        vista.setTag(R.id.itFoto, k)
        val enMemoria = cache.get(k)
        if (enMemoria != null) {
            vista.setImageBitmap(enMemoria)
            return
        }
        vista.setImageDrawable(null)
        hilo.execute {
            val b = leer(f, lado) ?: return@execute
            cache.put(k, b)
            main.post {
                if (vista.getTag(R.id.itFoto) == k) vista.setImageBitmap(b)
            }
        }
    }

    /** La foto lista para mandar a otro equipo: tal cual si es liviana, o reducida (800 px) si pesa mucho. */
    fun paraRed(f: File): ByteArray? = try {
        if (f.length() <= MAX_TAL_CUAL) {
            f.readBytes()
        } else {
            val b = leer(f, LADO_RED)
            if (b == null) {
                null
            } else {
                val bos = ByteArrayOutputStream()
                b.compress(Bitmap.CompressFormat.JPEG, 70, bos)
                bos.toByteArray()
            }
        }
    } catch (_: Throwable) {
        null
    }

    /** Muestra la foto completa en una ventana negra; un toque la cierra. */
    fun ver(ctx: Context, f: File, titulo: String) {
        val b = leer(f, 1600) ?: return
        val d = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val marco = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
        val img = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(b)
        }
        marco.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val dens = ctx.resources.displayMetrics.density
        val t = TextView(ctx).apply {
            text = titulo
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding((16 * dens).toInt(), (12 * dens).toInt(), (16 * dens).toInt(), (12 * dens).toInt())
            setBackgroundColor(Color.argb(160, 0, 0, 0))
        }
        marco.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        marco.setOnClickListener { d.dismiss() }
        d.setContentView(marco)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        d.show()
    }
}
