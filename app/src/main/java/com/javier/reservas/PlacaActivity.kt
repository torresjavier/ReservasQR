package com.javier.reservas

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Cámara que lee la placa con OCR (ML Kit, funciona sin internet).
 * Corrige confusiones O/Q/0, I/1… y confirma contra el listado antes de devolverla.
 */
class PlacaActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLACA = "placa"
        private const val CONFIRMACIONES = 2
    }

    private lateinit var vista: PreviewView
    private lateinit var txtLeida: TextView
    private lateinit var txtEstado: TextView

    private var camara: Camera? = null
    private var linterna = false
    private var terminado = false
    private lateinit var hilo: ExecutorService
    private val reconocedor = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Veces que se vio cada placa que sí está en el listado. */
    private val vistasEnLista = HashMap<String, Int>()
    /** Veces que se vio cada lectura (aunque no esté en el listado), para «Usar esta placa». */
    private val vistas = HashMap<String, Int>()
    private val correccionesDe = HashMap<String, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_placa)

        vista = findViewById(R.id.vistaCamara)
        txtLeida = findViewById(R.id.txtLeida)
        txtEstado = findViewById(R.id.txtEstadoOcr)
        val arriba = findViewById<View>(R.id.panelArriba)
        val abajo = findViewById<View>(R.id.panelAbajo)
        val padArriba = arriba.paddingTop
        val padAbajo = abajo.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.raizPlaca)) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            arriba.setPadding(arriba.paddingLeft, padArriba + b.top, arriba.paddingRight, arriba.paddingBottom)
            abajo.setPadding(abajo.paddingLeft, abajo.paddingTop, abajo.paddingRight, padAbajo + b.bottom)
            insets
        }

        findViewById<Button>(R.id.btnCancelarPlaca).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnUsarPlaca).setOnClickListener { usarMejorLectura() }
        findViewById<Button>(R.id.btnLinterna).setOnClickListener { cambiarLinterna() }

        hilo = Executors.newSingleThreadExecutor()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            iniciarCamara()
        } else {
            Toast.makeText(this, "Falta el permiso de cámara.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hilo.shutdown()
        reconocedor.close()
    }

    private fun iniciarCamara() {
        val futuro = ProcessCameraProvider.getInstance(this)
        futuro.addListener({
            try {
                val proveedor = futuro.get()
                val previa = Preview.Builder().build()
                previa.setSurfaceProvider(vista.surfaceProvider)

                val selector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
                val analisis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analisis.setAnalyzer(hilo) { proxy -> analizar(proxy) }

                proveedor.unbindAll()
                camara = proveedor.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previa, analisis)
            } catch (e: Exception) {
                txtEstado.text = "No se pudo abrir la cámara: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analizar(proxy: ImageProxy) {
        val media = proxy.image
        if (terminado || media == null) {
            proxy.close(); return
        }
        val imagen = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        reconocedor.process(imagen)
            .addOnSuccessListener { texto -> procesar(texto) }
            .addOnCompleteListener { proxy.close() }
    }

    private fun procesar(texto: Text) {
        if (terminado) return
        val lineas = ArrayList<String>()
        for (bloque in texto.textBlocks) {
            for (linea in bloque.lines) lineas.add(linea.text)
            lineas.add(bloque.text.replace('\n', ' '))
        }
        val lecturas = lineas.flatMap { OcrPlaca.lecturas(it) }.distinctBy { it.placa }
        if (lecturas.isEmpty()) {
            txtEstado.text = "Buscando placa…"
            return
        }

        val listado = Compartido.listado
        // 1) ¿Alguna lectura coincide con una placa del listado (aceptando O/Q/0, I/1…)?
        if (listado != null) {
            for (lec in lecturas) {
                val filas = listado.buscarPlacaOcr(lec.placa)
                if (filas.isNotEmpty()) {
                    val placaLista = listado.placaNormal(filas[0])
                    val n = (vistasEnLista[placaLista] ?: 0) + 1
                    vistasEnLista[placaLista] = n
                    txtLeida.text = placaLista
                    txtEstado.text = "✓ Está en el listado. Confirmando…"
                    if (n >= CONFIRMACIONES) terminar(lec.placa)
                    return
                }
            }
        }

        // 2) No está en el listado: se muestra la mejor lectura para que el operador decida.
        for (lec in lecturas) {
            vistas[lec.placa] = (vistas[lec.placa] ?: 0) + 1
            val c = correccionesDe[lec.placa]
            if (c == null || lec.correcciones < c) correccionesDe[lec.placa] = lec.correcciones
        }
        val mejor = mejorLectura() ?: return
        txtLeida.text = mejor
        txtEstado.text = if (listado == null) "Leída. Toque «Usar esta placa»."
        else "Leída «$mejor», pero no está en el listado.\nAcérquese a la placa, o toque «Usar esta placa» para revisarla."
    }

    /** La lectura vista más veces; a igualdad, la que necesitó menos correcciones. */
    private fun mejorLectura(): String? =
        vistas.keys.maxWithOrNull(
            compareBy<String> { vistas[it] ?: 0 }.thenByDescending { correccionesDe[it] ?: 9 }
        )

    private fun usarMejorLectura() {
        val placa = mejorLectura()
        if (placa == null) {
            Toast.makeText(this, "Aún no se ha leído ninguna placa.", Toast.LENGTH_SHORT).show()
            return
        }
        terminar(placa)
    }

    private fun terminar(placa: String) {
        if (terminado) return
        terminado = true
        vibrar()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PLACA, placa))
        finish()
    }

    private fun cambiarLinterna() {
        val c = camara ?: return
        if (!c.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "Este equipo no tiene linterna.", Toast.LENGTH_SHORT).show()
            return
        }
        linterna = !linterna
        c.cameraControl.enableTorch(linterna)
    }

    @Suppress("DEPRECATION")
    private fun vibrar() {
        try {
            val v = getSystemService(Vibrator::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(80)
        } catch (_: Exception) {
        }
    }
}
