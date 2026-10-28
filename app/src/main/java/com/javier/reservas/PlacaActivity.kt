package com.javier.reservas

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
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
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Cámara que lee SOLO placas (amarillas o blancas, de carro o de moto) y trabaja según el modo del equipo:
 *  - VÍA: valida el acceso sin detener el vehículo (vibra y sigue).
 *  - INTERIOR: registra el ingreso, toma la foto e imprime el ticket.
 *
 * Cómo ahorra batería y evita confundirse:
 *  1. Un detector liviano busca una zona con forma y color de placa. Si no hay placa a la vista NO se usa el OCR.
 *  2. Cuando hay placa, la cámara se enfoca en ella y el OCR lee solo ese recorte (no toda la escena).
 *  3. Sin placa por 20 segundos, entra en modo ahorro: casi no procesa cuadros y baja el brillo.
 *     Se despierta sola cuando aparece una placa, o al tocar la pantalla.
 */
class PlacaActivity : AppCompatActivity() {

    companion object {
        /** El operador tocó «Escribir»: la pantalla principal abre el buscador con esta placa. */
        const val EXTRA_ESCRIBIR = "escribir"
        /** Tiempo sin volver a procesar la misma placa (evita registrar dos veces el mismo carro). */
        private const val ENFRIAMIENTO_MS = 12_000L
        private const val AVISO_MS = 2_600L
        private const val AVISO_VIA_MS = 1_400L
        /** Sin placa durante este tiempo → modo ahorro. */
        private const val ESPERA_AHORRO_MS = 20_000L
        /** Una zona que parece placa pero nunca se lee se ignora un rato (letreros amarillos, por ejemplo). */
        private const val FALLOS_PARA_IGNORAR = 12
        private const val IGNORAR_MS = 6_000L
    }

    private enum class Tipo { OK, VIP, REPETIDO, ALERTA }
    private enum class Energia { ACTIVO, ESPERA, AHORRO }

    private lateinit var vista: PreviewView
    private lateinit var marco: View
    private lateinit var txtLeida: TextView
    private lateinit var txtEstado: TextView
    private lateinit var txtSesion: TextView
    private lateinit var txtModoCamara: TextView
    private lateinit var panelAviso: View
    private lateinit var avisoTitulo: TextView
    private lateinit var avisoPlaca: TextView
    private lateinit var avisoDetalle: TextView

    private var camara: Camera? = null
    private var linterna = false
    private lateinit var hilo: ExecutorService
    private val detector = DetectorPlaca()
    private val reconocedor = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val main = Handler(Looper.getMainLooper())
    private val hora = SimpleDateFormat("hh:mm a", Locale("es", "CO"))

    // ---- estado del hilo de análisis
    @Volatile private var energia = Energia.ESPERA
    @Volatile private var ultimoCuadro = 0L
    @Volatile private var ultimaPlacaVista = SystemClock.elapsedRealtime()
    @Volatile private var lecturaCompletaHasta = 0L
    private var ultimoEnfoque = 0L
    private var enfoqueX = -1f
    private var enfoqueY = -1f
    private var zoomActual = 1f
    private var textoUi = ""
    private var ultimoTextoUi = 0L

    // ---- zonas que parecen placa pero no se leen
    private var fallos = 0
    private var zonaIgnorada: Rect? = null
    private var ignoradaHasta = 0L

    /** placa → hasta cuándo se ignora */
    private val enfriamiento = HashMap<String, Long>()
    private var candidata: String? = null
    private var vecesCandidata = 0
    private var candidataT = 0L
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
        marco = findViewById(R.id.marco)
        txtLeida = findViewById(R.id.txtLeida)
        txtEstado = findViewById(R.id.txtEstadoOcr)
        txtSesion = findViewById(R.id.txtSesion)
        txtModoCamara = findViewById(R.id.txtModoCamara)
        panelAviso = findViewById(R.id.panelAviso)
        avisoTitulo = findViewById(R.id.avisoTitulo)
        avisoPlaca = findViewById(R.id.avisoPlaca)
        avisoDetalle = findViewById(R.id.avisoDetalle)
        txtModoCamara.text = Operacion.descripcionModo()

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
        // Tocar la cámara: despierta del modo ahorro o, si no se detecta la placa sola, fuerza una lectura completa de 3 s
        vista.setOnClickListener {
            if (energia == Energia.AHORRO) {
                despertar()
            } else {
                lecturaCompletaHasta = SystemClock.elapsedRealtime() + 3_000L
                mostrarEstado("Leyendo toda la imagen…", true)
            }
        }

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
        brillo(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        main.removeCallbacksAndMessages(null)
        hilo.shutdown()
        reconocedor.close()
    }

