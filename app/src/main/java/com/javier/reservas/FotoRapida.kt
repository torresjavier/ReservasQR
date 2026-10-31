package com.javier.reservas

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foto rápida del vehículo para los ingresos que se registran con QR o por búsqueda (adentro).
 *
 * Abre la cámara trasera sin pantalla de vista previa, espera un instante a que ajuste la luz y el enfoque, toma
 * un cuadro, cierra la cámara y entrega la imagen. Si no hay permiso de cámara, la cámara no abre o se demora
 * demasiado, entrega null y el registro sigue sin foto: nunca bloquea la operación.
 */
object FotoRapida {

    /** Tiempo que se deja correr la cámara antes de tomar el cuadro (ajuste automático de luz y enfoque). */
    private const val CALENTAR_MS = 700L

    /** Si en este tiempo no hubo foto, se sigue sin ella. */
    private const val LIMITE_MS = 3000L

    private val main = Handler(Looper.getMainLooper())

    /**
     * Toma la foto y llama a [alTerminar] una sola vez, siempre en el hilo principal (con la foto o con null).
     */
    fun tomar(act: AppCompatActivity, alTerminar: (Bitmap?) -> Unit) {
        if (act.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            alTerminar(null)
            return
        }
        val hilo: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "foto-rapida").apply { isDaemon = true } }
        val inicio = SystemClock.elapsedRealtime()
        var terminado = false
        var proveedor: ProcessCameraProvider? = null
        var analisis: ImageAnalysis? = null

        // Se llama en el hilo principal; el primer llamado gana y los demás se ignoran
        fun cerrar(foto: Bitmap?) {
            if (terminado) return
            terminado = true
            try {
                analisis?.let { proveedor?.unbind(it) }
            } catch (_: Exception) {
            }
            hilo.shutdown()
            alTerminar(foto)
        }

        main.postDelayed({ cerrar(null) }, LIMITE_MS)

        val futuro = ProcessCameraProvider.getInstance(act)
        futuro.addListener({
            if (terminado) return@addListener
            try {
                val p = futuro.get()
                proveedor = p
                val selector = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
                val a = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                var tomada = false
                a.setAnalyzer(hilo) { proxy ->
                    try {
                        if (!tomada && SystemClock.elapsedRealtime() - inicio >= CALENTAR_MS) {
                            tomada = true
                            val foto = aBitmap(proxy)
                            main.post { cerrar(foto) }
                        }
                    } finally {
                        proxy.close()
                    }
                }
                analisis = a
                p.bindToLifecycle(act, CameraSelector.DEFAULT_BACK_CAMERA, a)
            } catch (e: Exception) {
                cerrar(null)
            }
        }, ContextCompat.getMainExecutor(act))
    }

    /** El cuadro como imagen derecha (con el giro que informa la cámara aplicado). */
    private fun aBitmap(proxy: ImageProxy): Bitmap? = try {
        val bmp = proxy.toBitmap()
        val giro = proxy.imageInfo.rotationDegrees
        if (giro == 0) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(giro.toFloat()) }, true)
    } catch (e: Exception) {
        null
    }
}
