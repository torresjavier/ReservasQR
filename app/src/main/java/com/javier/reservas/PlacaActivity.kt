package com.javier.reservas

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
import android.view.View
import android.view.WindowManager
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Cámara en modo continuo para revisar muchos carros seguidos:
 * cuando reconoce una placa del listado vibra, toma la foto, registra el ingreso e imprime el ticket,
 * y sigue con el siguiente carro sin salir de la cámara.
 */
class PlacaActivity : AppCompatActivity() {

    companion object {
        /** El operador tocó «Escribir»: la pantalla principal abre el buscador con esta placa. */
        const val EXTRA_ESCRIBIR = "escribir"
        /** Tiempo sin volver a procesar la misma placa (evita registrar dos veces el mismo carro). */
        private const val ENFRIAMIENTO_MS = 12_000L
        private const val AVISO_MS = 2_600L
    }

    private enum class Tipo { OK, VIP, REPETIDO, SIN_RESERVA }

    private lateinit var vista: PreviewView
    private lateinit var txtLeida: TextView
    private lateinit var txtEstado: TextView
    private lateinit var txtSesion: TextView
    private lateinit var panelAviso: View
    private lateinit var avisoTitulo: TextView
    private lateinit var avisoPlaca: TextView
    private lateinit var avisoDetalle: TextView

    private var camara: Camera? = null
    private var linterna = false
    private lateinit var hilo: ExecutorService
    private val reconocedor = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val main = Handler(Looper.getMainLooper())
    private val hora = SimpleDateFormat("hh:mm a", Locale("es", "CO"))

    /** placa → hasta cuándo se ignora */
    private val enfriamiento = HashMap<String, Long>()
    private var candidata: String? = null
    private var vecesCandidata = 0
    private var sesion = 0
    private var ultimaLectura: String? = null
    private val ocultarAviso = Runnable { panelAviso.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_placa)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Operacion.iniciar(this)

        vista = findViewById(R.id.vistaCamara)
        txtLeida = findViewById(R.id.txtLeida)
        txtEstado = findViewById(R.id.txtEstadoOcr)
        txtSesion = findViewById(R.id.txtSesion)
        panelAviso = findViewById(R.id.panelAviso)
        avisoTitulo = findViewById(R.id.avisoTitulo)
        avisoPlaca = findViewById(R.id.avisoPlaca)
        avisoDetalle = findViewById(R.id.avisoDetalle)

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