    private fun iniciarCamara() {
        val futuro = ProcessCameraProvider.getInstance(this)
        futuro.addListener({
            try {
                val proveedor = futuro.get()
                // Misma proporción 16:9 en la vista y en el análisis: lo que se ve es lo que se analiza
                val proporcion = AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                val selectorPrevia = ResolutionSelector.Builder().setAspectRatioStrategy(proporcion).build()
                val previa = Preview.Builder().setResolutionSelector(selectorPrevia).build()
                previa.setSurfaceProvider(vista.surfaceProvider)
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(proporcion)
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
                ultimaPlacaVista = SystemClock.elapsedRealtime()
                mostrarEstado("Apunte a la placa", true)
            } catch (e: Exception) {
                txtEstado.text = "No se pudo abrir la cámara: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------------------------------------------------------------- análisis de cada cuadro (hilo aparte)

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analizar(proxy: ImageProxy) {
        if (isFinishing) {
            proxy.close(); return
        }
        val t = SystemClock.elapsedRealtime()
        // Menos cuadros cuanto menos pasa: 8 por segundo con placa, ~14 buscándola, 1-2 en ahorro
        val hueco = when (energia) {
            Energia.ACTIVO -> 110L
            Energia.ESPERA -> 70L
            Energia.AHORRO -> 650L
        }
        if (t - ultimoCuadro < hueco) {
            proxy.close(); return
        }
        ultimoCuadro = t

        val completa = t < lecturaCompletaHasta
        var placa: DetectorPlaca.Resultado? = null
        try {
            val p = proxy.planes
            placa = detector.detectar(
                p[0].buffer, p[1].buffer, p[2].buffer,
                p[0].rowStride, p[1].rowStride, p[1].pixelStride,
                proxy.width, proxy.height, proxy.imageInfo.rotationDegrees
            )
        } catch (_: Exception) {
        }

        // Una zona que ya se probó muchas veces sin éxito se ignora un rato
        val ign = zonaIgnorada
        if (placa != null && ign != null && t < ignoradaHasta &&
            ign.contains(placa.centroX().toInt(), placa.centroY().toInt())
        ) placa = null

        if (placa == null && !completa) {
            sinPlaca(t)
            proxy.close()
            return
        }

        if (placa != null) {
            ultimaPlacaVista = t
            if (energia != Energia.ACTIVO) {
                val estaba = energia
                energia = Energia.ACTIVO
                if (estaba == Energia.AHORRO) main.post { salirAhorro() }
            }
            val r: DetectorPlaca.Resultado = placa
            main.post {
                moverMarco(r)
                enfocar(r, t)
                ajustarZoom(r)
            }
        }

        val recorte: Bitmap? = if (placa != null) recortar(proxy, placa) else null
        val imagen = if (recorte != null) {
            InputImage.fromBitmap(recorte, 0)
        } else {
            val media = proxy.image
            if (media == null) {
                proxy.close(); return
            }
            InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        }
        val zona = placa
        reconocedor.process(imagen)
            .addOnSuccessListener { texto -> procesar(texto, proxy, zona) }
            .addOnCompleteListener { proxy.close() }
    }

    /** No hay placa a la vista: pasa de activo a espera y, si dura, a modo ahorro. */
    private fun sinPlaca(t: Long) {
        val sin = t - ultimaPlacaVista
        if (energia == Energia.ACTIVO && sin > 2_500L) energia = Energia.ESPERA
        if (energia == Energia.ESPERA && sin > ESPERA_AHORRO_MS) {
            energia = Energia.AHORRO
            main.post { entrarAhorro() }
        }
        if (sin > 3_500L) {
            main.post {
                moverMarco(null)
                if (zoomActual > 1.05f) {
                    camara?.cameraControl?.setZoomRatio(1f)
                    zoomActual = 1f
                }
                if (energia != Energia.AHORRO) mostrarEstado("Buscando placa…", false)
            }
        }
    }

    /** Recorte de la placa (con un poco de margen), agrandado si es pequeño, para que el OCR lea mejor. */
    private fun recortar(proxy: ImageProxy, r: DetectorPlaca.Resultado): Bitmap? = try {
        val mw = (r.ancho() * 0.10f).toInt()
        val mh = (r.alto() * 0.18f).toInt()
        val izq = maxOf(0, r.izq - mw)
        val arr = maxOf(0, r.arr - mh)
        val der = minOf(r.anchoCuadro, r.der + mw)
        val aba = minOf(r.altoCuadro, r.aba + mh)
        val w = der - izq
        val h = aba - arr
        if (w < 32 || h < 32) {
            null
        } else {
            val p = proxy.planes
            val px = DetectorPlaca.recortar(
                p[0].buffer, p[1].buffer, p[2].buffer,
                p[0].rowStride, p[1].rowStride, p[1].pixelStride,
                proxy.width, proxy.height, proxy.imageInfo.rotationDegrees,
                izq, arr, der, aba
            )
            var b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            if (w < 420) {
                val k = minOf(3f, 420f / w)
                b = Bitmap.createScaledBitmap(b, (w * k).toInt(), (h * k).toInt(), true)
            }
            b
        }
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------------------- lectura del texto (hilo principal)

    private fun candidatos(texto: Text): Pair<List<String>, Boolean> {
        val lineas = texto.textBlocks.flatMap { it.lines }
            .filter { Listado.normPlaca(it.text).length >= 2 }
            .sortedBy { it.boundingBox?.top ?: 0 }
        val salida = ArrayList<String>()
        lineas.forEach { salida.add(it.text) }
        var dos = false
        // Las placas de moto traen las letras arriba y los números abajo: se unen los renglones apilados
        for (i in 0 until lineas.size - 1) {
            val a = lineas[i].boundingBox
            val b = lineas[i + 1].boundingBox
            if (a == null || b == null) continue
            val solape = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val ancho = minOf(a.width(), b.width())
            if (b.top >= a.centerY() && ancho > 0 && solape > ancho * 0.4f) {
                salida.add(lineas[i].text + " " + lineas[i + 1].text)
                val na = Listado.normPlaca(lineas[i].text).length
                val nb = Listado.normPlaca(lineas[i + 1].text).length
                if (na in 2..4 && nb in 2..4) dos = true
            }
        }
        if (lineas.size in 2..3) salida.add(lineas.joinToString(" ") { it.text })
        return salida to dos
    }

    /** Se ejecuta en el hilo principal con cada recorte leído. */
    private fun procesar(texto: Text, proxy: ImageProxy, zona: DetectorPlaca.Resultado?) {
        val l = Operacion.listado ?: return
        val (cands, dosRenglones) = candidatos(texto)
        val pista: OcrPlaca.Tipo? = if (zona != null) OcrPlaca.tipoPorForma(zona.relacion(), dosRenglones) else null
        val lecturas = cands.flatMap { OcrPlaca.lecturas(it, pista, dosRenglones) }.distinctBy { it.placa }
        val ahora = System.currentTimeMillis()

        if (lecturas.isEmpty()) {
            if (zona != null) {
                fallos++
                if (fallos >= FALLOS_PARA_IGNORAR) {
                    fallos = 0
                    val m = zona.ancho() / 6
                    zonaIgnorada = Rect(zona.izq - m, zona.arr - m, zona.der + m, zona.aba + m)
                    ignoradaHasta = SystemClock.elapsedRealtime() + IGNORAR_MS
                }
            }
            mostrarEstado(if (zona != null) etiquetaZona(zona) + " · acérquese un poco" else "Apunte a la placa", false)
            return
        }
        fallos = 0

        for (lec in lecturas) {
            val filas = l.buscarPlacaOcr(lec.placa)
            if (filas.isEmpty()) continue
            val exacta = l.placaExacta(lec.placa)
            // Si la lectura se parece a varias placas del listado, se espera una lectura más clara
            if (filas.size > 1 && !exacta) {
                txtLeida.text = lec.placa
                mostrarEstado("Se parece a ${filas.size} placas. Acérquese un poco.", true)
                return
            }
            val fila = filas.firstOrNull { !l.ingresado(it) } ?: filas[0]
            val placa = l.placaNormal(fila)
            ultimaLectura = placa
            txtLeida.text = placa
            if ((enfriamiento[placa] ?: 0L) > ahora) {
                mostrarEstado("$placa ya fue procesada", true)
                return
            }
            // Una lectura exacta basta; si hubo que corregir letras (O/Q, I/1…) se confirma con otro cuadro reciente
            if (placa == candidata && ahora - candidataT < 2_500L) vecesCandidata++ else { candidata = placa; vecesCandidata = 1 }
            candidataT = ahora
            val necesarias = if (exacta && lec.correcciones == 0) 1 else 2
            if (vecesCandidata < necesarias) {
                mostrarEstado("Confirmando $placa…", true)
                return
            }
            candidata = null
            vecesCandidata = 0
            enfriamiento[placa] = ahora + ENFRIAMIENTO_MS
            registrarDesdeCamara(fila, lec.placa, proxy, pista)
            return
        }

        // Ninguna lectura está en el listado
        val primera = lecturas.first()
        val mejor = primera.placa
        ultimaLectura = mejor
        txtLeida.text = mejor
        mostrarEstado("$mejor no tiene reserva", true)
        // Solo lecturas limpias de placa completa (evita avisos por letreros o lecturas a medias)
        if (primera.correcciones == 0 && (mejor.length == 6 || mejor.length == 5) && (enfriamiento[mejor] ?: 0L) <= ahora) {
            enfriamiento[mejor] = ahora + ENFRIAMIENTO_MS
            val foto = tomarFoto(proxy)
            Operacion.alertaSinReserva(Operacion.Origen.PLACA, mejor, foto)
            val via = Operacion.accionPara(Operacion.Origen.PLACA) == Operacion.Accion.VALIDAR
            vibrar(longArrayOf(0, 400))
            mostrarAviso(
                Tipo.ALERTA, "SIN RESERVA", mejor,
                if (via) "No está en el listado. No debe pasar." else "Hay un vehículo adentro sin reserva.",
                via
            )
        }
    }

    private fun etiquetaZona(z: DetectorPlaca.Resultado): String {
        val color = if (z.color == DetectorPlaca.AMARILLA) "amarilla" else "blanca"
        val tipo = OcrPlaca.tipoPorForma(z.relacion(), false)
        val v = when (tipo) {
            OcrPlaca.Tipo.MOTO -> "moto"
            OcrPlaca.Tipo.CARRO -> "carro"
            null -> "vehículo"
        }
        return "Placa $color de $v"
    }

    // ---------------------------------------------------------------- registrar

    private fun registrarDesdeCamara(fila: Int, leida: String, proxy: ImageProxy, tipoVisto: OcrPlaca.Tipo?) {
        val l = Operacion.listado ?: return
        val foto = tomarFoto(proxy)
        val placa = l.placaNormal(fila)
        val nombre = l.valor(fila, Listado.Campo.NOMBRE).ifBlank { l.titulo(fila) }
        val evento = l.valor(fila, Listado.Campo.EVENTO)
        val vip = l.esVip(fila)
        val resultado = try {
            Operacion.procesar(fila, Operacion.Origen.PLACA, leida, foto, tipoVisto)
        } catch (e: Exception) {
            mostrarEstado("No se pudo registrar: ${e.message}", true)
            return
        }
        val via = Operacion.accionPara(Operacion.Origen.PLACA) == Operacion.Accion.VALIDAR
        when (resultado) {
            is Operacion.Resultado.Validado -> {
                sesion++
                txtSesion.text = sesion.toString()
                vibrar(longArrayOf(0, 90))
                mostrarAviso(
                    if (vip) Tipo.VIP else Tipo.OK,
                    if (vip) "★ VIP · ACCESO VALIDADO" else "✓ ACCESO VALIDADO",
                    placa,
                    listOf(nombre, resultado.aviso.orEmpty()).filter { it.isNotBlank() }.joinToString("\n"),
                    true
                )
                if (resultado.aviso != null) vibrar(longArrayOf(0, 150, 100, 150))
                if (Operacion.ajustes.imprimirAlValidar) {
                    Impresora.imprimir(this, Operacion.lineasValidacion(fila)) { error ->
                        if (error != null) txtEstado.text = "⚠ $error"
                    }
                } else mostrarEstado("✓ $placa validada", true)
            }
            is Operacion.Resultado.YaValidado -> {
                vibrar(longArrayOf(0, 80, 90, 80))
                if (resultado.sospechoso) {
                    vibrar(longArrayOf(0, 300, 120, 300))
                    mostrarAviso(
                        Tipo.ALERTA, "⚠ YA SE VALIDÓ", placa,
                        "$nombre\nYa pasó a las ${hora.format(Date(resultado.hora))}. Verifique: puede ser un QR o placa repetida.",
                        via
                    )
                } else {
                    mostrarAviso(Tipo.REPETIDO, "YA VALIDADA", placa, "$nombre\nValidada a las ${hora.format(Date(resultado.hora))}", via)
                }
                mostrarEstado("$placa ya había sido validada", true)
            }
            is Operacion.Resultado.Ingresado -> {
                sesion++
                txtSesion.text = sesion.toString()
                vibrar(longArrayOf(0, if (resultado.sinValidar) 450 else 120))
                val extra = buildList {
                    add(nombre)
                    if (evento.isNotBlank()) add(evento)
                    if (resultado.sinValidar) add("⚠ No pasó por la vía")
                    resultado.aviso?.let { add(it) }
                }.filter { it.isNotBlank() }.joinToString("\n")
                mostrarAviso(
                    if (vip) Tipo.VIP else Tipo.OK,
                    if (vip) "★ VIP · RESERVA INGRESADA" else "✓ RESERVA INGRESADA",
                    placa, extra, false
                )
                if (Operacion.ajustes.autoImprimir) {
                    mostrarEstado("Imprimiendo ticket de $placa…", true)
                    Impresora.imprimir(this, Operacion.lineasTicket(fila, false)) { error ->
                        txtEstado.text = if (error == null) "✓ Ticket impreso: $placa" else "⚠ $error"
                    }
                } else {
                    mostrarEstado("✓ $placa registrada", true)
                }
            }
            is Operacion.Resultado.YaIngreso -> {
                vibrar(longArrayOf(0, 80, 90, 80))
                mostrarAviso(Tipo.REPETIDO, "YA INGRESÓ", placa, "$nombre\nIngresó a las ${hora.format(Date(resultado.hora))}", false)
                mostrarEstado("$placa ya había ingresado", true)
            }
            is Operacion.Resultado.SinValidacion -> {
                vibrar(longArrayOf(0, 500, 150, 500))
                mostrarAviso(
                    Tipo.ALERTA, "✗ NO PASÓ POR LA VÍA", placa,
                    "$nombre\nPosible colado. No se registró el ingreso.", false
                )
                mostrarEstado("$placa no fue validada en la vía", true)
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

    // ---------------------------------------------------------------- pantalla

    private fun mostrarEstado(t: String, forzar: Boolean) {
        val ahora = SystemClock.elapsedRealtime()
        if (!forzar && (t == textoUi || ahora - ultimoTextoUi < 400L)) return
        textoUi = t
        ultimoTextoUi = ahora
        txtEstado.text = t
    }

    private fun mostrarAviso(tipo: Tipo, titulo: String, placa: String, detalle: String, rapido: Boolean) {
        val (fondo, colorTexto) = when (tipo) {
            Tipo.OK -> R.drawable.aviso_ok to R.color.negro
            Tipo.VIP -> R.drawable.aviso_vip to R.color.negro
            Tipo.REPETIDO -> R.drawable.aviso_repetido to R.color.blanco
            Tipo.ALERTA -> R.drawable.aviso_error to R.color.blanco
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
        // En la vía el aviso dura poco para no estorbar la lectura del siguiente vehículo
        main.postDelayed(ocultarAviso, if (rapido && tipo != Tipo.ALERTA) AVISO_VIA_MS else AVISO_MS)
    }

    /** Pasa de coordenadas del cuadro (derecho) a coordenadas de la vista (la vista recorta al centro). */
    private fun aVista(x: Float, y: Float, cw: Int, ch: Int): PointF {
        val vw = vista.width.toFloat()
        val vh = vista.height.toFloat()
        if (vw <= 0f || vh <= 0f || cw <= 0 || ch <= 0) return PointF(x, y)
        val escala = maxOf(vw / cw, vh / ch)
        val dx = (cw * escala - vw) / 2f
        val dy = (ch * escala - vh) / 2f
        return PointF(x * escala - dx, y * escala - dy)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** El recuadro rojo sigue a la placa detectada; sin placa vuelve al centro como guía. */
    private fun moverMarco(r: DetectorPlaca.Resultado?) {
        val lp = marco.layoutParams as FrameLayout.LayoutParams
        if (r == null) {
            if (lp.gravity == Gravity.CENTER) return
            lp.gravity = Gravity.CENTER
            lp.width = dp(310)
            lp.height = dp(120)
            lp.leftMargin = 0
            lp.topMargin = 0
        } else {
            val a = aVista(r.izq.toFloat(), r.arr.toFloat(), r.anchoCuadro, r.altoCuadro)
            val b = aVista(r.der.toFloat(), r.aba.toFloat(), r.anchoCuadro, r.altoCuadro)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = a.x.toInt()
            lp.topMargin = a.y.toInt()
            lp.width = (b.x - a.x).toInt().coerceAtLeast(dp(40))
            lp.height = (b.y - a.y).toInt().coerceAtLeast(dp(30))
            mostrarEstado(etiquetaZona(r), false)
        }
        marco.layoutParams = lp
    }

    /** Enfoca y mide la luz en la placa para que los caracteres salgan nítidos. */
    private fun enfocar(r: DetectorPlaca.Resultado, t: Long) {
        val c = camara ?: return
        val movio = abs(r.centroX() - enfoqueX) > r.anchoCuadro * 0.12f || abs(r.centroY() - enfoqueY) > r.altoCuadro * 0.12f
        if (!movio && t - ultimoEnfoque < 2_500L) return
        try {
            val p = aVista(r.centroX(), r.centroY(), r.anchoCuadro, r.altoCuadro)
            val punto = vista.meteringPointFactory.createPoint(p.x, p.y)
            val accion = FocusMeteringAction.Builder(punto, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            c.cameraControl.startFocusAndMetering(accion)
            ultimoEnfoque = t
            enfoqueX = r.centroX()
            enfoqueY = r.centroY()
        } catch (_: Exception) {
        }
    }

    /** Si la placa se ve muy pequeña y está al centro, acerca un poco para leerla mejor. */
    private fun ajustarZoom(r: DetectorPlaca.Resultado) {
        val c = camara ?: return
        try {
            val maximo = c.cameraInfo.zoomState.value?.maxZoomRatio ?: return
            val fr = r.fraccionAncho()
            val cx = r.centroX() / r.anchoCuadro
            val cy = r.centroY() / r.altoCuadro
            var objetivo = zoomActual
            if (fr < 0.13f && cx in 0.3f..0.7f && cy in 0.25f..0.75f && zoomActual < 1.2f) {
                objetivo = (0.26f / fr).coerceIn(1f, minOf(3f, maximo))
            } else if (fr > 0.45f && zoomActual > 1.2f) {
                objetivo = 1f
            }
            if (abs(objetivo - zoomActual) > 0.3f) {
                c.cameraControl.setZoomRatio(objetivo)
                zoomActual = objetivo
            }
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- ahorro de batería

    private fun entrarAhorro() {
        if (isFinishing) return
        brillo(0.05f)
        if (linterna) {
            camara?.cameraControl?.enableTorch(false)
            linterna = false
        }
        moverMarco(null)
        mostrarEstado("Modo ahorro · apunte a una placa para continuar", true)
    }

    private fun salirAhorro() {
        brillo(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        mostrarEstado("Placa detectada…", true)
    }

    private fun despertar() {
        ultimaPlacaVista = SystemClock.elapsedRealtime()
        energia = Energia.ESPERA
        salirAhorro()
        mostrarEstado("Apunte a la placa", true)
    }

    private fun brillo(valor: Float) {
        try {
            val lp = window.attributes
            lp.screenBrightness = valor
            window.attributes = lp
        } catch (_: Exception) {
        }
    }

    private fun cambiarLinterna() {
        val c = camara ?: return
        if (!c.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "Este equipo no tiene linterna.", Toast.LENGTH_SHORT).show()
            return
        }
        linterna = !linterna
        c.cameraControl.enableTorch(linterna)
        if (energia == Energia.AHORRO) despertar()
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
