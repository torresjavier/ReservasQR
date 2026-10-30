package com.javier.reservas

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Cámara que lee SOLO placas (amarillas o blancas, de carro o de moto) y trabaja según el modo del equipo:
 *  - VÍA: valida el acceso sin detener el vehículo (vibra y sigue).
 *  - INTERIOR: registra el ingreso, toma la foto e imprime el ticket.
 *
 * Cómo lee (varias técnicas a la vez, de la más barata a la más pesada):
 *  0. MOTOR DE IA (v1.5): un detector de placas (YOLOv9) la encuentra y un lector especializado entrega la probabilidad de
 *     cada letra o número. Se obliga el formato colombiano (AAA000 carro, AAA00A moto) y se compara con el listado de
 *     reservas: la G no se confunde con 6, y por el formato se sabe si es carro o moto. Si la IA no ve placa, entra el
 *     respaldo de abajo (ML Kit).
 *  1. ZONA DE LECTURA fija (recuadro punteado): el foco y la luz se miden ahí y se lee solo lo que cae adentro.
 *  2. Un detector liviano busca la placa por color y forma en toda la imagen. Si la ve, se lee solo ese recorte, ampliado.
 *  3. Si no se logra leer, cada cuadro prueba una versión distinta de la imagen (más contraste, blanco y negro,
 *     afilado contra el movimiento) hasta que alguna se lee bien.
 *  4. Varios cuadros votan: carácter por carácter gana el más repetido, y pesan más los cuadros nítidos.
 *  5. Con el vehículo en movimiento: escena de acción / obturador rápido, y se descartan los cuadros movidos.
 *  6. Sin placa por 20 segundos: modo ahorro (casi no procesa y baja el brillo).
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

        /** Tiempo mínimo entre cuadros según la energía. Con placa a la vista se trabaja a toda velocidad. */
        private const val HUECO_ACTIVO = 0L
        private const val HUECO_ESPERA = 90L
        private const val HUECO_AHORRO = 700L
        /** Tiempo mínimo entre lecturas de texto (OCR) cuando no se ve una placa clara. */
        private const val OCR_CADA_ESPERA = 260L
        private const val OCR_CADA_AHORRO = 1_500L

        /** Zona fija de lectura, como fracción del cuadro ya derecho. */
        private const val ZONA_X0 = 0.04f
        private const val ZONA_X1 = 0.96f
        private const val ZONA_Y0 = 0.30f
        private const val ZONA_Y1 = 0.62f

        /** Un recorte de placa más borroso que esto se descarta (salvo que lleve varios descartados seguidos). */
        private const val NITIDEZ_MIN = 0.16f
        private const val MAX_SALTADOS = 3
        /** Contraste mínimo de la zona para gastar un OCR en ella. */
        private const val CONTRASTE_MIN = 28

        /** Peso de votos necesario: lectura limpia que coincide exacto / lectura corregida / aviso de «sin reserva». */
        private const val PESO_EXACTA = 0.9f
        private const val PESO_CORREGIDA = 1.3f
        private const val PESO_ALERTA = 1.8f
        /** Motor de IA: placa del listado elegida con poca seguridad (puntaje entre −1 y −3): se pide el doble de evidencia. */
        private const val PESO_IA_DUDA = 1.8f

        /** Motor de IA: tiempo mínimo entre lecturas cuando no hay una placa a la vista. */
        private const val IA_CADA_ESPERA = 200L
        private const val IA_CADA_AHORRO = 1_200L
        /** Si la IA no ve placa, el respaldo (ML Kit) trabaja como mucho a este ritmo. */
        private const val RESPALDO_CADA = 450L
        private const val IA_OMITIDO = 0
        private const val IA_SIN_PLACA = 1
        private const val IA_ASINCRONO = 2
        /** Método AUTO con IA: se busca en todo el ancho y en la parte central del cuadro. */
        private const val AUTO_Y0 = 0.12f
        private const val AUTO_Y1 = 0.82f

        private val ZOOMS = floatArrayOf(1f, 1.5f, 2f, 3f)
        private val FOCOS = intArrayOf(0, 70, 120, 200, 400)
    }

    private enum class Tipo { OK, VIP, REPETIDO, ALERTA }
    private enum class Energia { ACTIVO, ESPERA, AHORRO }

    /** Lo que se mandó al OCR en un cuadro, para entender después lo que devolvió. */
    private class Pase(
        val zona: Rect,                                 // zona del cuadro derecho que se leyó
        val paso: Int,                                  // reducción aplicada al recortar (1 = ninguna)
        val escala: Float,                              // ampliación aplicada antes del OCR
        val detectada: DetectorPlaca.Resultado?,        // placa que encontró el detector (si fue la que se leyó)
        val gris: IntArray,                             // recorte en brillos, sin ampliar
        val gw: Int,
        val gh: Int,
        val nitidez: Float                              // nitidez del recorte (−1 si no se midió)
    )

    private class Cand(val texto: String, val caja: Rect?)
    private class Hallazgo(val lec: OcrPlaca.Lectura, val caja: Rect?)

    /** Lo que leyó el motor de IA en un cuadro: probabilidades por posición, dónde estaba la placa y cuán nítida salió. */
    private class LecturaIA(
        val logp: Array<FloatArray>,
        val caja: Rect,                 // placa en el cuadro derecho
        val confianza: Float,           // seguridad del detector
        val nitidez: Float,
        val recorte: IntArray,          // imagen de la placa (ARGB), para la miniatura
        val rw: Int,
        val rh: Int,
        val ms: Int                     // lo que tardó la IA en este cuadro
    )

    private lateinit var vista: PreviewView
    private lateinit var zonaView: View
    private lateinit var marco: View
    private lateinit var imgCrop: ImageView
    private lateinit var txtLeida: TextView
    private lateinit var txtEstado: TextView
    private lateinit var txtSesion: TextView
    private lateinit var txtModoCamara: TextView
    private lateinit var panelAviso: View
    private lateinit var avisoTitulo: TextView
    private lateinit var avisoPlaca: TextView
    private lateinit var avisoDetalle: TextView
    private lateinit var opMotor: TextView
    private lateinit var opMetodo: TextView
    private lateinit var opZoom: TextView
    private lateinit var opFoco: TextView
    private lateinit var opMovimiento: TextView
    private lateinit var opResolucion: TextView

    private var camara: Camera? = null
    private var linterna = false
    private lateinit var hilo: ExecutorService
    private val detector = DetectorPlaca()
    private val reconocedor = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val main = Handler(Looper.getMainLooper())
    private val hora = SimpleDateFormat("hh:mm a", Locale("es", "CO"))

    // ---- opciones elegidas por el operador (se guardan en Ajustes)
    @Volatile private var metodo = 0        // 0 automático, 1 zona fija, 2 toda la imagen
    private var zoom = 1f
    private var focoCm = 0
    @Volatile private var movimiento = 1    // 0 normal, 1 rápido, 2 obturador manual
    private var altaRes = true
    @Volatile private var motorIA = 0       // 0 IA con respaldo ML Kit, 1 solo IA, 2 solo ML Kit

    // ---- motor de IA (detector de placas + lector con formato de placa colombiana)
    @Volatile private var motor: MotorPlacas? = null
    @Volatile private var motorEstado = 0   // 0 cargando, 1 listo, 2 no disponible
    private val acum = AcumuladorPlacas(2_500L)
    private var ultimoIA = 0L
    private var ultimoRespaldo = 0L
    private var iaFallos = 0
    private var iaMs = 0f
    private var ultimoTextoMotor = 0L

    // ---- exposición manual (solo con movimiento = 2)
    private var manualPosible = false
    private var expNs = 3_000_000L
    private var iso = 400
    private var expMin = 500_000L
    private var expMax = 12_000_000L
    private var isoMin = 100
    private var isoMax = 1600
    private var ultimoAjusteExp = 0L

    // ---- estado del hilo de análisis
    @Volatile private var energia = Energia.ESPERA
    @Volatile private var ultimoCuadro = 0L
    @Volatile private var ultimaPlacaVista = SystemClock.elapsedRealtime()
    @Volatile private var lecturaCompletaHasta = 0L
    @Volatile private var indicioHasta = 0L
    @Volatile private var frameW = 0
    @Volatile private var frameH = 0
    @Volatile private var sinLecturaSeguidas = 0
    private var contador = 0
    private var ultimoOcr = 0L
    private var saltados = 0
    private var ultimaMiniatura = 0L
    private var textoUi = ""
    private var ultimoTextoUi = 0L

    // ---- zonas que parecen placa pero no se leen
    private var fallos = 0
    private var fallosDet = 0
    private var zonaIgnorada: Rect? = null
    private var ignoradaHasta = 0L

    // ---- lecturas (hilo principal)
    /** placa (forma canónica) → hasta cuándo se ignora */
    private val enfriamiento = HashMap<String, Long>()
    private val voto = VotoPlacas(2_500L)
    private var sesion = 0
    private var ultimaLectura: String? = null
    private var ultimaPista: OcrPlaca.Tipo? = null
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
        zonaView = findViewById(R.id.zonaLectura)
        marco = findViewById(R.id.marco)
        imgCrop = findViewById(R.id.imgCrop)
        txtLeida = findViewById(R.id.txtLeida)
        txtEstado = findViewById(R.id.txtEstadoOcr)
        txtSesion = findViewById(R.id.txtSesion)
        txtModoCamara = findViewById(R.id.txtModoCamara)
        panelAviso = findViewById(R.id.panelAviso)
        avisoTitulo = findViewById(R.id.avisoTitulo)
        avisoPlaca = findViewById(R.id.avisoPlaca)
        avisoDetalle = findViewById(R.id.avisoDetalle)
        opMotor = findViewById(R.id.opMotor)
        opMetodo = findViewById(R.id.opMetodo)
        opZoom = findViewById(R.id.opZoom)
        opFoco = findViewById(R.id.opFoco)
        opMovimiento = findViewById(R.id.opMovimiento)
        opResolucion = findViewById(R.id.opResolucion)
        txtModoCamara.text = Operacion.descripcionModo()

        val aj = Operacion.ajustes
        metodo = aj.metodoPlaca
        zoom = aj.zoomPlaca
        focoCm = aj.focoPlacaCm
        movimiento = aj.movimientoPlaca
        altaRes = aj.altaResolucion
        motorIA = aj.motorPlaca
        refrescarOpciones()
        cargarMotor()

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
        vista.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> posicionarZona() }

        findViewById<Button>(R.id.btnTerminar).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnLinterna).setOnClickListener { cambiarLinterna() }
        findViewById<Button>(R.id.btnEscribirPlaca).setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ESCRIBIR, ultimaLectura.orEmpty()))
            finish()
        }
        panelAviso.setOnClickListener { panelAviso.visibility = View.GONE }
        // Tocar la cámara: despierta del modo ahorro o, si no se lee sola, fuerza una lectura de toda la imagen por 3 s
        vista.setOnClickListener {
            if (energia == Energia.AHORRO) {
                despertar()
            } else {
                lecturaCompletaHasta = SystemClock.elapsedRealtime() + 3_000L
                mostrarEstado("Leyendo toda la imagen…", true)
            }
            enfocarZona()
        }

        opMotor.setOnClickListener {
            motorIA = (motorIA + 1) % 3
            Operacion.ajustes.motorPlaca = motorIA
            reiniciarLectura()
            refrescarOpciones()
            if (motorIA != 2 && motorEstado == 2) {
                Toast.makeText(this, "La IA no está disponible en este equipo: se lee con ML Kit.", Toast.LENGTH_LONG).show()
            }
            mostrarEstado(
                when (motorIA) {
                    0 -> "IA + respaldo: lee con inteligencia artificial y, si no ve la placa, prueba con ML Kit"
                    1 -> "Solo IA: lee con el modelo de placas (formato AAA000 carro, AAA00A moto)"
                    else -> "Solo ML Kit: el lector de texto de la versión anterior"
                }, true
            )
        }
        opMetodo.setOnClickListener {
            metodo = (metodo + 1) % 3
            Operacion.ajustes.metodoPlaca = metodo
            reiniciarLectura()
            refrescarOpciones()
            posicionarZona()
            mostrarEstado(
                when (metodo) {
                    0 -> "Automático: busca la placa en toda la imagen y también en el recuadro"
                    1 -> "Zona fija: lee solo lo que está dentro del recuadro punteado"
                    else -> "Toda la imagen: busca texto de placa en cualquier parte"
                }, true
            )
        }
        opZoom.setOnClickListener {
            val i = ZOOMS.indexOfFirst { abs(it - zoom) < 0.05f }
            zoom = ZOOMS[(i + 1) % ZOOMS.size]
            Operacion.ajustes.zoomPlaca = zoom
            aplicarZoom()
            refrescarOpciones()
            enfocarZona()
        }
        opFoco.setOnClickListener {
            val i = FOCOS.indexOf(focoCm)
            focoCm = FOCOS[(i + 1) % FOCOS.size]
            Operacion.ajustes.focoPlacaCm = focoCm
            aplicarOpcionesCamara()
            refrescarOpciones()
            enfocarZona()
            if (focoCm > 0) mostrarEstado("Foco fijo a ${textoDistancia(focoCm)}: ponga la placa a esa distancia", true)
        }
        opMovimiento.setOnClickListener {
            var m = (movimiento + 1) % 3
            if (m == 2 && !manualPosible) {
                Toast.makeText(this, "Este equipo no permite obturador manual.", Toast.LENGTH_SHORT).show()
                m = 0
            }
            movimiento = m
            Operacion.ajustes.movimientoPlaca = m
            aplicarOpcionesCamara()
            refrescarOpciones()
        }
        opResolucion.setOnClickListener {
            altaRes = !altaRes
            Operacion.ajustes.altaResolucion = altaRes
            refrescarOpciones()
            iniciarCamara()
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
        // El motor de IA se cierra en el mismo hilo que lo usa, después del cuadro que esté procesando
        val m = motor
        motor = null
        motorEstado = 2
        hilo.execute {
            try {
                m?.close()
            } catch (_: Throwable) {
            }
        }
        hilo.shutdown()
        reconocedor.close()
    }

    // ---------------------------------------------------------------- motor de IA

    /** Carga los dos modelos (detector y lector) en un hilo aparte: tarda un instante y no debe frenar la cámara. */
    private fun cargarMotor() {
        Thread {
            try {
                val det = assets.open("placas/detector.onnx").use { it.readBytes() }
                val lec = assets.open("placas/lector.onnx").use { it.readBytes() }
                val m = MotorPlacas(EjecutorOrt(det, lec))
                // Primera pasada en vacío: así el primer cuadro real no se demora
                val gris = IntArray(256 * 256) { 0xFF727272.toInt() }
                m.detectar(gris, 256, 256)
                m.leer(IntArray(128 * 64) { 0xFF727272.toInt() }, 128, 64)
                if (isDestroyed) {
                    m.close()
                    return@Thread
                }
                motor = m
                motorEstado = 1
                main.post {
                    refrescarOpciones()
                    if (motorIA != 2) mostrarEstado("IA lista · ponga la placa dentro del recuadro", true)
                }
            } catch (e: Throwable) {
                motorEstado = 2
                main.post {
                    refrescarOpciones()
                    mostrarEstado("La IA no se pudo cargar (${e.javaClass.simpleName}): se lee con ML Kit", true)
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- cámara

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
                val tam = if (altaRes) Size(1920, 1080) else Size(1280, 720)
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(proporcion)
                    .setResolutionStrategy(ResolutionStrategy(tam, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
                val analisis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analisis.setAnalyzer(hilo) { proxy -> analizar(proxy) }
                proveedor.unbindAll()
                camara = proveedor.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previa, analisis)
                ultimaPlacaVista = SystemClock.elapsedRealtime()
                frameW = 0
                frameH = 0
                revisarCapacidades()
                aplicarZoom()
                aplicarOpcionesCamara()
                refrescarOpciones()
                main.postDelayed({ enfocarZona() }, 700L)
                mostrarEstado("Ponga la placa dentro del recuadro", true)
            } catch (e: Exception) {
                txtEstado.text = "No se pudo abrir la cámara: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Qué permite esta cámara: obturador manual y su rango de exposición y sensibilidad. */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun revisarCapacidades() {
        manualPosible = false
        val c = camara ?: return
        try {
            val info = Camera2CameraInfo.from(c.cameraInfo)
            val capacidades = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val rangoExp = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val rangoIso = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            if (capacidades != null && rangoExp != null && rangoIso != null &&
                capacidades.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            ) {
                expMin = max(rangoExp.lower, 500_000L)
                expMax = min(rangoExp.upper, 12_000_000L)
                isoMin = max(rangoIso.lower, 100)
                isoMax = min(rangoIso.upper, 1600)
                if (expMin < expMax && isoMin < isoMax) {
                    expNs = expNs.coerceIn(expMin, expMax)
                    iso = iso.coerceIn(isoMin, isoMax)
                    manualPosible = true
                }
            }
        } catch (_: Exception) {
        }
        if (movimiento == 2 && !manualPosible) {
            movimiento = 1
            Operacion.ajustes.movimientoPlaca = 1
        }
    }

    /**
     * Foco fijo, vehículo en movimiento (escena de acción, más cuadros por segundo u obturador manual).
     * Se manda siempre el conjunto completo de opciones: lo que no se pide vuelve al modo automático.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun aplicarOpcionesCamara() {
        val c = camara ?: return
        try {
            val info = Camera2CameraInfo.from(c.cameraInfo)
            val b = CaptureRequestOptions.Builder()
            val mov = if (movimiento == 2 && !manualPosible) 1 else movimiento

            // Foco a una distancia fija (el lente no tiene que buscar: ideal con vehículos pasando)
            val minFoco = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            if (focoCm > 0 && minFoco > 0f) {
                val dioptrias = (100f / focoCm).coerceIn(0f, minFoco)
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, dioptrias)
            }

            if (mov >= 1) {
                // Más cuadros por segundo = exposición más corta = menos movimiento borroso
                val rangos = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                val mejor = rangos?.filter { it.upper in 24..60 }
                    ?.maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
                if (mejor != null) b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, mejor)
                if (mov == 1) {
                    val escenas = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
                    if (escenas != null && escenas.contains(CameraMetadata.CONTROL_SCENE_MODE_SPORTS)) {
                        b.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
                        b.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_SPORTS)
                    }
                } else {
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                    b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                }
            }
            Camera2CameraControl.from(c.cameraControl).setCaptureRequestOptions(b.build())
        } catch (_: Exception) {
        }
    }

    private fun aplicarZoom() {
        val c = camara ?: return
        try {
            val estado = c.cameraInfo.zoomState.value
            val minimo = estado?.minZoomRatio ?: 1f
            val maximo = estado?.maxZoomRatio ?: 1f
            c.cameraControl.setZoomRatio(zoom.coerceIn(minimo, max(minimo, maximo)))
        } catch (_: Exception) {
        }
    }

    /** Foco y luz medidos en el centro de la zona de lectura. Se queda ahí (no salta de un lado a otro). */
    private fun enfocarZona() {
        val c = camara ?: return
        try {
            if (vista.width <= 0 || vista.height <= 0) return
            val lp = zonaView.layoutParams as FrameLayout.LayoutParams
            val cx = if (zonaView.visibility == View.VISIBLE && lp.width > 0) lp.leftMargin + lp.width / 2f else vista.width / 2f
            val cy = if (zonaView.visibility == View.VISIBLE && lp.height > 0) lp.topMargin + lp.height / 2f else vista.height / 2f
            val punto = vista.meteringPointFactory.createPoint(cx, cy)
            val banderas = if (focoCm > 0) FocusMeteringAction.FLAG_AE else FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            val accion = FocusMeteringAction.Builder(punto, banderas).disableAutoCancel().build()
            c.cameraControl.startFocusAndMetering(accion)
        } catch (_: Exception) {
        }
    }

    /** Con obturador manual: sube o baja la sensibilidad y el tiempo de exposición según la luz del centro de la imagen. */
    private fun ajustarExposicion(plano: ImageProxy.PlaneProxy, ancho: Int, alto: Int, t: Long) {
        if (t - ultimoAjusteExp < 350L) return
        ultimoAjusteExp = t
        val buf = plano.buffer
        val fila = plano.rowStride
        var suma = 0L
        var n = 0
        var yy = alto / 5
        while (yy < alto * 4 / 5) {
            var xx = ancho / 5
            val base = yy * fila
            while (xx < ancho * 4 / 5) {
                suma += (buf.get(base + xx).toInt() and 0xFF)
                n++
                xx += 12
            }
            yy += 12
        }
        if (n == 0) return
        val media = (suma / n).toInt()
        var cambio = false
        if (media < 85) {
            if (iso < isoMax) {
                iso = min(isoMax, (iso * 1.4f).toInt() + 1)
                cambio = true
            } else if (expNs < expMax) {
                expNs = min(expMax, (expNs * 1.35f).toLong())
                cambio = true
            }
        } else if (media > 175) {
            if (iso > isoMin) {
                iso = max(isoMin, (iso / 1.4f).toInt())
                cambio = true
            } else if (expNs > expMin) {
                expNs = max(expMin, (expNs / 1.35f).toLong())
                cambio = true
            }
        }
        if (cambio) main.post { aplicarOpcionesCamara() }
    }

    private fun reiniciarLectura() {
        voto.limpiar()
        acum.limpiar()
        sinLecturaSeguidas = 0
        fallos = 0
        fallosDet = 0
        zonaIgnorada = null
        indicioHasta = 0L
        mostrar(null)
    }

    // ---------------------------------------------------------------- análisis de cada cuadro (hilo aparte)

    private fun analizar(proxy: ImageProxy) {
        if (isFinishing) {
            proxy.close(); return
        }
        val t = SystemClock.elapsedRealtime()
        // Con placa a la vista se trabaja a toda velocidad; sin placa, pocos cuadros por segundo
        val hueco = when (energia) {
            Energia.ACTIVO -> HUECO_ACTIVO
            Energia.ESPERA -> HUECO_ESPERA
            Energia.AHORRO -> HUECO_AHORRO
        }
        if (t - ultimoCuadro < hueco) {
            proxy.close(); return
        }
        ultimoCuadro = t
        var asincrono = false
        try {
            asincrono = procesarCuadro(proxy, t)
        } catch (_: Exception) {
        }
        // Si se lanzó el OCR, el cuadro se cierra cuando el OCR termina
        if (!asincrono) proxy.close()
    }

    /** @return true si se lanzó el OCR (entonces el cuadro se cierra al terminar él) */
    private fun procesarCuadro(proxy: ImageProxy, t: Long): Boolean {
        val p = proxy.planes
        val ancho = proxy.width
        val alto = proxy.height
        val rot = proxy.imageInfo.rotationDegrees
        val giro = rot == 90 || rot == 270
        val uw = if (giro) alto else ancho
        val uh = if (giro) ancho else alto
        if (uw != frameW || uh != frameH) {
            frameW = uw
            frameH = uh
            main.post { posicionarZona() }
        }
        if (movimiento == 2 && manualPosible && energia != Energia.AHORRO) ajustarExposicion(p[0], ancho, alto, t)

        val forzada = t < lecturaCompletaHasta
        val metodoAct = if (forzada) 2 else metodo
        contador++

        // 0) Motor de IA: detector de placas + lector que obliga el formato AAA000 / AAA00A
        if (motorIA != 2 && motorEstado == 1) {
            when (procesarConIA(proxy, t, ancho, alto, rot, uw, uh, metodoAct, forzada)) {
                IA_ASINCRONO -> return true
                IA_SIN_PLACA -> {
                    // La IA no vio placa: solo con «IA + respaldo» entra ML Kit, y de vez en cuando
                    if (motorIA == 1 || t - ultimoRespaldo < RESPALDO_CADA) {
                        sinPlaca(t)
                        return false
                    }
                    ultimoRespaldo = t
                }
                else -> {
                    // Todavía no toca leer (ahorro de energía)
                    sinPlaca(t)
                    return false
                }
            }
        }

        // 1) Detector de placa por color y forma (solo en automático)
        var det: DetectorPlaca.Resultado? = null
        if (metodoAct == 0) {
            try {
                det = detector.detectar(
                    p[0].buffer, p[1].buffer, p[2].buffer,
                    p[0].rowStride, p[1].rowStride, p[1].pixelStride,
                    ancho, alto, rot, (contador and 1) == 0
                )
            } catch (_: Exception) {
            }
            val ign = zonaIgnorada
            if (det != null && ign != null && t < ignoradaHasta && ign.contains(det.centroX().toInt(), det.centroY().toInt())) {
                det = null
            }
        }
        if (det != null) {
            ultimaPlacaVista = t
            indicioHasta = t + 2_500L
            if (energia != Energia.ACTIVO) {
                val estaba = energia
                energia = Energia.ACTIVO
                if (estaba == Energia.AHORRO) main.post { salirAhorro() }
            }
        }

        // 2) Qué parte del cuadro se lee: el recorte de la placa detectada, la zona fija o toda la imagen
        var usarDet = det != null && fallosDet < 3
        if (det != null && !usarDet) {
            fallosDet++
            if (fallosDet >= 8) fallosDet = 0   // vuelve a probar con el recorte de la placa
        }
        val zona: Rect = when {
            metodoAct == 2 -> Rect(0, 0, uw, uh)
            usarDet && det != null -> ampliar(det, uw, uh)
            else -> zonaFija(uw, uh)
        }
        if (det != null) {
            val d: DetectorPlaca.Resultado = det
            main.post { mostrar(Rect(d.izq, d.arr, d.der, d.aba)) }
        }

        // 3) Sin placa clara no se gasta OCR más que de vez en cuando
        val cadaOcr = when (energia) {
            Energia.ACTIVO -> 0L
            Energia.ESPERA -> OCR_CADA_ESPERA
            Energia.AHORRO -> OCR_CADA_AHORRO
        }
        if (!forzada && det == null && t - ultimoOcr < cadaOcr) {
            sinPlaca(t)
            return false
        }

        // 4) Recorte (solo brillos: rápido) y revisión de que haya algo que leer
        val paso = max(1, ceil(max(zona.width(), zona.height()) / 1400f).toInt())
        val tam = DetectorPlaca.tamanoRecorte(zona.left, zona.top, zona.right, zona.bottom, paso)
        val gw = tam[0]
        val gh = tam[1]
        if (gw < 24 || gh < 24) return false
        val gris = DetectorPlaca.recortarGris(
            p[0].buffer, p[0].rowStride, ancho, alto, rot,
            zona.left, zona.top, zona.right, zona.bottom, paso
        )
        if (!forzada && det == null) {
            val contraste = ImagenPlaca.contraste(gris)
            val brilloMedio = ImagenPlaca.brilloMedio(gris)
            if (contraste < CONTRASTE_MIN || brilloMedio < 8 || brilloMedio > 250) {
                sinPlaca(t)
                return false
            }
        }

        // Con el vehículo en movimiento muchos cuadros salen movidos: se descartan los más borrosos
        var nit = -1f
        if (usarDet) {
            nit = ImagenPlaca.nitidez(gris, gw, gh)
            if (nit < NITIDEZ_MIN && saltados < MAX_SALTADOS) {
                saltados++
                return false
            }
            saltados = 0
        }

        // 5) Imagen para el OCR: ampliada si es pequeña y, cuando no se logra leer, con una técnica distinta cada cuadro
        val variante = if (t < indicioHasta && (contador and 1) == 1) {
            1 + ((contador shr 1) % (ImagenPlaca.NUM_VARIANTES - 1))
        } else {
            0
        }
        var px = ImagenPlaca.aArgb(gris)
        var bw = gw
        var bh = gh
        var escala = 1f
        if (usarDet && det != null) {
            val objetivo = if (det.relacion() < 1.6f) 520 else 640
            if (gw < objetivo) {
                escala = min(4f, objetivo.toFloat() / gw)
                val pequena = Bitmap.createBitmap(px, gw, gh, Bitmap.Config.ARGB_8888)
                val grande = Bitmap.createScaledBitmap(pequena, (gw * escala).toInt(), (gh * escala).toInt(), true)
                bw = grande.width
                bh = grande.height
                px = IntArray(bw * bh)
                grande.getPixels(px, 0, bw, 0, 0, bw, bh)
            }
        }
        if (variante != 0) px = ImagenPlaca.variante(px, bw, bh, variante)
        val bmp = Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888)

        if (t - ultimaMiniatura > 500L) {
            ultimaMiniatura = t
            try {
                val mw = 192
                val mh = max(1, (bh * (mw.toFloat() / bw)).toInt())
                val mini = Bitmap.createScaledBitmap(bmp, mw, mh, true)
                main.post { imgCrop.setImageBitmap(mini) }
            } catch (_: Exception) {
            }
        }

        // 6) OCR (último paso: de aquí en adelante el cuadro lo cierra el OCR al terminar)
        ultimoOcr = t
        val pase = Pase(zona, paso, escala, if (usarDet) det else null, gris, gw, gh, nit)
        reconocedor.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { texto -> alTexto(texto, proxy, pase) }
            .addOnCompleteListener { proxy.close() }
        return true
    }

    // ---------------------------------------------------------------- lectura con IA

    /** Dónde busca el detector de placas, en píxeles del cuadro derecho. */
    private fun regionIA(metodoAct: Int, uw: Int, uh: Int): Rect = when (metodoAct) {
        1 -> zonaFija(uw, uh)
        2 -> Rect(0, 0, uw, uh)
        else -> Rect(0, (uh * AUTO_Y0).toInt(), uw, (uh * AUTO_Y1).toInt())
    }

    /**
     * Un cuadro con el motor de IA (hilo de análisis): detecta la placa, la recorta a color y el lector devuelve las
     * probabilidades de cada símbolo. La decisión se toma después, en el hilo principal.
     * @return IA_ASINCRONO si hay una placa (el cuadro se cierra al terminar de decidir), IA_SIN_PLACA si no se vio ninguna,
     *         IA_OMITIDO si todavía no tocaba leer
     */
    private fun procesarConIA(
        proxy: ImageProxy, t: Long, ancho: Int, alto: Int, rot: Int, uw: Int, uh: Int, metodoAct: Int, forzada: Boolean
    ): Int {
        val m = motor ?: return IA_OMITIDO
        val cada = when (energia) {
            Energia.ACTIVO -> 0L
            Energia.ESPERA -> IA_CADA_ESPERA
            Energia.AHORRO -> IA_CADA_AHORRO
        }
        if (!forzada && t - ultimoIA < cada) return IA_OMITIDO
        ultimoIA = t
        try {
            val p = proxy.planes
            // 1) Imagen de la zona donde se busca, reducida a un tamaño que le sirva al detector
            val reg = regionIA(metodoAct, uw, uh)
            val paso = max(1, max(reg.width(), reg.height()) / MotorPlacas.TAM_DET)
            val tam = DetectorPlaca.tamanoRecorte(reg.left, reg.top, reg.right, reg.bottom, paso)
            val img = MotorPlacas.recortarArgb(
                p[0].buffer, p[1].buffer, p[2].buffer, p[0].rowStride, p[1].rowStride, p[1].pixelStride,
                ancho, alto, rot, reg.left, reg.top, reg.right, reg.bottom, paso
            )
            val dets = m.detectar(img, tam[0], tam[1])
            iaFallos = 0
            val mejor = dets.firstOrNull() ?: return IA_SIN_PLACA

            // 2) La placa en el cuadro derecho y su recorte a tamaño completo, con un margen para no cortar letras
            val caja = Rect(
                reg.left + mejor.x1 * paso, reg.top + mejor.y1 * paso,
                reg.left + mejor.x2 * paso, reg.top + mejor.y2 * paso
            )
            val mx = (caja.width() * 0.08f).toInt()
            val my = (caja.height() * 0.08f).toInt()
            val pr = Rect(max(0, caja.left - mx), max(0, caja.top - my), min(uw, caja.right + mx), min(uh, caja.bottom + my))
            if (pr.width() < 16 || pr.height() < 8) return IA_SIN_PLACA
            val pasoP = max(1, pr.width() / 256)
            val tp = DetectorPlaca.tamanoRecorte(pr.left, pr.top, pr.right, pr.bottom, pasoP)
            val recorte = MotorPlacas.recortarArgb(
                p[0].buffer, p[1].buffer, p[2].buffer, p[0].rowStride, p[1].rowStride, p[1].pixelStride,
                ancho, alto, rot, pr.left, pr.top, pr.right, pr.bottom, pasoP
            )

            // 3) El lector: probabilidad de cada letra o número en cada posición
            val logp = m.leer(recorte, tp[0], tp[1]) ?: return IA_SIN_PLACA
            val nit = ImagenPlaca.nitidez(ImagenPlaca.gris(recorte), tp[0], tp[1])
            val ms = (SystemClock.elapsedRealtime() - t).toInt()
            val lectura = LecturaIA(logp, caja, mejor.confianza, nit, recorte, tp[0], tp[1], ms)
            main.post {
                try {
                    alIA(lectura, proxy)
                } catch (_: Exception) {
                } finally {
                    proxy.close()
                }
            }
            return IA_ASINCRONO
        } catch (e: Throwable) {
            // Si la IA falla varias veces seguidas se desactiva y se sigue con ML Kit
            iaFallos++
            if (iaFallos >= 3) {
                motorEstado = 2
                main.post {
                    refrescarOpciones()
                    mostrarEstado("La IA falló (${e.javaClass.simpleName}): se lee con ML Kit", true)
                }
            }
            return IA_SIN_PLACA
        }
    }

    /** Miniatura de lo que está leyendo la IA (el recorte de la placa). */
    private fun miniaturaIA(l: LecturaIA): Bitmap {
        val bmp = Bitmap.createBitmap(l.recorte, l.rw, l.rh, Bitmap.Config.ARGB_8888)
        val mw = 192
        return Bitmap.createScaledBitmap(bmp, mw, max(1, (l.rh * (mw.toFloat() / l.rw)).toInt()), true)
    }

    /** Hilo principal: llega la lectura de un cuadro. Se suma a las anteriores y se decide. */
    private fun alIA(lec: LecturaIA, proxy: ImageProxy) {
        val l = Operacion.listado ?: return
        val ahora = SystemClock.elapsedRealtime()
        val calidad = (lec.nitidez * 1.5f).coerceIn(0.3f, 1.3f)
        val util = acum.agregar(lec.logp, calidad, ahora)
        mostrar(lec.caja)
        // Velocidad de la IA en este equipo (promedio), en el botón del motor
        iaMs = if (iaMs == 0f) lec.ms.toFloat() else iaMs * 0.8f + lec.ms * 0.2f
        if (ahora - ultimoTextoMotor > 1_000L) {
            ultimoTextoMotor = ahora
            opMotor.text = "${textoMotor()} · ${iaMs.toInt()} ms"
        }
        if (util || lec.confianza >= 0.5f) {
            ultimaPlacaVista = ahora
            indicioHasta = ahora + 2_500L
            if (energia != Energia.ACTIVO) {
                val estaba = energia
                energia = Energia.ACTIVO
                if (estaba == Energia.AHORRO) salirAhorro()
            }
        }
        if (ahora - ultimaMiniatura > 250L) {
            ultimaMiniatura = ahora
            try {
                imgCrop.setImageBitmap(miniaturaIA(lec))
            } catch (_: Exception) {
            }
        }
        if (!util) {
            mostrarEstado("Placa a la vista · acérquese o espere", false)
            return
        }
        decidirIA(l, ahora, proxy)
    }

    /**
     * Con lo visto en los últimos cuadros: la placa más probable según el formato y el listado de reservas.
     * Si encaja con una reserva y hay evidencia suficiente, se registra.
     */
    private fun decidirIA(l: Listado, ahoraT: Long, proxy: ImageProxy) {
        val prom = acum.promedio(ahoraT) ?: return
        val r = DecisionPlaca.decidir(prom.logp, l.placasIndexadas()) ?: return
        val libre = r.libre
        val clase = if (libre.moto) "MOTO" else "CARRO"
        val ahora = System.currentTimeMillis()
        val listada: String? = r.listada
        txtLeida.text = listada ?: libre.placa
        ultimaPista = if (libre.moto) OcrPlaca.Tipo.MOTO else OcrPlaca.Tipo.CARRO

        if (r.ambigua) {
            mostrarEstado("$clase · ${libre.placa} se parece a varias placas. Acérquese un poco.", false)
            return
        }

        var filas: List<Int> = emptyList()
        var exacta = false
        var necesario = PESO_EXACTA
        if (listada != null) {
            // Una placa del listado encaja casi tan bien como la mejor lectura posible
            filas = l.buscarPlacaOcr(listada)
            exacta = true
            necesario = if (r.puntajeListada >= DecisionPlaca.SEGURA) PESO_EXACTA else PESO_IA_DUDA
        } else if (libre.puntaje >= DecisionPlaca.LIBRE_MINIMO) {
            // Ninguna encaja por probabilidad: se prueba la lectura con las confusiones típicas (O/Q/0, I/1…)
            filas = l.buscarPlacaOcr(libre.placa)
            exacta = l.placaExacta(libre.placa)
            necesario = if (exacta) PESO_EXACTA else PESO_CORREGIDA
        }

        if (filas.isNotEmpty()) {
            if (filas.size > 1 && !exacta) {
                mostrarEstado("$clase · se parece a ${filas.size} placas. Acérquese un poco.", false)
                return
            }
            val fila = filas.firstOrNull { !l.ingresado(it) } ?: filas[0]
            val placa = l.placaNormal(fila)
            ultimaLectura = placa
            txtLeida.text = placa
            val clave = OcrPlaca.canonica(placa)
            if ((enfriamiento[clave] ?: 0L) > ahora) {
                mostrarEstado("$placa ya fue procesada", false)
                return
            }
            if (prom.peso < necesario) {
                val porcentaje = (100f * prom.peso / necesario).toInt().coerceIn(1, 99)
                mostrarEstado("$clase · confirmando $placa ($porcentaje%)", false)
                return
            }
            enfriamiento[clave] = ahora + ENFRIAMIENTO_MS
            acum.limpiar()
            voto.limpiar()
            // El tipo de vehículo sale del formato leído; solo se compara con la reserva si la lectura fue segura
            val tipoVisto = if (libre.puntaje >= DecisionPlaca.SEGURA) ultimaPista else null
            registrarDesdeCamara(fila, libre.placa, proxy, tipoVisto)
            return
        }

        // Ninguna reserva coincide
        ultimaLectura = libre.placa
        mostrarEstado("$clase · ${libre.placa} no tiene reserva", false)
        val clave = OcrPlaca.canonica(libre.placa)
        // Solo con lectura segura y varios cuadros que coinciden: evita avisos por letreros o lecturas a medias
        if (libre.puntaje >= DecisionPlaca.SEGURA && acum.coincidencias(libre.placa) >= 2 &&
            prom.peso >= PESO_ALERTA && (enfriamiento[clave] ?: 0L) <= ahora
        ) {
            enfriamiento[clave] = ahora + ENFRIAMIENTO_MS
            acum.limpiar()
            avisarSinReserva(libre.placa, clase, proxy)
        }
    }

    /** Placa que no está en el listado: guarda la alerta con foto y avisa. */
    private fun avisarSinReserva(placa: String, clase: String, proxy: ImageProxy) {
        val foto = tomarFoto(proxy)
        Operacion.alertaSinReserva(Operacion.Origen.PLACA, placa, foto)
        val via = Operacion.accionPara(Operacion.Origen.PLACA) == Operacion.Accion.VALIDAR
        vibrar(longArrayOf(0, 400))
        mostrarAviso(
            Tipo.ALERTA, "SIN RESERVA", placa,
            "$clase\n" + if (via) "No está en el listado. No debe pasar." else "Hay un vehículo adentro sin reserva.",
            via
        )
    }

    /** Zona fija de lectura (el recuadro punteado), en píxeles del cuadro derecho. */
    private fun zonaFija(uw: Int, uh: Int): Rect =
        Rect((uw * ZONA_X0).toInt(), (uh * ZONA_Y0).toInt(), (uw * ZONA_X1).toInt(), (uh * ZONA_Y1).toInt())

    /** La placa detectada con un margen, para no cortar letras en los bordes. */
    private fun ampliar(r: DetectorPlaca.Resultado, uw: Int, uh: Int): Rect {
        val mw = (r.ancho() * 0.12f).toInt()
        val mh = (r.alto() * 0.25f).toInt()
        return Rect(max(0, r.izq - mw), max(0, r.arr - mh), min(uw, r.der + mw), min(uh, r.aba + mh))
    }

    /** No hay placa a la vista: pasa de activo a espera y, si dura, a modo ahorro. */
    private fun sinPlaca(t: Long) {
        val sin = t - ultimaPlacaVista
        if (energia == Energia.ACTIVO && sin > 2_500L) energia = Energia.ESPERA
        if (energia == Energia.ESPERA && sin > ESPERA_AHORRO_MS) {
            energia = Energia.AHORRO
            main.post { entrarAhorro() }
        }
        if (sin > 1_500L) {
            main.post {
                mostrar(null)
                if (energia != Energia.AHORRO) mostrarEstado("Buscando placa…", false)
            }
        }
    }

    // ---------------------------------------------------------------- lectura del texto (hilo principal)

    private fun unir(a: Rect?, b: Rect?): Rect? {
        if (a == null) return b
        if (b == null) return a
        return Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
    }

    private fun candidatos(texto: Text): Pair<List<Cand>, Boolean> {
        val lineas = texto.textBlocks.flatMap { it.lines }
            .filter { Listado.normPlaca(it.text).length >= 2 }
            .sortedBy { it.boundingBox?.top ?: 0 }
        val salida = ArrayList<Cand>()
        lineas.forEach { salida.add(Cand(it.text, it.boundingBox)) }
        var dos = false
        // Las placas de moto traen las letras arriba y los números abajo: se unen los renglones apilados
        for (i in 0 until lineas.size - 1) {
            val a = lineas[i].boundingBox
            val b = lineas[i + 1].boundingBox
            if (a == null || b == null) continue
            val solape = min(a.right, b.right) - max(a.left, b.left)
            val ancho = min(a.width(), b.width())
            if (b.top >= a.centerY() && ancho > 0 && solape > ancho * 0.4f) {
                salida.add(Cand(lineas[i].text + " " + lineas[i + 1].text, unir(a, b)))
                val na = Listado.normPlaca(lineas[i].text).length
                val nb = Listado.normPlaca(lineas[i + 1].text).length
                if (na in 2..4 && nb in 2..4) dos = true
            }
        }
        if (lineas.size in 2..3) {
            var u: Rect? = null
            lineas.forEach { u = unir(u, it.boundingBox) }
            salida.add(Cand(lineas.joinToString(" ") { it.text }, u))
        }
        return salida to dos
    }

    /** Hay texto con pinta de placa a medias (letras y números mezclados) aunque no se haya podido leer completa. */
    private fun hayIndicio(cands: List<Cand>): Boolean = cands.any {
        val n = Listado.normPlaca(it.texto)
        n.length >= 4 && n.any { c -> c.isDigit() } && n.any { c -> c.isLetter() }
    }

    /** Nitidez del renglón leído (recorte del recorte); pesa más una lectura de una imagen enfocada. */
    private fun calidad(pase: Pase, caja: Rect?): Float {
        var nit = pase.nitidez
        if (caja != null && pase.escala > 0f) {
            val x0 = (caja.left / pase.escala).toInt().coerceIn(0, pase.gw - 1)
            val y0 = (caja.top / pase.escala).toInt().coerceIn(0, pase.gh - 1)
            val x1 = (caja.right / pase.escala).toInt().coerceIn(x0 + 1, pase.gw)
            val y1 = (caja.bottom / pase.escala).toInt().coerceIn(y0 + 1, pase.gh)
            val w = x1 - x0
            val h = y1 - y0
            if (w >= 24 && h >= 14) {
                val sub = IntArray(w * h)
                for (j in 0 until h) System.arraycopy(pase.gris, (y0 + j) * pase.gw + x0, sub, j * w, w)
                nit = ImagenPlaca.nitidez(sub, w, h)
            }
        }
        if (nit < 0f) nit = 0.6f
        return (nit * 1.5f).coerceIn(0.3f, 1.3f)
    }

    /** Pasa un rectángulo del OCR (en píxeles de la imagen que se le dio) al cuadro derecho. */
    private fun aCuadro(caja: Rect, pase: Pase): Rect {
        val k = pase.paso / pase.escala
        return Rect(
            pase.zona.left + (caja.left * k).toInt(), pase.zona.top + (caja.top * k).toInt(),
            pase.zona.left + (caja.right * k).toInt(), pase.zona.top + (caja.bottom * k).toInt()
        )
    }

    /** Se ejecuta en el hilo principal con cada imagen leída. */
    private fun alTexto(texto: Text, proxy: ImageProxy, pase: Pase) {
        val l = Operacion.listado ?: return
        val ahora = SystemClock.elapsedRealtime()
        val (cands, dosRenglones) = candidatos(texto)
        val det = pase.detectada
        val pista: OcrPlaca.Tipo? = when {
            dosRenglones -> OcrPlaca.Tipo.MOTO
            det != null -> OcrPlaca.tipoPorForma(det.relacion(), false)
            else -> null
        }
        ultimaPista = pista
        val hallazgos = cands.flatMap { c -> OcrPlaca.lecturas(c.texto, pista, dosRenglones).map { Hallazgo(it, c.caja) } }
            .distinctBy { it.lec.placa }

        if (hallazgos.isEmpty()) {
            sinLecturaSeguidas++
            if (hayIndicio(cands)) indicioHasta = ahora + 2_500L
            if (det != null) {
                fallos++
                if (fallos >= FALLOS_PARA_IGNORAR) {
                    fallos = 0
                    val m = det.ancho() / 6
                    zonaIgnorada = Rect(det.izq - m, det.arr - m, det.der + m, det.aba + m)
                    ignoradaHasta = ahora + IGNORAR_MS
                }
                fallosDet++
                mostrarEstado(etiquetaZona(det) + " · acérquese o espere", false)
            } else {
                sinPlaca(ahora)
            }
            return
        }
        sinLecturaSeguidas = 0
        fallos = 0
        fallosDet = 0
        ultimaPlacaVista = ahora
        indicioHasta = ahora + 2_500L
        if (energia != Energia.ACTIVO) {
            val estaba = energia
            energia = Energia.ACTIVO
            if (estaba == Energia.AHORRO) salirAhorro()
        }

        // Marco rojo sobre la placa leída (si el detector no la había marcado ya)
        val mejorCaja = hallazgos[0].caja
        if (det == null && mejorCaja != null) mostrar(aCuadro(mejorCaja, pase))

        // Cada lectura vota; las de imágenes nítidas y del tipo de vehículo que se ve pesan más
        for (h in hallazgos.take(3)) {
            var q = calidad(pase, h.caja)
            if (pista != null && h.lec.tipo != pista) q *= 0.7f
            voto.agregar(h.lec.placa, h.lec.correcciones, q, ahora)
        }
        val consenso = voto.mejor(ahora) ?: return
        decidir(l, consenso, proxy)
    }

    /** Con la placa que ganó la votación: buscar la reserva y, si hay evidencia suficiente, registrar. */
    private fun decidir(l: Listado, c: VotoPlacas.Consenso, proxy: ImageProxy) {
        val ahora = System.currentTimeMillis()
        val clase = if (OcrPlaca.tipoDePlaca(c.placa) == OcrPlaca.Tipo.MOTO) "MOTO" else "CARRO"
        txtLeida.text = c.placa
        val filas = l.buscarPlacaOcr(c.placa)

        if (filas.isNotEmpty()) {
            val exacta = l.placaExacta(c.placa)
            // Si la lectura se parece a varias placas del listado, se espera una lectura más clara
            if (filas.size > 1 && !exacta) {
                mostrarEstado("$clase · se parece a ${filas.size} placas. Acérquese un poco.", true)
                return
            }
            val fila = filas.firstOrNull { !l.ingresado(it) } ?: filas[0]
            val placa = l.placaNormal(fila)
            ultimaLectura = placa
            txtLeida.text = placa
            val clave = OcrPlaca.canonica(placa)
            if ((enfriamiento[clave] ?: 0L) > ahora) {
                mostrarEstado("$placa ya fue procesada", true)
                return
            }
            // Una lectura limpia y nítida basta; si hubo que corregir letras (O/Q, I/1…) se piden más cuadros que coincidan
            val necesario = if (exacta) PESO_EXACTA else PESO_CORREGIDA
            if (c.peso < necesario) {
                val porcentaje = (100f * c.peso / necesario).toInt().coerceIn(1, 99)
                mostrarEstado("$clase · confirmando $placa ($porcentaje%)", true)
                return
            }
            enfriamiento[clave] = ahora + ENFRIAMIENTO_MS
            voto.limpiar()
            registrarDesdeCamara(fila, c.placa, proxy, ultimaPista)
            return
        }

        // Ninguna reserva coincide
        ultimaLectura = c.placa
        mostrarEstado("$clase · ${c.placa} no tiene reserva", true)
        // Solo con evidencia fuerte (varias lecturas limpias): evita avisos por letreros o lecturas a medias
        val clave = OcrPlaca.canonica(c.placa)
        if (c.exactas >= 2 && c.peso >= PESO_ALERTA && (c.placa.length == 6 || c.placa.length == 5) && (enfriamiento[clave] ?: 0L) <= ahora) {
            enfriamiento[clave] = ahora + ENFRIAMIENTO_MS
            voto.limpiar()
            avisarSinReserva(c.placa, clase, proxy)
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
        val clase = if (OcrPlaca.tipoDePlaca(placa) == OcrPlaca.Tipo.MOTO) "MOTO" else "CARRO"
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
                if (resultado.aviso == null) Avisos.leida(this)   // lectura buena: pitido (con aviso, solo vibra)
                mostrarAviso(
                    if (vip) Tipo.VIP else Tipo.OK,
                    if (vip) "★ VIP · ACCESO VALIDADO" else "✓ ACCESO VALIDADO",
                    placa,
                    listOf(clase, nombre, resultado.aviso.orEmpty()).filter { it.isNotBlank() }.joinToString("\n"),
                    true
                )
                if (resultado.aviso != null) vibrar(longArrayOf(0, 150, 100, 150))
                if (Operacion.ajustes.imprimirAlValidar) {
                    Impresora.imprimir(this, Operacion.lineasValidacion(fila)) { error ->
                        if (error != null) txtEstado.text = "⚠ $error"
                    }
                } else mostrarEstado("✓ $placa validada · $clase", true)
            }
            is Operacion.Resultado.YaValidado -> {
                vibrar(longArrayOf(0, 80, 90, 80))
                val por = resultado.porEquipo?.let { " en $it" }.orEmpty()
                if (resultado.sospechoso) {
                    vibrar(longArrayOf(0, 300, 120, 300))
                    mostrarAviso(
                        Tipo.ALERTA, "⚠ YA SE VALIDÓ", placa,
                        "$nombre\nYa pasó a las ${hora.format(Date(resultado.hora))}$por. Verifique: puede ser un QR o placa repetida.",
                        via
                    )
                } else {
                    mostrarAviso(Tipo.REPETIDO, "YA VALIDADA", placa, "$nombre\nValidada a las ${hora.format(Date(resultado.hora))}$por", via)
                }
                mostrarEstado("$placa ya había sido validada", true)
            }
            is Operacion.Resultado.Ingresado -> {
                sesion++
                txtSesion.text = sesion.toString()
                if (resultado.sinValidar || resultado.aviso != null) vibrar(longArrayOf(0, 450)) else Avisos.leida(this)
                val extra = buildList {
                    add(clase)
                    add(nombre)
                    if (evento.isNotBlank()) add(evento)
                    if (resultado.sinValidar) add("⚠ No había pasado por la vía: se validó y registró aquí")
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
                val por = resultado.porEquipo?.let { " en $it" }.orEmpty()
                mostrarAviso(Tipo.REPETIDO, "YA INGRESÓ", placa, "$nombre\nIngresó a las ${hora.format(Date(resultado.hora))}$por", false)
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
        val grande = if (giro == 0) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(giro.toFloat()) }, true)
        grande
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

    private fun textoDistancia(cm: Int): String = if (cm >= 100) "${cm / 100f} m".replace(".0", "").replace('.', ',') else "$cm cm"

    private fun textoMotor(): String {
        val estadoIA = when (motorEstado) {
            0 -> " (cargando…)"
            2 -> " (no disponible)"
            else -> ""
        }
        return when (motorIA) {
            0 -> "Motor: IA + ML Kit$estadoIA"
            1 -> "Motor: solo IA$estadoIA"
            else -> "Motor: solo ML Kit"
        }
    }

    private fun refrescarOpciones() {
        opMotor.text = textoMotor()
        opMetodo.text = when (metodo) {
            0 -> "Lectura: AUTO"
            1 -> "Lectura: ZONA FIJA"
            else -> "Lectura: TODA LA IMAGEN"
        }
        opZoom.text = "Zoom ${if (zoom % 1f == 0f) zoom.toInt().toString() else zoom.toString().replace('.', ',')}x"
        opFoco.text = if (focoCm == 0) "Foco: AUTO" else "Foco fijo: ${textoDistancia(focoCm)}"
        opMovimiento.text = when (movimiento) {
            0 -> "Movimiento: NORMAL"
            1 -> "Movimiento: RÁPIDO"
            else -> "Movimiento: MANUAL"
        }
        opResolucion.text = if (altaRes) "Calidad: ALTA" else "Calidad: NORMAL"
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
        val escala = max(vw / cw, vh / ch)
        val dx = (cw * escala - vw) / 2f
        val dy = (ch * escala - vh) / 2f
        return PointF(x * escala - dx, y * escala - dy)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** El recuadro punteado: dónde debe quedar la placa. */
    private fun posicionarZona() {
        val uw = frameW
        val uh = frameH
        if (uw <= 0 || uh <= 0 || vista.width <= 0) return
        if (metodo == 2) {
            zonaView.visibility = View.GONE
            return
        }
        val a = aVista(uw * ZONA_X0, uh * ZONA_Y0, uw, uh)
        val b = aVista(uw * ZONA_X1, uh * ZONA_Y1, uw, uh)
        val lp = zonaView.layoutParams as FrameLayout.LayoutParams
        val nuevoIzq = a.x.toInt().coerceAtLeast(0)
        val nuevoArr = a.y.toInt()
        val nuevoAncho = (b.x - a.x).toInt().coerceAtLeast(dp(80))
        val nuevoAlto = (b.y - a.y).toInt().coerceAtLeast(dp(60))
        if (zonaView.visibility == View.VISIBLE && lp.leftMargin == nuevoIzq && lp.topMargin == nuevoArr &&
            lp.width == nuevoAncho && lp.height == nuevoAlto
        ) return
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = nuevoIzq
        lp.topMargin = nuevoArr
        lp.width = nuevoAncho
        lp.height = nuevoAlto
        zonaView.layoutParams = lp
        zonaView.visibility = View.VISIBLE
    }

    /** Marco rojo sobre la placa que se está leyendo (en píxeles del cuadro derecho). null lo oculta. */
    private fun mostrar(r: Rect?) {
        if (r == null) {
            if (marco.visibility != View.GONE) marco.visibility = View.GONE
            return
        }
        val uw = frameW
        val uh = frameH
        if (uw <= 0 || uh <= 0) return
        val a = aVista(r.left.toFloat(), r.top.toFloat(), uw, uh)
        val b = aVista(r.right.toFloat(), r.bottom.toFloat(), uw, uh)
        val lp = marco.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = a.x.toInt()
        lp.topMargin = a.y.toInt()
        lp.width = (b.x - a.x).toInt().coerceAtLeast(dp(40))
        lp.height = (b.y - a.y).toInt().coerceAtLeast(dp(30))
        marco.layoutParams = lp
        marco.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- ahorro de batería

    private fun entrarAhorro() {
        if (isFinishing) return
        brillo(0.05f)
        if (linterna) {
            camara?.cameraControl?.enableTorch(false)
            linterna = false
        }
        mostrar(null)
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
        mostrarEstado("Ponga la placa dentro del recuadro", true)
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