        findViewById<Button>(R.id.btnTerminar).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnLinterna).setOnClickListener { cambiarLinterna() }
        findViewById<Button>(R.id.btnEscribirPlaca).setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ESCRIBIR, ultimaLectura.orEmpty()))
            finish()
        }
        panelAviso.setOnClickListener { panelAviso.visibility = View.GONE }

        hilo = Executors.newSingleThreadExecutor()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            iniciarCamara()
        } else {
            Toast.makeText(this, "Falta el permiso de cámara.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        // Si la app estuvo bloqueada, se vuelve a la pantalla principal que pide la clave
        if (Bloqueo.requiereClave) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
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
        if (media == null || isFinishing) {
            proxy.close(); return
        }
        val imagen = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        reconocedor.process(imagen)
            .addOnSuccessListener { texto -> procesar(texto, proxy) }
            .addOnCompleteListener { proxy.close() }
    }

    /** Se ejecuta en el hilo principal con cada cuadro leído. */
    private fun procesar(texto: Text, proxy: ImageProxy) {
        val l = Operacion.listado ?: return
        val lineas = ArrayList<String>()
        for (b in texto.textBlocks) {
            for (ln in b.lines) lineas.add(ln.text)
            if (b.lines.size > 1) lineas.add(b.text.replace('\n', ' '))
        }
        val lecturas = lineas.flatMap { OcrPlaca.lecturas(it) }.distinctBy { it.placa }
        if (lecturas.isEmpty()) {
            txtEstado.text = "Apunte a la placa dentro del recuadro"
            return
        }
        val ahora = System.currentTimeMillis()

        for (lec in lecturas) {
            val filas = l.buscarPlacaOcr(lec.placa)
            if (filas.isEmpty()) continue
            val exacta = l.placaExacta(lec.placa)
            // Si la lectura se parece a varias placas del listado, se espera una lectura más clara
            if (filas.size > 1 && !exacta) {
                txtLeida.text = lec.placa
                txtEstado.text = "Se parece a ${filas.size} placas. Acérquese un poco."
                return
            }
            val fila = filas.firstOrNull { !l.ingresado(it) } ?: filas[0]
            val placa = l.placaNormal(fila)
            ultimaLectura = placa
            txtLeida.text = placa
            if ((enfriamiento[placa] ?: 0L) > ahora) {
                txtEstado.text = "$placa ya fue procesada"
                return
            }
            // Una lectura exacta basta; si hubo que corregir letras (O/Q, I/1…) se confirma con otro cuadro
            if (placa == candidata) vecesCandidata++ else { candidata = placa; vecesCandidata = 1 }
            val necesarias = if (exacta && lec.correcciones == 0) 1 else 2
            if (vecesCandidata < necesarias) {
                txtEstado.text = "Confirmando $placa…"
                return
            }
            candidata = null
            vecesCandidata = 0
            enfriamiento[placa] = ahora + ENFRIAMIENTO_MS
            registrarDesdeCamara(fila, lec.placa, proxy)
            return
        }

        // Ninguna lectura está en el listado
        val primera = lecturas.first()
        val mejor = primera.placa
        ultimaLectura = mejor
        txtLeida.text = mejor
        txtEstado.text = "$mejor no tiene reserva"
        // El aviso rojo solo con lecturas limpias de placa completa (evita avisos por letreros o lecturas a medias)
        if (primera.correcciones == 0 && mejor.length == 6 && (enfriamiento[mejor] ?: 0L) <= ahora) {
            enfriamiento[mejor] = ahora + ENFRIAMIENTO_MS
            mostrarAviso(Tipo.SIN_RESERVA, "SIN RESERVA", mejor, "Esta placa no está en el listado")
        }
    }

    private fun registrarDesdeCamara(fila: Int, leida: String, proxy: ImageProxy) {
        val l = Operacion.listado ?: return
        val foto = tomarFoto(proxy)
        val placa = l.placaNormal(fila)
        val nombre = l.valor(fila, Listado.Campo.NOMBRE).ifBlank { l.titulo(fila) }
        val evento = l.valor(fila, Listado.Campo.EVENTO)
        when (val r = Operacion.registrar(fila, Operacion.Origen.PLACA, leida, foto)) {
            is Operacion.Registro.Nuevo -> {
                sesion++
                txtSesion.text = sesion.toString()
                vibrar(longArrayOf(0, 120))
                val vip = l.esVip(fila)
                mostrarAviso(
                    if (vip) Tipo.VIP else Tipo.OK,
                    if (vip) "★ VIP · RESERVA INGRESADA" else "✓ RESERVA INGRESADA",
                    placa,
                    listOf(nombre, evento).filter { it.isNotBlank() }.joinToString("\n")
                )
                if (Operacion.ajustes.autoImprimir) {
                    txtEstado.text = "Imprimiendo ticket de $placa…"
                    Impresora.imprimir(this, Operacion.lineasTicket(fila, false)) { error ->
                        txtEstado.text = if (error == null) "✓ Ticket impreso: $placa" else "⚠ $error"
                    }
                } else {
                    txtEstado.text = "✓ $placa registrada"
                }
            }
            is Operacion.Registro.Repetido -> {
                vibrar(longArrayOf(0, 80, 90, 80))
                mostrarAviso(Tipo.REPETIDO, "YA INGRESÓ", placa, "$nombre\nIngresó a las ${hora.format(Date(r.hora))}")
                txtEstado.text = "$placa ya había ingresado"
            }
        }
    }

    /** Foto del mismo cuadro donde se leyó la placa (sin demorar la lectura). */
    private fun tomarFoto(proxy: ImageProxy): Bitmap? = try {
        val bmp = proxy.toBitmap()
        val giro = proxy.imageInfo.rotationDegrees
        if (giro == 0) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(giro.toFloat()) }, true)
    } catch (e: Exception) {
        null
    }

    private fun mostrarAviso(tipo: Tipo, titulo: String, placa: String, detalle: String) {
        val (fondo, colorTexto) = when (tipo) {
            Tipo.OK -> R.drawable.aviso_ok to R.color.negro
            Tipo.VIP -> R.drawable.aviso_vip to R.color.negro
            Tipo.REPETIDO -> R.drawable.aviso_repetido to R.color.blanco
            Tipo.SIN_RESERVA -> R.drawable.aviso_error to R.color.blanco
        }
        panelAviso.setBackgroundResource(fondo)
        val c = getColor(colorTexto)
        avisoTitulo.setTextColor(c)
        avisoDetalle.setTextColor(c)
        avisoTitulo.text = titulo
        avisoPlaca.text = placa
        avisoDetalle.text = detalle
        avisoDetalle.visibility = if (detalle.isBlank()) View.GONE else View.VISIBLE
        panelAviso.visibility = View.VISIBLE
        main.removeCallbacks(ocultarAviso)
        main.postDelayed(ocultarAviso, AVISO_MS)
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
    private fun vibrar(patron: LongArray) {
        try {
            val v = getSystemService(Vibrator::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(patron, -1))
            else v.vibrate(patron, -1)
        } catch (_: Exception) {
        }
    }
}
