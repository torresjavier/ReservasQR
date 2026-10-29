package com.javier.reservas

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class Estilo { OK, VIP, AVISO, ERROR, INFO }

    /** Selector de archivos que puede abrir directo en la carpeta de Bluetooth. */
    private class AbrirDocumento : ActivityResultContracts.OpenDocument() {
        var inicial: Uri? = null
        override fun createIntent(context: Context, input: Array<String>): Intent {
            val i = super.createIntent(context, input)
            val u = inicial
            if (u != null && Build.VERSION.SDK_INT >= 26) i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, u)
            return i
        }
    }

    private val ajustes: Ajustes get() = Operacion.ajustes
    private val listado: Listado? get() = Operacion.listado

    private lateinit var header: View
    private lateinit var avatar: TextView
    private lateinit var imgAvatar: ImageView
    private lateinit var txtOperador: TextView
    private lateinit var txtSubtitulo: TextView
    private lateinit var chipModo: TextView
    private lateinit var chipAlertas: TextView
    private lateinit var segValidados: TextView
    private lateinit var banner: View
    private lateinit var bannerTitulo: TextView
    private lateinit var bannerPlaca: TextView
    private lateinit var bannerDetalle: TextView
    private lateinit var chipImpresora: TextView
    private lateinit var chipLector: TextView
    private lateinit var chipEnvio: TextView
    private lateinit var chipRed: TextView
    private lateinit var txtContador: TextView
    private lateinit var txtTotal: TextView
    private lateinit var txtFaltan: TextView
    private lateinit var progreso: com.google.android.material.progressindicator.LinearProgressIndicator
    private lateinit var edtPlaca: EditText
    private lateinit var btnLimpiar: View
    private lateinit var segPendientes: TextView
    private lateinit var segIngresados: TextView
    private lateinit var txtVacio: TextView
    private lateinit var panelBloqueo: View
    private lateinit var txtBloqueo: TextView
    private lateinit var btnDesbloquear: Button
    private lateinit var adaptador: ReservaAdapter

    private enum class Vista { PENDIENTES, VALIDADOS, INGRESADOS }
    private var verLista = Vista.PENDIENTES
    /** El PIN de configuración ya se escribió: no se vuelve a pedir hasta esta hora. */
    private var pinValidoHasta = 0L
    private var pinFallos = 0
    private var pinBloqueadoHasta = 0L
    private var despuesDeRestablecer: (() -> Unit)? = null
    private val ocultarBanner = Runnable { banner.visibility = View.GONE }
    private var dialogoActual: AlertDialog? = null
    /** Junta lo que manda el lector Sunmi (teclado y aviso del sistema) para procesar cada código una sola vez y completo. */
    private val captura = CapturaLector()
    /** Lo que había en el cuadro de búsqueda antes de que el lector empezara a «escribir» en él. */
    private var textoAntesDeLector: String? = null
    /** Lectura completa que llegó por teclado, esperando un instante por si llega el aviso exacto del sistema. */
    private var tecladoPendiente: String? = null
    private val procesarTecladoPendiente = Runnable {
        val t = tecladoPendiente ?: return@Runnable
        tecladoPendiente = null
        if (!Bloqueo.requiereClave && captura.aceptar(t, SystemClock.uptimeMillis())) procesarLectura(t.trim())
    }
    private var uriPendiente: Uri? = null
    private var ayudaInicialMostrada = false
    /** El operador canceló la clave: se espera a que toque «Desbloquear». */
    private var esperarBoton = false
    private val handler = Handler(Looper.getMainLooper())
    private val co = Locale("es", "CO")
    private val fmtHora = SimpleDateFormat("hh:mm a", co)
    private val fmtFecha = SimpleDateFormat("dd/MM/yyyy hh:mm a", co)
    private val alCambiarDatos: () -> Unit = { refrescar() }
    private val alCambiarRed: () -> Unit = { refrescarEstado() }

    // ---------------------------------------------------------------- lanzadores

    private val contratoAbrir = AbrirDocumento()
    private val abrirArchivo = registerForActivityResult(contratoAbrir) { uri ->
        if (uri != null) importar(uri)
    }

    private val escanear = registerForActivityResult(ScanContract()) { r ->
        val c = r.contents
        if (c != null) procesarQr(c)
    }

    private val pedirCamara = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) lanzarEscaner()
        else mostrarError("Sin permiso de cámara", "Active el permiso de cámara en Ajustes → Aplicaciones → Reservas QR → Permisos.")
    }

    private val pedirCamaraPlaca = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) abrirCamaraPlaca()
        else mostrarError("Sin permiso de cámara", "Active el permiso de cámara en Ajustes → Aplicaciones → Reservas QR → Permisos.")
    }

    private val leerPlaca = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val escribir = r.data?.getStringExtra(PlacaActivity.EXTRA_ESCRIBIR)
        if (r.resultCode == RESULT_OK && escribir != null) {
            edtPlaca.setText(escribir)
            enfocarBuscador()
        }
    }

    /** Android 13+: sin este permiso el servicio de la red funciona igual, pero no se ve su aviso fijo. */
    private val pedirNotificaciones = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val pedirBt = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) hacerVisibleAhora()
        else mostrarError("Sin permiso de Bluetooth", "Active el permiso «Dispositivos cercanos» para Reservas QR en Ajustes → Aplicaciones → Permisos.")
    }

    private val hacerVisible = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != RESULT_CANCELED) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Bluetooth listo")
                .setMessage("Este equipo está visible por 5 minutos.\n\nDesde el otro teléfono: abra el Excel → Compartir → Bluetooth → elija este equipo.\n\nCuando llegue, toque la notificación «Archivo recibido» para abrirlo con Reservas QR, o use «Abrir recibido».")
                .setPositiveButton("Abrir recibido") { _, _ -> abrirRecibido() }
                .setNegativeButton("Cerrar", null)
                .show()
        } else toast("No se activó la visibilidad por Bluetooth.")
    }

    private val elegirFoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) guardarFotoPerfil(uri)
    }

    private val confirmarClave = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        Bloqueo.autenticando = false
        if (r.resultCode == RESULT_OK) {
            Bloqueo.desbloqueado = true
            comprobarBloqueo()
        } else {
            esperarBoton = true
            txtBloqueo.text = "No se desbloqueó. Toque «Desbloquear» e ingrese la clave del equipo."
        }
    }

    /** «Olvidé el PIN»: se confirma con la clave de desbloqueo del equipo y se crea uno nuevo. */
    private val restablecerPin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        Bloqueo.autenticando = false
        if (r.resultCode == RESULT_OK) {
            ajustes.borrarPin()
            val seguir = despuesDeRestablecer
            despuesDeRestablecer = null
            crearPin(seguir)
        } else {
            despuesDeRestablecer = null
            toast("No se restableció el PIN.")
        }
    }

    /** Lecturas del lector Sunmi cuando está en modo «broadcast». */
    private val receptorEscaner = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val datos = intent?.getStringExtra(EscanerSunmi.EXTRA_DATOS) ?: return
            alAvisoDelLector(datos)
        }
    }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Operacion.iniciar(this)
        Bloqueo.instalar()

        header = findViewById(R.id.header)
        avatar = findViewById(R.id.avatar)
        imgAvatar = findViewById(R.id.imgAvatar)
        txtOperador = findViewById(R.id.txtOperador)
        txtSubtitulo = findViewById(R.id.txtSubtitulo)
        chipModo = findViewById(R.id.chipModo)
        chipAlertas = findViewById(R.id.chipAlertas)
        segValidados = findViewById(R.id.segValidados)
        banner = findViewById(R.id.bannerRapido)
        bannerTitulo = findViewById(R.id.bannerTitulo)
        bannerPlaca = findViewById(R.id.bannerPlaca)
        bannerDetalle = findViewById(R.id.bannerDetalle)
        chipImpresora = findViewById(R.id.chipImpresora)
        chipLector = findViewById(R.id.chipLector)
        chipEnvio = findViewById(R.id.chipEnvio)
        chipRed = findViewById(R.id.chipRed)
        txtContador = findViewById(R.id.txtContador)
        txtTotal = findViewById(R.id.txtTotal)
        txtFaltan = findViewById(R.id.txtFaltan)
        progreso = findViewById(R.id.progreso)
        edtPlaca = findViewById(R.id.edtPlaca)
        btnLimpiar = findViewById(R.id.btnLimpiar)
        segPendientes = findViewById(R.id.segPendientes)
        segIngresados = findViewById(R.id.segIngresados)
        txtVacio = findViewById(R.id.txtVacio)
        panelBloqueo = findViewById(R.id.panelBloqueo)
        txtBloqueo = findViewById(R.id.txtBloqueo)
        btnDesbloquear = findViewById(R.id.btnDesbloquear)

        val dock = findViewById<View>(R.id.dock)
        val padTop = header.paddingTop
        val padDock = dock.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            header.setPadding(header.paddingLeft, padTop + bars.top, header.paddingRight, header.paddingBottom)
            dock.setPadding(dock.paddingLeft, dock.paddingTop, dock.paddingRight, padDock + maxOf(bars.bottom, ime.bottom))
            panelBloqueo.setPadding(panelBloqueo.paddingLeft, bars.top, panelBloqueo.paddingRight, bars.bottom)
            insets
        }

        adaptador = ReservaAdapter { mostrarDetalle(it) }
        findViewById<RecyclerView>(R.id.lista).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = adaptador
            setHasFixedSize(true)
        }

        // Accesos rápidos
        findViewById<View>(R.id.btnDockQr).setOnClickListener { iniciarEscaneo() }
        findViewById<View>(R.id.btnDockQr).setOnLongClickListener { iniciarEscaneo(conCamara = true); true }
        findViewById<View>(R.id.btnDockPlaca).setOnClickListener { leerPlacaCamara() }
        findViewById<View>(R.id.btnDockBuscar).setOnClickListener { enfocarBuscador() }
        findViewById<View>(R.id.btnMenu).setOnClickListener { mostrarMenu() }
        findViewById<View>(R.id.zonaAvatar).setOnClickListener { opcionesPerfil() }
        findViewById<View>(R.id.zonaOperador).setOnClickListener { mostrarAjustes() }
        chipImpresora.setOnClickListener { enviarAImpresora(Operacion.lineasPrueba(), null) }
        chipLector.setOnClickListener { iniciarEscaneo() }
        chipEnvio.setOnClickListener { enviarAhora() }
        chipRed.setOnClickListener { mostrarEquiposCercanos() }
        segPendientes.setOnClickListener { verLista = Vista.PENDIENTES; refrescarLista() }
        segValidados.setOnClickListener { verLista = Vista.VALIDADOS; refrescarLista() }
        segIngresados.setOnClickListener { verLista = Vista.INGRESADOS; refrescarLista() }
        banner.setOnClickListener { banner.visibility = View.GONE }
        chipModo.setOnClickListener { explicarModo() }
        chipAlertas.setOnClickListener { mostrarAlertas() }
        btnLimpiar.setOnClickListener { edtPlaca.setText("") }
        btnDesbloquear.setOnClickListener { esperarBoton = false; pedirClave() }

        edtPlaca.doAfterTextChanged {
            btnLimpiar.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE
            refrescarLista()
        }
        edtPlaca.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE) {
                ocultarTeclado()
                val l = listado
                val q = edtPlaca.text?.toString().orEmpty()
                if (l != null && q.isNotBlank()) {
                    val r = l.buscarTexto(q, 2)
                    if (r.size == 1) mostrarDetalle(r[0])
                }
                true
            } else false
        }

        Impresora.alCambiar = { refrescarEstado() }
        Impresora.conectar(this)
        EscanerSunmi.alCambiar = { runOnUiThread { refrescarEstado() } }
        EscanerSunmi.conectar(this)
        Sincronizacion.programar(this)
        Operacion.escuchar(alCambiarDatos)
        RedLocal.escuchar(alCambiarRed)

        cargarFotoPerfil()
        refrescar()
        if (savedInstanceState == null) manejarIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Operacion.dejarDeEscuchar(alCambiarDatos)
        RedLocal.dejarDeEscuchar(alCambiarRed)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        manejarIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Impresora.conectar(this)
        EscanerSunmi.conectar(this)
        try {
            ContextCompat.registerReceiver(
                this, receptorEscaner, IntentFilter(EscanerSunmi.ACCION_DATOS), ContextCompat.RECEIVER_EXPORTED
            )
        } catch (_: Exception) {
        }
        comprobarBloqueo()
        refrescar()
        iniciarRedEntreEquipos()
    }

    /** Enciende la comunicación con los equipos cercanos (si está activada en Ajustes) y deja el servicio en segundo plano. */
    private fun iniciarRedEntreEquipos() {
        if (!ajustes.redActiva) return
        RedLocal.arrancar()
        RedLocal.iniciarServicio(this)
        if (Build.VERSION.SDK_INT >= 33 && !ajustes.pidioNotificaciones &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ajustes.pidioNotificaciones = true
            try {
                pedirNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (_: Exception) {
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(receptorEscaner)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- bloqueo con la clave del equipo

    private fun comprobarBloqueo() {
        if (Bloqueo.requiereClave) {
            panelBloqueo.visibility = View.VISIBLE
            ocultarTeclado()
            if (!Bloqueo.autenticando && !esperarBoton) pedirClave()
        } else {
            panelBloqueo.visibility = View.GONE
            despuesDeDesbloquear()
        }
    }

    @Suppress("DEPRECATION")
    private fun pedirClave() {
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isDeviceSecure) {
            txtBloqueo.text = "Este equipo no tiene clave de bloqueo. Para proteger la app, configure un PIN, patrón o contraseña en los ajustes del equipo."
            btnDesbloquear.text = "Configurar clave del equipo"
            btnDesbloquear.setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            return
        }
        btnDesbloquear.text = "Desbloquear"
        btnDesbloquear.setOnClickListener { esperarBoton = false; pedirClave() }
        txtBloqueo.text = "Ingrese la clave de desbloqueo del equipo para continuar."
        val i = km.createConfirmDeviceCredentialIntent("Reservas QR", "Ingrese la clave del equipo para entrar")
        if (i == null) {
            Bloqueo.desbloqueado = true
            comprobarBloqueo()
            return
        }
        Bloqueo.autenticando = true
        try {
            confirmarClave.launch(i)
        } catch (e: Exception) {
            Bloqueo.autenticando = false
        }
    }

    private fun despuesDeDesbloquear() {
        uriPendiente?.let { u ->
            uriPendiente = null
            confirmarSiHayAvance { importar(u) }
            return
        }
        if (listado == null && !ayudaInicialMostrada) {
            ayudaInicialMostrada = true
            handler.postDelayed({ if (listado == null && !Bloqueo.requiereClave) mostrarColumnas(false) }, 300)
        }
    }

    // ---------------------------------------------------------------- lector Sunmi (teclado / aviso del sistema)

    /**
     * El lector Sunmi puede entregar cada código de dos maneras a la vez: como «teclado» (letra por letra y Enter)
     * y como aviso del sistema con el texto completo. [CapturaLector] las junta para que cada código se procese
     * una sola vez y completo; aquí solo se traducen las teclas de Android.
     *
     * Las teclas del lector se atienden también cuando hay una ventana abierta (ver [atenderLector]): si no, el Enter
     * del final le llega a la ventana y la cierra, y las letras sueltas se pierden.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Bloqueo.requiereClave) return super.dispatchKeyEvent(event)
        if (tratarTeclaLector(event, currentFocus is EditText)) return true
        return super.dispatchKeyEvent(event)
    }

    /** @return true si la tecla es del lector y ya se atendió (no debe llegar al campo de texto ni a los botones). */
    private fun tratarTeclaLector(event: KeyEvent, enCampo: Boolean): Boolean {
        val t = event.eventTime
        val tecla = event.keyCode
        if (tecla == KeyEvent.KEYCODE_ENTER || tecla == KeyEvent.KEYCODE_NUMPAD_ENTER || tecla == KeyEvent.KEYCODE_TAB) {
            val fin = captura.terminador(event.action == KeyEvent.ACTION_DOWN, t, enCampo)
            val texto = fin.texto
            if (texto != null) {
                textoAntesDeLector = null
                alTecladoCompleto(texto)
            }
            return fin.absorber
        }
        var ch = event.unicodeChar
        if (ch < 0) ch = ch and 0x7FFFFFFF
        if (ch <= 0 || Character.isISOControl(ch)) return false
        if (event.action == KeyEvent.ACTION_UP) return captura.absorberSoltar(t, enCampo)
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (captura.caracter(ch, t, enCampo)) {
            CapturaLector.PASAR -> {
                // Todavía parece escritura a mano: pasa al campo, pero se guarda cómo estaba por si resulta ser el lector
                if (captura.largo() == 1) textoAntesDeLector = (currentFocus as? EditText)?.text?.toString()
                false
            }
            CapturaLector.ABSORBER_Y_LIMPIAR -> {
                quitarLecturaDelCampo()
                true
            }
            else -> true
        }
    }

    /** Deja el cuadro de búsqueda como estaba antes de que el lector le metiera las primeras letras del código. */
    private fun quitarLecturaDelCampo() {
        val antes = textoAntesDeLector ?: return
        textoAntesDeLector = null
        val campo = currentFocus as? EditText ?: return
        campo.setText(antes)
        campo.setSelection(campo.text.length)
    }

    /** Hace que una ventana (diálogo) también deje pasar las teclas del lector en vez de reaccionar a ellas. */
    private fun AlertDialog.atenderLector(): AlertDialog {
        setOnKeyListener { _, _, ev -> !Bloqueo.requiereClave && tratarTeclaLector(ev, false) }
        return this
    }

    /** Código completo escrito por el lector como teclado: se espera un instante por si llega el aviso exacto del sistema. */
    private fun alTecladoCompleto(texto: String) {
        tecladoPendiente = texto
        handler.removeCallbacks(procesarTecladoPendiente)
        handler.postDelayed(procesarTecladoPendiente, CapturaLector.ESPERA_TECLADO_MS)
    }

    /** Aviso del sistema: trae el texto completo y exacto, así que manda sobre la copia por teclado. */
    private fun alAvisoDelLector(datos: String) {
        if (Bloqueo.requiereClave) return
        val ahora = SystemClock.uptimeMillis()
        handler.removeCallbacks(procesarTecladoPendiente)
        tecladoPendiente = null
        if (captura.hayTeclasRecientes(ahora)) quitarLecturaDelCampo()
        if (captura.alAviso(datos, ahora)) procesarLectura(datos.trim())
    }

    private fun procesarLectura(t: String) {
        if (t.isEmpty()) return
        val enBuscador = edtPlaca.text?.toString().orEmpty()
        if (enBuscador.contains(t)) edtPlaca.setText(enBuscador.replace(t, "").trim())
        if (listado == null) {
            toast("Primero cargue el listado de reservas."); return
        }
        procesarQr(t)
    }

    // ---------------------------------------------------------------- recibir archivo de otra app

    private fun manejarIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) {
            intent.action = Intent.ACTION_MAIN
            if (Bloqueo.requiereClave) uriPendiente = uri
            else confirmarSiHayAvance { importar(uri) }
        }
    }

    // ---------------------------------------------------------------- QR

    private fun iniciarEscaneo(conCamara: Boolean = false) {
        if (listado == null) {
            faltaListado(); return
        }
        if (!conCamara && ajustes.usarLectorSunmi && EscanerSunmi.disponible) {
            dialogoActual?.dismiss()
            if (EscanerSunmi.escanear()) {
                toast("Lector encendido: apunte la parte de arriba del equipo al QR")
                return
            }
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) lanzarEscaner()
        else pedirCamara.launch(Manifest.permission.CAMERA)
    }

    private fun lanzarEscaner() {
        val l = listado ?: return
        val o = ScanOptions()
        o.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        o.setPrompt("Apunte al QR de la reserva   ·   ${l.ingresos.size} de ${l.filas.size} ingresados")
        o.setBeepEnabled(true)
        o.setOrientationLocked(false)
        o.setBarcodeImageEnabled(false)
        escanear.launch(o)
    }

    private fun procesarQr(texto: String) {
        val l = listado ?: return
        val candidatas = l.buscarQr(texto)
        if (candidatas.isEmpty()) {
            vibrar(600)
            tonoError()
            // Alguien sin reserva en el listado: queda como alerta (y se envía al servidor)
            Operacion.alertaSinReserva(Operacion.Origen.QR, texto.trim(), null)
            if (Operacion.accionPara(Operacion.Origen.QR) == Operacion.Accion.VALIDAR) {
                mostrarBanner(Estilo.ERROR, "✗ SIN RESERVA", null, texto.trim(), "El código no está en el listado. No debe pasar.")
            } else {
                mostrarTarjeta(
                    Estilo.ERROR, "✗ NO ESTÁ EN EL LISTADO", null, texto.trim(),
                    secundario = accion("Buscar placa") { d, _ -> d.dismiss(); enfocarBuscador() },
                    principal = accion("Escanear otro") { d, _ -> d.dismiss(); iniciarEscaneo() }
                )
            }
            return
        }
        val libre = candidatas.firstOrNull { !l.ingresado(it) }
        registrar(libre ?: candidatas.first(), Operacion.Origen.QR)
    }

    // ---------------------------------------------------------------- placa con la cámara

    private fun leerPlacaCamara() {
        if (listado == null) {
            faltaListado(); return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) abrirCamaraPlaca()
        else pedirCamaraPlaca.launch(Manifest.permission.CAMERA)
    }

    private fun abrirCamaraPlaca() {
        dialogoActual?.dismiss()
        leerPlaca.launch(Intent(this, PlacaActivity::class.java))
    }

    // ---------------------------------------------------------------- registrar / mostrar

    /**
     * Valida o registra según el modo del equipo (Ajustes). En la vía (validar) la respuesta es un aviso rápido
     * que se quita solo, para no detener la operación; adentro (ingresar) se muestra la tarjeta con el ticket.
     */
    private fun registrar(
        fila: Int,
        origen: Operacion.Origen,
        forzar: Boolean = false,
        modo: Operacion.Accion? = null
    ) {
        val l = listado ?: return
        val vip = l.esVip(fila)
        val resultado = try {
            Operacion.procesar(fila, origen, null, null, null, forzar, modo)
        } catch (e: Exception) {
            mostrarError("No se pudo registrar", e.message ?: e.toString())
            return
        }
        when (resultado) {
            is Operacion.Resultado.Validado -> {
                vibrar(if (resultado.aviso != null) 300 else 80)
                mostrarBanner(
                    if (vip) Estilo.VIP else Estilo.OK,
                    if (vip) "★ VIP · ACCESO VALIDADO" else "✓ ACCESO VALIDADO",
                    fila, null, resultado.aviso
                )
                if (ajustes.imprimirAlValidar) {
                    Impresora.imprimir(this, Operacion.lineasValidacion(fila)) { error ->
                        if (error != null) toast("No se imprimió: $error")
                    }
                }
                if (origen == Operacion.Origen.BUSQUEDA) edtPlaca.setText("")
            }
            is Operacion.Resultado.YaValidado -> {
                val por = resultado.porEquipo?.let { " en $it" }.orEmpty()
                if (resultado.sospechoso) {
                    vibrar(500)
                    tonoError()
                    mostrarBanner(
                        Estilo.ERROR, "⚠ YA SE VALIDÓ", fila, null,
                        "Pasó a las ${fmtHora.format(Date(resultado.hora))}$por. Verifique: puede ser un QR o una placa repetida."
                    )
                } else {
                    vibrar(200)
                    mostrarBanner(
                        Estilo.AVISO, "YA VALIDADA · ${fmtHora.format(Date(resultado.hora))}", fila, null,
                        resultado.porEquipo?.let { "Validada en $it" }
                    )
                }
            }
            is Operacion.Resultado.Ingresado -> {
                vibrar(if (resultado.sinValidar) 400 else 80)
                val auto = ajustes.autoImprimir
                val notas = listOfNotNull(
                    if (resultado.sinValidar) "⚠ No pasó por la vía antes de ingresar." else null,
                    resultado.aviso
                ).joinToString("\n").ifBlank { null }
                mostrarTarjeta(
                    if (vip) Estilo.VIP else Estilo.OK,
                    if (vip) "★ VIP · RESERVA INGRESADA" else "✓ RESERVA INGRESADA",
                    fila, null,
                    secundario = accion(if (auto) "Reimprimir" else "Imprimir ticket") { _, est -> imprimir(fila, auto, est) },
                    principal = siguiente(origen),
                    imprimirAhora = auto,
                    nota = notas
                )
            }
            is Operacion.Resultado.YaIngreso -> {
                vibrar(250)
                mostrarTarjeta(
                    Estilo.AVISO, "YA INGRESÓ · ${fmtHora.format(Date(resultado.hora))}", fila, null,
                    secundario = accion("Reimprimir") { _, est -> imprimir(fila, true, est) },
                    principal = siguiente(origen),
                    nota = resultado.porEquipo?.let { "Lo registró $it." }
                )
            }
            is Operacion.Resultado.SinValidacion -> {
                vibrar(600)
                tonoError()
                mostrarTarjeta(
                    Estilo.ERROR, "✗ NO PASÓ POR LA VÍA", fila, null,
                    secundario = accion("Cancelar") { d, _ -> d.dismiss() },
                    principal = accion("Registrar de todas formas") { d, _ -> d.dismiss(); registrar(fila, origen, true, modo) },
                    nota = "Esta reserva no fue validada en la vía. Posible intento de colarse."
                )
            }
        }
    }

    /** Aviso grande que se quita solo: confirma una lectura sin obligar a tocar nada. */
    private fun mostrarBanner(estilo: Estilo, titulo: String, fila: Int?, textoLibre: String?, extra: String?) {
        val l = listado
        val (fondo, texto) = when (estilo) {
            Estilo.OK -> R.drawable.aviso_ok to R.color.negro
            Estilo.VIP -> R.drawable.aviso_vip to R.color.negro
            Estilo.AVISO, Estilo.INFO -> R.drawable.aviso_repetido to R.color.blanco
            Estilo.ERROR -> R.drawable.aviso_error to R.color.blanco
        }
        banner.setBackgroundResource(fondo)
        val c = getColor(texto)
        bannerTitulo.setTextColor(c)
        bannerDetalle.setTextColor(c)
        bannerTitulo.text = titulo
        val placa = if (l != null && fila != null) l.valor(fila, Listado.Campo.PLACA).uppercase() else ""
        bannerPlaca.text = placa
        bannerPlaca.visibility = if (placa.isBlank()) View.GONE else View.VISIBLE
        val detalle = listOfNotNull(
            if (l != null && fila != null) l.valor(fila, Listado.Campo.NOMBRE).ifBlank { l.titulo(fila) } else textoLibre,
            if (l != null && fila != null) l.valor(fila, Listado.Campo.EVENTO).takeIf { it.isNotBlank() } else null,
            extra
        ).filter { it.isNotBlank() }.joinToString("\n")
        bannerDetalle.text = detalle
        bannerDetalle.visibility = if (detalle.isBlank()) View.GONE else View.VISIBLE
        banner.visibility = View.VISIBLE
        handler.removeCallbacks(ocultarBanner)
        handler.postDelayed(ocultarBanner, if (estilo == Estilo.ERROR) 5_000L else 1_600L)
    }

    private fun accion(texto: String, f: (AlertDialog, TextView) -> Unit): Pair<String, (AlertDialog, TextView) -> Unit> = texto to f

    private fun siguiente(origen: Operacion.Origen): Pair<String, (AlertDialog, TextView) -> Unit> = when (origen) {
        Operacion.Origen.BUSQUEDA -> accion("Listo") { d, _ -> d.dismiss(); edtPlaca.setText("") }
        Operacion.Origen.PLACA -> accion("Leer otra placa") { d, _ -> d.dismiss(); leerPlacaCamara() }
        Operacion.Origen.QR -> accion("Escanear otro") { d, _ -> d.dismiss(); iniciarEscaneo() }
    }

    private fun mostrarDetalle(fila: Int) {
        val l = listado ?: return
        ocultarTeclado()
        if (l.ingresado(fila)) {
            mostrarTarjeta(
                Estilo.INFO, "INGRESADO · ${fmtHora.format(Date(l.ingresos[fila] ?: 0L))}", fila, null,
                secundario = accion("Anular ingreso") { d, _ -> d.dismiss(); confirmarAnular(fila) },
                principal = accion("Reimprimir") { _, est -> imprimir(fila, true, est) }
            )
        } else if (l.validado(fila)) {
            val min = Operacion.minutosEsperando(fila)
            mostrarTarjeta(
                if (min >= ajustes.minutosEspera) Estilo.ERROR else Estilo.INFO,
                "VALIDADA EN LA VÍA · ${fmtHora.format(Date(l.validaciones[fila] ?: 0L))}", fila, null,
                secundario = accion("Anular validación") { d, _ -> d.dismiss(); confirmarAnular(fila) },
                principal = accion("Registrar ingreso") { d, _ ->
                    d.dismiss()
                    registrar(fila, Operacion.Origen.BUSQUEDA, modo = Operacion.Accion.INGRESAR)
                },
                nota = if (min >= 1) "Lleva $min min esperando sin recibir ticket adentro." else null
            )
        } else {
            val validar = Operacion.accionPara(Operacion.Origen.BUSQUEDA) == Operacion.Accion.VALIDAR
            mostrarTarjeta(
                Estilo.INFO, "RESERVA PENDIENTE", fila, null,
                secundario = accion("Cerrar") { d, _ -> d.dismiss() },
                principal = accion(if (validar) "Validar acceso" else "Registrar ingreso") { d, _ ->
                    d.dismiss(); registrar(fila, Operacion.Origen.BUSQUEDA)
                }
            )
        }
    }

    private fun confirmarAnular(fila: Int) {
        val l = listado ?: return
        val soloValidada = !l.ingresado(fila) && l.validado(fila)
        MaterialAlertDialogBuilder(this)
            .setTitle(if (soloValidada) "¿Anular la validación?" else "¿Anular el ingreso?")
            .setMessage("${l.titulo(fila)} volverá a quedar como ${if (soloValidada || !l.validado(fila)) "pendiente" else "validada en la vía"}. Se avisará al servidor.")
            .setPositiveButton("Anular") { _, _ -> conPin { Operacion.anular(fila) } }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrarTarjeta(
        estilo: Estilo,
        titulo: String,
        fila: Int?,
        textoLibre: String?,
        secundario: Pair<String, (AlertDialog, TextView) -> Unit>,
        principal: Pair<String, (AlertDialog, TextView) -> Unit>,
        imprimirAhora: Boolean = false,
        nota: String? = null
    ) {
        val l = listado
        val v = layoutInflater.inflate(R.layout.dialog_resultado, null)
        val tTitulo = v.findViewById<TextView>(R.id.resTitulo)
        val tFoto = v.findViewById<ImageView>(R.id.resFoto)
        val tPlaca = v.findViewById<TextView>(R.id.resPlaca)
        val tVip = v.findViewById<TextView>(R.id.resVip)
        val tNombre = v.findViewById<TextView>(R.id.resNombre)
        val tDetalle = v.findViewById<TextView>(R.id.resDetalle)
        val tImpresion = v.findViewById<TextView>(R.id.resImpresion)
        val bSec = v.findViewById<Button>(R.id.resSecundario)
        val bPri = v.findViewById<Button>(R.id.resPrincipal)

        tTitulo.text = titulo
        val (fondo, texto) = when (estilo) {
            Estilo.OK -> R.color.blanco to R.color.negro
            Estilo.VIP -> R.color.rojo to R.color.blanco
            Estilo.AVISO -> R.color.negro to R.color.blanco
            Estilo.ERROR -> R.color.rojo to R.color.blanco
            Estilo.INFO -> R.color.tarjeta_borde to R.color.blanco
        }
        tTitulo.setBackgroundColor(getColor(fondo))
        tTitulo.setTextColor(getColor(texto))

        if (l != null && fila != null) {
            val placa = l.valor(fila, Listado.Campo.PLACA)
            tPlaca.text = placa.uppercase()
            tPlaca.visibility = if (placa.isBlank()) View.GONE else View.VISIBLE
            tVip.visibility = if (l.esVip(fila)) View.VISIBLE else View.GONE
            tNombre.text = l.valor(fila, Listado.Campo.NOMBRE).ifBlank { l.titulo(fila) }
            val det = mutableListOf<String>()
            nota?.let { det += it }
            l.valor(fila, Listado.Campo.EVENTO).takeIf { it.isNotBlank() }?.let { det += "Evento: $it" }
            l.valor(fila, Listado.Campo.CODIGO).takeIf { it.isNotBlank() }?.let { det += "Reserva: $it" }
            l.valor(fila, Listado.Campo.TIPO).takeIf { it.isNotBlank() }?.let { det += "Tipo: ${it.uppercase()}" }
            l.validaciones[fila]?.let { det += "Validado en la vía: ${fmtFecha.format(Date(it))}" }
            l.ingresos[fila]?.let { det += "Ingreso: ${fmtFecha.format(Date(it))}" }
            Operacion.infoVal[fila]?.let { i ->
                if (i.placaLeida.isNotBlank()) det += "Placa leída en la vía: ${i.placaLeida}"
                Operacion.equipoDe(i)?.let { det += "Validado por el equipo: $it" }
            }
            Operacion.info[fila]?.let { i ->
                val como = when {
                    Operacion.equipoDe(i) != null -> "el equipo ${Operacion.equipoDe(i)}"
                    i.origen == Operacion.Origen.PLACA.api -> "cámara (placa leída: ${i.placaLeida})"
                    i.origen == Operacion.Origen.QR.api -> "QR"
                    else -> "búsqueda manual"
                }
                det += "Registrado por: $como"
            }
            val usadas = l.columnas.filter { it >= 0 }.toSet()
            l.encabezados.forEachIndexed { i, h ->
                val valor = l.filas[fila][i]
                if (i !in usadas && valor.isNotBlank()) det += "$h: $valor"
            }
            tDetalle.text = det.joinToString("\n")
            Operacion.foto(fila)?.let { f ->
                val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 })
                if (b != null) {
                    tFoto.setImageBitmap(b)
                    tFoto.visibility = View.VISIBLE
                }
            }
        } else {
            tPlaca.visibility = View.GONE
            tVip.visibility = View.GONE
            tNombre.text = textoLibre.orEmpty()
            tDetalle.text = "Este código no corresponde a ninguna reserva del listado.\nPuede buscar la placa manualmente."
        }
        tImpresion.visibility = View.GONE

        bSec.text = secundario.first
        bPri.text = principal.first
        val d = MaterialAlertDialogBuilder(this).setView(v).create().atenderLector()
        bSec.setOnClickListener { secundario.second(d, tImpresion) }
        bPri.setOnClickListener { principal.second(d, tImpresion) }
        dialogoActual?.dismiss()
        dialogoActual = d
        d.show()

        if (imprimirAhora && fila != null) imprimir(fila, false, tImpresion)
    }

    // ---------------------------------------------------------------- imprimir

    private fun imprimir(fila: Int, reimpresion: Boolean, estado: TextView?) {
        enviarAImpresora(Operacion.lineasTicket(fila, reimpresion), estado)
    }

    private fun enviarAImpresora(lineas: List<Linea>, estado: TextView?) {
        val sunmi = Impresora.esSunmi
        estado?.visibility = View.VISIBLE
        estado?.text = if (sunmi) "Imprimiendo ticket…" else "Abriendo impresión de Android…"
        if (estado == null && sunmi) toast("Imprimiendo ticket de prueba…")
        Impresora.imprimir(this, lineas) { error ->
            if (error == null) {
                estado?.text = if (sunmi) "✓ Ticket impreso. Entréguelo al cliente." else "Ticket enviado a la impresión de Android."
            } else if (estado != null) {
                estado.text = "⚠ $error"
                estado.setTextColor(getColor(R.color.rojo))
            } else mostrarError("No se imprimió", error)
        }
    }

    // ---------------------------------------------------------------- menú de opciones

    private fun mostrarMenu() {
        val hoja = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_menu, null)
        val cont = v.findViewById<LinearLayout>(R.id.menuLista)
        val pendientes = Bandeja.cantidad(this)

        fun item(icono: Int, titulo: String, detalle: String, rojo: Boolean = false, accionItem: () -> Unit) {
            val fila = layoutInflater.inflate(R.layout.item_menu, cont, false)
            fila.findViewById<ImageView>(R.id.miIcono).apply {
                setImageResource(icono)
                setColorFilter(getColor(if (rojo) R.color.rojo else R.color.blanco))
            }
            fila.findViewById<TextView>(R.id.miTitulo).apply {
                text = titulo
                setTextColor(getColor(if (rojo) R.color.rojo else R.color.blanco))
            }
            fila.findViewById<TextView>(R.id.miDetalle).text = detalle
            fila.setOnClickListener { hoja.dismiss(); accionItem() }
            cont.addView(fila)
        }

        item(R.drawable.ic_archivo, "Cargar Excel", "Reemplaza el listado desde un archivo .xlsx o .csv") {
            confirmarSiHayAvance { pedirArchivo(null) }
        }
        item(R.drawable.ic_bluetooth, "Recibir Excel por Bluetooth", "Hace visible el equipo para recibir el archivo") { mostrarBluetooth() }
        item(R.drawable.ic_actualizar, "Traer reservas del servidor", "Agrega reservas nuevas sin subir Excel") { actualizarReservas() }
        item(R.drawable.ic_nube, "Enviar registros ahora", if (pendientes > 0) "$pendientes registros esperando" else "No hay registros pendientes") { enviarAhora() }
        item(R.drawable.ic_camara, "Escanear QR con la cámara", "Si el lector Sunmi no está disponible") { iniciarEscaneo(conCamara = true) }
        item(R.drawable.ic_columnas, "Columnas del Excel y plantilla", "Qué datos debe tener el listado") { mostrarColumnas(false) }
        item(R.drawable.ic_compartir, "Exportar ingresos", "Excel con el estado y la hora de cada reserva") { exportar() }
        item(R.drawable.ic_impresora, "Imprimir ticket de prueba", if (Impresora.esSunmi) "Impresora Sunmi lista" else "Sin impresora Sunmi") {
            enviarAImpresora(Operacion.lineasPrueba(), null)
        }
        val nAlertas = Alertas.cantidad(this)
        item(R.drawable.ic_candado, "Alertas de la jornada", if (nAlertas > 0) "$nAlertas alertas: intentos de colarse, repetidos, sin reserva" else "Sin alertas por ahora") { mostrarAlertas() }
        item(R.drawable.ic_ajustes, "Ajustes (PIN)", "Operación vía / interior, ticket, servidor") { mostrarAjustes() }
        item(R.drawable.ic_borrar, "Borrar todos los ingresos", "Deja todas las reservas como pendientes (PIN)", rojo = true) { conPin { confirmarReinicio() } }

        hoja.setContentView(v)
        hoja.show()
    }

    // ---------------------------------------------------------------- servidor

    private fun enviarAhora() {
        if (!Sincronizacion.configuradoEnvio(this)) {
            servidorNoConfigurado(); return
        }
        val n = Bandeja.cantidad(this)
        if (n == 0) {
            toast("No hay registros por enviar."); return
        }
        Sincronizacion.enviarAhora(this)
        toast("Enviando $n registros (cuando haya internet)…")
    }

    private fun actualizarReservas() {
        if (!Sincronizacion.configuradoReservas(this)) {
            servidorNoConfigurado(); return
        }
        Sincronizacion.actualizarReservasAhora(this)
        toast("Buscando reservas nuevas en el servidor…")
    }

    private fun servidorNoConfigurado() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Servidor sin configurar")
            .setMessage("Escriba en Ajustes → Servidor la dirección para enviar los registros y la dirección para traer las reservas.")
            .setPositiveButton("Ir a Ajustes") { _, _ -> mostrarAjustes() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    // ---------------------------------------------------------------- perfil del operador

    private fun opcionesPerfil() {
        val tiene = File(filesDir, "perfil.jpg").exists()
        val opciones = if (tiene) arrayOf("Cambiar foto", "Quitar foto", "Cambiar nombre") else arrayOf("Poner foto", "Cambiar nombre")
        MaterialAlertDialogBuilder(this)
            .setTitle(ajustes.operador)
            .setItems(opciones) { _, w ->
                when (opciones[w]) {
                    "Cambiar foto", "Poner foto" -> try {
                        elegirFoto.launch("image/*")
                    } catch (e: Exception) {
                        mostrarError("No se pudo abrir la galería", e.message ?: e.toString())
                    }
                    "Quitar foto" -> {
                        File(filesDir, "perfil.jpg").delete(); cargarFotoPerfil()
                    }
                    else -> mostrarAjustes()
                }
            }
            .show()
    }

    private fun guardarFotoPerfil(uri: Uri) {
        Thread {
            val ok = try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException()
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var muestra = 1
                while (maxOf(o.outWidth, o.outHeight) / (muestra * 2) >= 400) muestra *= 2
                val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = muestra })
                    ?: throw IOException()
                val lado = minOf(b.width, b.height)
                val cuadro = Bitmap.createBitmap(b, (b.width - lado) / 2, (b.height - lado) / 2, lado, lado)
                val salida = Bitmap.createScaledBitmap(cuadro, 300, 300, true)
                File(filesDir, "perfil.jpg").outputStream().use { salida.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                true
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                if (ok) cargarFotoPerfil() else toast("No se pudo usar esa imagen.")
            }
        }.start()
    }

    private fun cargarFotoPerfil() {
        val f = File(filesDir, "perfil.jpg")
        val b = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
        if (b != null) {
            imgAvatar.setImageBitmap(b)
            imgAvatar.visibility = View.VISIBLE
        } else {
            imgAvatar.setImageDrawable(null)
            imgAvatar.visibility = View.GONE
        }
    }

    // ---------------------------------------------------------------- cargar listado

    private fun faltaListado() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Falta el listado")
            .setMessage("Cargue el Excel con las reservas, recíbalo por Bluetooth o tráigalo del servidor.")
            .setPositiveButton("Cargar Excel") { _, _ -> pedirArchivo(null) }
            .setNeutralButton("Bluetooth") { _, _ -> mostrarBluetooth() }
            .setNegativeButton("Servidor") { _, _ -> actualizarReservas() }
            .show()
    }

    private fun pedirArchivo(inicial: Uri?) {
        contratoAbrir.inicial = inicial
        try {
            abrirArchivo.launch(
                arrayOf(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-excel",
                    "text/csv",
                    "text/comma-separated-values",
                    "application/octet-stream",
                    "*/*"
                )
            )
        } catch (e: Exception) {
            mostrarError("No se pudo abrir el selector de archivos", e.message ?: e.toString())
        }
    }

    private fun confirmarSiHayAvance(hacer: () -> Unit) {
        val l = listado
        if (l == null) {
            hacer(); return
        }
        // Reemplazar el listado cambia toda la operación: pide el PIN de configuración
        conPin { confirmarReemplazo(hacer) }
    }

    private fun confirmarReemplazo(hacer: () -> Unit) {
        val l = listado
        if (l == null || (l.ingresos.isEmpty() && l.validaciones.isEmpty())) {
            hacer(); return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Cargar un listado nuevo?")
            .setMessage("Hay ${l.ingresos.size} ingresos y ${l.cantidadValidados()} validaciones registrados en «${l.nombreArchivo}». Si carga otro listado se reemplazan. Puede exportarlos primero.")
            .setPositiveButton("Cargar nuevo") { _, _ -> hacer() }
            .setNeutralButton("Exportar primero") { _, _ -> exportar() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun importar(uri: Uri) {
        val nombre = nombreDe(uri)
        toast("Leyendo $nombre…")
        Thread {
            val r = runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IOException("No se pudo abrir el archivo.")
                Listado.desdeTabla(nombre, LectorTabla.leer(bytes))
            }
            runOnUiThread {
                r.onSuccess { l ->
                    Operacion.reemplazarListado(l)
                    edtPlaca.setText("")
                    verLista = Vista.PENDIENTES
                    refrescar()
                    mostrarColumnas(true)
                }.onFailure { e ->
                    mostrarError("No se pudo leer el archivo", (e.message ?: e.toString()) + "\n\nEn el menú ⋮ → «Columnas del Excel» puede ver cómo debe estar el archivo.")
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- columnas

    private fun mostrarColumnas(trasCargar: Boolean) {
        val v = layoutInflater.inflate(R.layout.dialog_columnas, null)
        val intro = v.findViewById<TextView>(R.id.colIntro)
        val lista = v.findViewById<LinearLayout>(R.id.colLista)
        val nota = v.findViewById<TextView>(R.id.colNota)
        val l = listado

        intro.text = if (l != null && trasCargar) {
            "Se cargaron ${l.filas.size} reservas de «${l.nombreArchivo}». Revise que cada dato quedó en la columna correcta (toque una para cambiarla):"
        } else if (l != null) {
            "Columnas del listado «${l.nombreArchivo}». Toque una para cambiar de dónde se lee ese dato.\n\nPara un Excel nuevo, la PRIMERA FILA debe tener estos títulos (el orden no importa):"
        } else {
            "El Excel debe tener en la PRIMERA FILA estos títulos (el orden no importa). Debajo, una reserva por fila:"
        }

        fun pintar() {
            lista.removeAllViews()
            for (c in Listado.Campo.values()) {
                val fila = layoutInflater.inflate(R.layout.item_columna, lista, false)
                fila.findViewById<TextView>(R.id.colTitulo).text = c.titulo
                fila.findViewById<TextView>(R.id.colDescripcion).text = "${c.descripcion}\nEjemplo: ${c.ejemplo}"
                val estado = fila.findViewById<TextView>(R.id.colEstado)
                if (l == null) {
                    estado.text = if (c.clave) "Obligatoria *" else "Recomendada"
                    estado.setTextColor(getColor(if (c.clave) R.color.rojo else R.color.texto_suave))
                } else {
                    val col = l.columna(c)
                    when {
                        col >= 0 -> {
                            estado.text = "✓ ${XlsxWriter.colName(col)} · ${l.encabezados[col]}".let { if (it.length > 26) it.take(26) + "…" else it }
                            estado.setTextColor(getColor(R.color.blanco))
                        }
                        c.clave -> {
                            estado.text = "✗ No encontrada"
                            estado.setTextColor(getColor(R.color.rojo))
                        }
                        else -> {
                            estado.text = "— No está"
                            estado.setTextColor(getColor(R.color.texto_suave))
                        }
                    }
                    fila.setOnClickListener {
                        elegirColumnaPara(l, c) {
                            Operacion.guardar(); refrescar(); pintar()
                            nota.text = notaColumnas(l)
                        }
                    }
                }
                lista.addView(fila)
            }
        }
        pintar()
        nota.text = notaColumnas(l)

        MaterialAlertDialogBuilder(this)
            .setTitle(if (trasCargar) "Columnas detectadas" else "Columnas del Excel")
            .setView(v)
            .setPositiveButton(if (trasCargar) "Listo" else "Cerrar", null)
            .setNeutralButton("Compartir plantilla") { _, _ -> compartirPlantilla() }
            .show()
    }

    private fun notaColumnas(l: Listado?): CharSequence {
        val base = "* Debe existir al menos CODIGO_RESERVA o PLACA. El QR que trae el cliente debe contener el código de la reserva (o la placa). En TIPO escriba VIP o NORMAL.\n\nSirve Excel (.xlsx) o CSV. Si su Excel es .xls antiguo, guárdelo como .xlsx."
        if (l != null && !l.tieneClave()) {
            val aviso = "⚠ No se encontró la columna del código ni la de la placa. Toque CODIGO_RESERVA o PLACA y elija la columna correcta.\n\n"
            val s = SpannableString(aviso + base)
            s.setSpan(ForegroundColorSpan(getColor(R.color.rojo)), 0, aviso.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(StyleSpan(Typeface.BOLD), 0, aviso.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return s
        }
        return base
    }

    private fun elegirColumnaPara(l: Listado, c: Listado.Campo, alTerminar: () -> Unit) {
        val ejemplos = l.encabezados.indices.map { i ->
            l.filas.firstOrNull { it[i].isNotBlank() }?.get(i).orEmpty()
        }
        val opciones = arrayOf("(No usar esta columna)") + l.encabezados.mapIndexed { i, h ->
            val ej = ejemplos[i].let { if (it.length > 16) it.take(16) + "…" else it }
            "${XlsxWriter.colName(i)} · $h" + if (ej.isNotEmpty()) "   (ej: $ej)" else ""
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Dónde está ${c.titulo}?")
            .setSingleChoiceItems(opciones, l.columna(c) + 1) { d, w ->
                l.asignar(c, w - 1)
                d.dismiss()
                alTerminar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------- Bluetooth

    private fun mostrarBluetooth() {
        val adaptadorBt = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adaptadorBt == null) {
            mostrarError("Sin Bluetooth", "Este equipo no tiene Bluetooth. Cargue el Excel desde archivos, WhatsApp o correo.")
            return
        }
        val nombre = nombreBluetooth(adaptadorBt)
        MaterialAlertDialogBuilder(this)
            .setTitle("Recibir el Excel por Bluetooth")
            .setMessage(
                "1. Toque «Activar»: se enciende el Bluetooth y este equipo queda visible 5 minutos" +
                    (if (nombre != null) " con el nombre «$nombre»." else ".") +
                    "\n\n2. En el otro teléfono abra el Excel → Compartir → Bluetooth → elija este equipo." +
                    "\n\n3. Acepte el archivo y toque la notificación «Archivo recibido» para abrirlo con Reservas QR, o use «Abrir recibido»."
            )
            .setPositiveButton("Activar") { _, _ -> activarBluetooth() }
            .setNeutralButton("Abrir recibido") { _, _ -> abrirRecibido() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun nombreBluetooth(a: BluetoothAdapter): String? = try {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) null else a.name
    } catch (e: Exception) {
        null
    }

    private fun activarBluetooth() {
        if (Build.VERSION.SDK_INT >= 31) {
            val faltan = arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (faltan.isNotEmpty()) {
                pedirBt.launch(faltan.toTypedArray()); return
            }
        }
        hacerVisibleAhora()
    }

    private fun hacerVisibleAhora() {
        try {
            val i = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            hacerVisible.launch(i)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (_: Exception) {
                mostrarError("Bluetooth", "Active el Bluetooth desde los ajustes del equipo.")
            }
        }
    }

    private fun abrirRecibido() {
        val carpeta = try {
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/Bluetooth")
        } catch (e: Exception) {
            null
        }
        confirmarSiHayAvance { pedirArchivo(carpeta) }
    }

    // ---------------------------------------------------------------- exportar / plantilla / reiniciar

    private fun exportar() {
        val l = listado
        if (l == null) {
            toast("No hay listado para exportar."); return
        }
        val base = l.nombreArchivo.substringBeforeLast('.').ifBlank { "reservas" }
        val filas = l.paraExportar(fmtFecha)
        val marcadas = l.ingresos.keys.map { it + 1 }.toSet()
        compartirXlsx(
            "${base}_ingresos.xlsx", filas, marcadas,
            "Ingresos: ${l.nombreArchivo}",
            "Ingresos de ${l.nombreArchivo}: ${l.ingresos.size} de ${l.filas.size} reservas."
        )
    }

    private fun compartirPlantilla() {
        compartirXlsx(
            "Plantilla_reservas.xlsx", Listado.plantilla(), emptySet(),
            "Plantilla de reservas",
            "Llene esta plantilla (una reserva por fila) y envíela al equipo de ingreso."
        )
    }

    private fun compartirXlsx(nombre: String, filas: List<List<String>>, marcadas: Set<Int>, asunto: String, texto: String) {
        try {
            val dir = File(cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val limpio = nombre.replace(Regex("[^\\p{L}\\p{N} _.-]"), "_")
            val f = File(dir, limpio)
            f.outputStream().use { XlsxWriter.write(it, filas, marcadas) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val enviar = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, asunto)
                putExtra(Intent.EXTRA_TEXT, texto)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(enviar, "Enviar"))
        } catch (e: Exception) {
            mostrarError("No se pudo crear el Excel", e.message ?: e.toString())
        }
    }

    private fun confirmarReinicio() {
        val l = listado
        if (l == null || l.ingresos.isEmpty()) {
            toast("No hay ingresos registrados."); return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Borrar todos los ingresos?")
            .setMessage("Se quitarán los ${l.ingresos.size} ingresos y sus fotos del equipo; todas las reservas quedarán pendientes. Lo que ya se envió al servidor no se borra allá.")
            .setPositiveButton("Borrar ingresos") { _, _ -> Operacion.borrarIngresos() }
            .setNeutralButton("Exportar primero") { _, _ -> exportar() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------- ajustes

    /** Los ajustes (modo de operación, servidor, ticket…) se abren solo con el PIN de configuración. */
    private fun mostrarAjustes() {
        conPin(crearSiFalta = true) { abrirAjustes() }
    }

    private fun adaptadorSpinner(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun abrirAjustes() {
        val v = layoutInflater.inflate(R.layout.dialog_ajustes, null)
        val op = v.findViewById<EditText>(R.id.ajOperador)
        val neg = v.findViewById<EditText>(R.id.ajNegocio)
        val bien = v.findViewById<EditText>(R.id.ajBienvenida)
        val vip = v.findViewById<EditText>(R.id.ajVip)
        val auto = v.findViewById<MaterialSwitch>(R.id.ajAuto)
        val qr = v.findViewById<MaterialSwitch>(R.id.ajQr)
        val lector = v.findViewById<MaterialSwitch>(R.id.ajLector)
        val urlEnvio = v.findViewById<EditText>(R.id.ajUrlEnvio)
        val urlReservas = v.findViewById<EditText>(R.id.ajUrlReservas)
        val token = v.findViewById<EditText>(R.id.ajToken)
        val lote = v.findViewById<Spinner>(R.id.ajLote)
        val intervalo = v.findViewById<Spinner>(R.id.ajIntervalo)
        val autoRes = v.findViewById<MaterialSwitch>(R.id.ajAutoReservas)
        val soloQr = v.findViewById<MaterialSwitch>(R.id.ajSoloValidarQr)
        val soloPlaca = v.findViewById<MaterialSwitch>(R.id.ajSoloValidarPlaca)
        val impVal = v.findViewById<MaterialSwitch>(R.id.ajImprimirValidar)
        val envVal = v.findViewById<MaterialSwitch>(R.id.ajEnviarValidar)
        val exigir = v.findViewById<MaterialSwitch>(R.id.ajExigirValidacion)
        val envAlertas = v.findViewById<MaterialSwitch>(R.id.ajEnviarAlertas)
        val minEspera = v.findViewById<EditText>(R.id.ajMinutosEspera)
        val redActiva = v.findViewById<MaterialSwitch>(R.id.ajRedActiva)
        val nombreEquipo = v.findViewById<EditText>(R.id.ajNombreEquipo)
        val redClave = v.findViewById<EditText>(R.id.ajRedClave)
        val redIps = v.findViewById<EditText>(R.id.ajRedIps)

        lote.adapter = adaptadorSpinner(Sincronizacion.LOTES.map { "$it registros" })
        lote.setSelection(Sincronizacion.LOTES.indexOf(ajustes.loteEnvio).coerceAtLeast(0))
        intervalo.adapter = adaptadorSpinner(Sincronizacion.INTERVALOS.map { if (it == 1) "1 minuto" else "$it minutos" })
        intervalo.setSelection(Sincronizacion.INTERVALOS.indexOf(ajustes.intervaloMin).coerceAtLeast(0))

        op.setText(ajustes.operador.takeIf { it != "Operador" }.orEmpty())
        neg.setText(ajustes.negocio)
        bien.setText(ajustes.bienvenida)
        vip.setText(ajustes.mensajeVip)
        auto.isChecked = ajustes.autoImprimir
        qr.isChecked = ajustes.imprimirQr
        lector.isChecked = ajustes.usarLectorSunmi
        urlEnvio.setText(ajustes.urlEnvio)
        urlReservas.setText(ajustes.urlReservas)
        token.setText(ajustes.token)
        autoRes.isChecked = ajustes.autoReservas
        soloQr.isChecked = ajustes.accionQr == Operacion.Accion.VALIDAR.name
        soloPlaca.isChecked = ajustes.accionPlaca == Operacion.Accion.VALIDAR.name
        impVal.isChecked = ajustes.imprimirAlValidar
        envVal.isChecked = ajustes.enviarAlValidar
        exigir.isChecked = ajustes.exigirValidacion
        envAlertas.isChecked = ajustes.enviarAlertas
        minEspera.setText(ajustes.minutosEspera.toString())
        redActiva.isChecked = ajustes.redActiva
        nombreEquipo.setText(ajustes.nombreEquipo)
        redClave.setText(ajustes.redClave)
        redIps.setText(ajustes.redIps)
        v.findViewById<View>(R.id.ajVerEquipos).setOnClickListener { mostrarEquiposCercanos() }
        v.findViewById<View>(R.id.ajRedBateria).setOnClickListener { permitirSegundoPlano() }

        v.findViewById<View>(R.id.ajPerfilVia).setOnClickListener {
            soloQr.isChecked = true
            soloPlaca.isChecked = true
            impVal.isChecked = false
            envVal.isChecked = true
            exigir.isChecked = false
            envAlertas.isChecked = true
            toast("Equipo de la vía: solo valida y envía. Toque «Guardar».")
        }
        v.findViewById<View>(R.id.ajPerfilInterior).setOnClickListener {
            soloQr.isChecked = false
            soloPlaca.isChecked = false
            auto.isChecked = true
            envVal.isChecked = true
            exigir.isChecked = true
            envAlertas.isChecked = true
            toast("Equipo interior: registra e imprime, y exige haber pasado por la vía. Toque «Guardar».")
        }
        v.findViewById<View>(R.id.ajCambiarPin).setOnClickListener { crearPin(null) }

        fun guardarCampos() {
            ajustes.operador = op.text.toString()
            ajustes.negocio = neg.text.toString()
            ajustes.bienvenida = bien.text.toString().ifBlank { Ajustes.BIENVENIDA }
            ajustes.mensajeVip = vip.text.toString()
            ajustes.autoImprimir = auto.isChecked
            ajustes.imprimirQr = qr.isChecked
            ajustes.usarLectorSunmi = lector.isChecked
            ajustes.urlEnvio = urlEnvio.text.toString()
            ajustes.urlReservas = urlReservas.text.toString()
            ajustes.token = token.text.toString()
            ajustes.loteEnvio = Sincronizacion.LOTES[lote.selectedItemPosition.coerceIn(0, Sincronizacion.LOTES.size - 1)]
            ajustes.intervaloMin = Sincronizacion.INTERVALOS[intervalo.selectedItemPosition.coerceIn(0, Sincronizacion.INTERVALOS.size - 1)]
            ajustes.autoReservas = autoRes.isChecked
            ajustes.accionQr = if (soloQr.isChecked) Operacion.Accion.VALIDAR.name else Operacion.Accion.INGRESAR.name
            ajustes.accionPlaca = if (soloPlaca.isChecked) Operacion.Accion.VALIDAR.name else Operacion.Accion.INGRESAR.name
            ajustes.imprimirAlValidar = impVal.isChecked
            ajustes.enviarAlValidar = envVal.isChecked
            ajustes.exigirValidacion = exigir.isChecked
            ajustes.enviarAlertas = envAlertas.isChecked
            ajustes.minutosEspera = minEspera.text.toString().toIntOrNull() ?: 30
            ajustes.redActiva = redActiva.isChecked
            ajustes.nombreEquipo = nombreEquipo.text.toString()
            ajustes.redClave = redClave.text.toString()
            ajustes.redIps = redIps.text.toString()
            RedLocal.aplicarAjustes(this)
            Sincronizacion.programar(this)
            refrescar()
        }
        v.findViewById<Button>(R.id.ajPrueba).setOnClickListener { guardarCampos(); enviarAImpresora(Operacion.lineasPrueba(), null) }

        MaterialAlertDialogBuilder(this)
            .setTitle("Ajustes")
            .setView(v)
            .setPositiveButton("Guardar") { _, _ ->
                guardarCampos()
                if (ajustes.urlReservas.isNotBlank()) Sincronizacion.actualizarReservasAhora(this)
                if (Bandeja.cantidad(this) > 0) Sincronizacion.enviarAhora(this)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------- equipos cercanos

    /** Lista los equipos que se encuentran por el Wi-Fi y si están conectados con este. */
    private fun mostrarEquiposCercanos() {
        val a = ajustes
        val t = StringBuilder()
        t.append("Este equipo: ").append(a.nombreEquipo).append('\n')
        val ips = RedLocal.misDirecciones()
        t.append("Dirección en la red: ").append(if (ips.isEmpty()) "sin Wi-Fi" else ips.joinToString(", ")).append("\n\n")
        if (!a.redActiva) {
            t.append("La red entre equipos está apagada. Se activa en Ajustes → Red entre equipos.")
        } else if (!RedLocal.activa()) {
            t.append(RedLocal.error ?: "La red entre equipos se está iniciando…")
        } else {
            val eq = RedLocal.equipos()
            if (eq.isEmpty()) {
                t.append("No se ha encontrado ningún equipo.\n\nVerifique que los demás estén encendidos, con la app abierta una vez, en el mismo Wi-Fi y con la misma clave de red. Si tienen datos móviles (4G), pruebe apagarlos.")
            } else {
                for (e in eq) {
                    val estado = if (e.conectado) "● conectado" else "○ visto, conectando…"
                    t.append(e.nombre.ifBlank { "(sin nombre)" }).append("  ·  ").append(e.ip).append("\n   ").append(estado)
                    if (e.eventosRecibidos > 0) t.append("  ·  ").append(e.eventosRecibidos).append(" avisos")
                    t.append("\n")
                }
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Equipos cercanos")
            .setMessage(t.toString().trimEnd())
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Sincronizar ahora") { _, _ ->
                RedLocal.sincronizarAhora()
                toast("Comparando lo que sabe cada equipo…")
            }
            .show()
    }

    /** Abre la pantalla de Android donde se quita el ahorro de batería a esta app, para que no se duerma en segundo plano. */
    private fun permitirSegundoPlano() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            toast("Busque «Reservas QR» y elija «No optimizar» o «Sin restricciones».")
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                toast("Entre a Batería y elija «Sin restricciones».")
            } catch (_: Exception) {
                toast("No se pudo abrir la pantalla de batería.")
            }
        }
    }

    // ---------------------------------------------------------------- PIN de configuración

    /** Ejecuta [alVerificar] solo después del PIN de configuración (si el administrador ya creó uno). */
    private fun conPin(crearSiFalta: Boolean = false, alVerificar: () -> Unit) {
        if (!ajustes.tienePin) {
            if (crearSiFalta) crearPin(alVerificar) else alVerificar()
            return
        }
        if (SystemClock.elapsedRealtime() < pinValidoHasta) {
            alVerificar(); return
        }
        pedirPin(alVerificar)
    }

    private fun pedirPin(alVerificar: () -> Unit) {
        val v = layoutInflater.inflate(R.layout.dialog_pin, null)
        val campo = v.findViewById<EditText>(R.id.pinCampo)
        val error = v.findViewById<TextView>(R.id.pinError)
        v.findViewById<TextView>(R.id.pinMensaje).text = "Escriba el PIN de configuración para continuar."
        val d = MaterialAlertDialogBuilder(this)
            .setTitle("PIN de configuración")
            .setView(v)
            .setPositiveButton("Aceptar", null)
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Olvidé el PIN", null)
            .create()
        d.setOnShowListener {
            campo.requestFocus()
            d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            fun verificar() {
                val ahora = SystemClock.elapsedRealtime()
                if (ahora < pinBloqueadoHasta) {
                    error.text = "Demasiados intentos. Espere ${(pinBloqueadoHasta - ahora) / 1000 + 1} s."
                    error.visibility = View.VISIBLE
                    return
                }
                if (ajustes.pinCorrecto(campo.text.toString())) {
                    pinFallos = 0
                    pinValidoHasta = ahora + 2 * 60_000L
                    d.dismiss()
                    alVerificar()
                } else {
                    pinFallos++
                    campo.setText("")
                    if (pinFallos >= 5) {
                        pinFallos = 0
                        pinBloqueadoHasta = ahora + 60_000L
                        error.text = "PIN incorrecto. Espere 1 minuto para intentar de nuevo."
                    } else {
                        error.text = "PIN incorrecto ($pinFallos de 5)."
                    }
                    error.visibility = View.VISIBLE
                }
            }
            d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener { verificar() }
            d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                d.dismiss()
                olvidePin(alVerificar)
            }
            campo.setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_DONE) {
                    verificar(); true
                } else false
            }
        }
        d.show()
    }

    /** Crea o cambia el PIN de configuración (4 a 8 números). */
    private fun crearPin(alTerminar: (() -> Unit)?) {
        val v = layoutInflater.inflate(R.layout.dialog_pin, null)
        val campo = v.findViewById<EditText>(R.id.pinCampo)
        val confirmar = v.findViewById<EditText>(R.id.pinConfirmar)
        val error = v.findViewById<TextView>(R.id.pinError)
        v.findViewById<View>(R.id.pinConfirmarCaja).visibility = View.VISIBLE
        v.findViewById<TextView>(R.id.pinMensaje).text =
            "Cree un PIN para proteger la configuración: modo de operación, servidor, listado, anulaciones y borrados. Solo debe conocerlo el administrador."
        val d = MaterialAlertDialogBuilder(this)
            .setTitle("Crear PIN de configuración")
            .setView(v)
            .setPositiveButton("Guardar PIN", null)
            .setNegativeButton("Cancelar", null)
            .create()
        d.setOnShowListener {
            campo.requestFocus()
            d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val a = campo.text.toString()
                val b = confirmar.text.toString()
                when {
                    !Pin.formatoValido(a) -> {
                        error.text = "El PIN debe tener de 4 a 8 números."
                        error.visibility = View.VISIBLE
                    }
                    a != b -> {
                        error.text = "Los dos PIN no coinciden."
                        error.visibility = View.VISIBLE
                    }
                    else -> {
                        ajustes.crearPin(a)
                        pinValidoHasta = SystemClock.elapsedRealtime() + 2 * 60_000L
                        d.dismiss()
                        toast("PIN guardado.")
                        alTerminar?.invoke()
                    }
                }
            }
        }
        d.show()
    }

    /** «Olvidé el PIN»: se confirma con la clave de desbloqueo del equipo y se crea uno nuevo. */
    @Suppress("DEPRECATION")
    private fun olvidePin(seguir: () -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        val i = if (km != null && km.isDeviceSecure) {
            km.createConfirmDeviceCredentialIntent("Restablecer el PIN", "Confirme con la clave del equipo para crear un PIN nuevo")
        } else null
        if (i == null) {
            mostrarError("No se puede restablecer", "Este equipo no tiene clave de bloqueo. Configure una en los ajustes del equipo para poder restablecer el PIN.")
            return
        }
        despuesDeRestablecer = seguir
        Bloqueo.autenticando = true
        try {
            restablecerPin.launch(i)
        } catch (e: Exception) {
            Bloqueo.autenticando = false
            despuesDeRestablecer = null
        }
    }

    private fun explicarModo() {
        val q = Operacion.accionPara(Operacion.Origen.QR) == Operacion.Accion.VALIDAR
        val p = Operacion.accionPara(Operacion.Origen.PLACA) == Operacion.Accion.VALIDAR
        fun que(validar: Boolean) = if (validar) "solo VALIDA el acceso (momento 1, en la vía)" else "REGISTRA el ingreso e imprime el ticket (momento 2, adentro)"
        MaterialAlertDialogBuilder(this)
            .setTitle(Operacion.descripcionModo())
            .setMessage(
                "Este equipo trabaja así:\n\n• Al leer el QR: ${que(q)}.\n• Al leer la placa: ${que(p)}.\n\n" +
                    (if (ajustes.exigirValidacion) "Adentro NO se da ingreso a quien no pasó antes por la vía.\n\n" else "") +
                    "El administrador puede cambiarlo en Ajustes con su PIN."
            )
            .setPositiveButton("Entendido", null)
            .setNeutralButton("Cambiar (PIN)") { _, _ -> mostrarAjustes() }
            .show()
    }

    // ---------------------------------------------------------------- alertas

    private fun mostrarAlertas() {
        val items = Alertas.todas(this)
        val l = listado
        val atrasados = l?.validados()?.filter { Operacion.minutosEsperando(it) >= ajustes.minutosEspera }.orEmpty()
        if (items.isEmpty() && atrasados.isEmpty()) {
            toast("No hay alertas."); return
        }
        val sb = StringBuilder()
        if (l != null && atrasados.isNotEmpty()) {
            sb.append("VALIDADOS EN LA VÍA SIN INGRESAR (más de ${ajustes.minutosEspera} min):\n")
            atrasados.take(15).forEach { f ->
                sb.append("• ").append(l.titulo(f)).append(" · hace ").append(Operacion.minutosEsperando(f)).append(" min\n")
            }
            sb.append('\n')
        }
        items.take(60).forEach { a ->
            sb.append(fmtFecha.format(Date(a.t))).append(" · ").append(Alertas.titulo(a.motivo)).append('\n')
            val quien = listOf(a.placa, a.nombre).filter { it.isNotBlank() }.joinToString(" · ")
            if (quien.isNotBlank()) sb.append(quien).append('\n')
            if (a.detalle.isNotBlank()) sb.append(a.detalle).append('\n')
            sb.append('\n')
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Alertas (${items.size})")
            .setMessage(sb.toString().trim())
            .setPositiveButton("Cerrar", null)
            .setNegativeButton("Borrar lista") { _, _ -> conPin { Alertas.borrar(this); refrescar() } }
            .show()
    }

    // ---------------------------------------------------------------- pantalla

    private fun enfocarBuscador() {
        edtPlaca.requestFocus()
        edtPlaca.selectAll()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(edtPlaca, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun refrescar() {
        if (!::txtOperador.isInitialized) return
        val l = listado
        val total = l?.filas?.size ?: 0
        val dentro = l?.ingresos?.size ?: 0

        txtOperador.text = ajustes.operador
        avatar.text = ajustes.operador.trim().firstOrNull()?.uppercase() ?: "O"
        val eventos = l?.let { x -> x.filas.indices.map { x.valor(it, Listado.Campo.EVENTO) }.filter { it.isNotBlank() }.distinct() }.orEmpty()
        txtSubtitulo.text = listOfNotNull(
            ajustes.negocio.takeIf { it.isNotBlank() },
            when (eventos.size) { 0 -> null; 1 -> eventos[0]; else -> "${eventos.size} eventos" }
        ).joinToString(" · ").ifBlank { "Control de reservas" }

        txtContador.text = dentro.toString()
        txtTotal.text = " / $total ingresados"
        val nValidados = l?.cantidadValidados() ?: 0
        txtFaltan.text = when {
            total == 0 -> ""
            nValidados > 0 -> "${total - dentro} por ingresar · $nValidados validados esperando"
            else -> "${total - dentro} pendientes"
        }
        progreso.setProgressCompat(if (total == 0) 0 else dentro * 100 / total, true)

        refrescarEstado()
        refrescarLista()
    }

    private fun punto(texto: String, ok: Boolean): CharSequence {
        val s = SpannableString("●  $texto")
        s.setSpan(ForegroundColorSpan(getColor(if (ok) R.color.blanco else R.color.rojo)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private fun refrescarEstado() {
        if (!::chipImpresora.isInitialized) return
        chipModo.text = punto(Operacion.descripcionModo(), true)
        val nAlertas = Alertas.cantidad(this)
        chipAlertas.visibility = if (nAlertas > 0) View.VISIBLE else View.GONE
        chipAlertas.text = punto("$nAlertas alertas", false)
        chipImpresora.text = punto(if (Impresora.esSunmi) "Impresora Sunmi" else "Sin impresora Sunmi", Impresora.esSunmi)
        val lector = EscanerSunmi.disponible && ajustes.usarLectorSunmi
        chipLector.text = punto(if (lector) "Lector Sunmi" else "QR por cámara", lector)
        chipRed.text = punto(RedLocal.resumen(), ajustes.redActiva && RedLocal.activa() && RedLocal.conectados() > 0)
        chipEnvio.text = if (!Sincronizacion.configuradoEnvio(this)) {
            punto("Sin servidor", false)
        } else {
            val n = Bandeja.cantidad(this)
            val error = Sincronizacion.ultimoError
            when {
                error != null -> punto("$n por enviar · $error", false)
                n > 0 -> punto("$n por enviar", true)
                else -> punto("Todo enviado", true)
            }
        }
    }

    private fun refrescarLista() {
        if (!::adaptador.isInitialized) return
        val l = listado
        val q = edtPlaca.text?.toString().orEmpty()
        val total = l?.filas?.size ?: 0
        val dentro = l?.ingresos?.size ?: 0
        val nVal = l?.cantidadValidados() ?: 0
        segPendientes.text = "Pendientes ${(total - dentro - nVal).coerceAtLeast(0)}"
        segValidados.text = "Validados $nVal"
        segIngresados.text = "Ingresados $dentro"
        val buscando = q.isNotBlank()
        listOf(
            segPendientes to (verLista == Vista.PENDIENTES),
            segValidados to (verLista == Vista.VALIDADOS),
            segIngresados to (verLista == Vista.INGRESADOS)
        ).forEach { (v, sel) ->
            val activo = sel && !buscando
            v.setBackgroundResource(if (activo) R.drawable.tab_activa else R.drawable.tab_inactiva)
            v.setTextColor(getColor(if (activo) R.color.negro else R.color.texto_suave))
        }

        val filas: List<Int> = when {
            l == null -> emptyList()
            buscando -> l.buscarTexto(q, 200)
            verLista == Vista.INGRESADOS -> l.ingresados()
            verLista == Vista.VALIDADOS -> l.validados()
            else -> l.pendientes()
        }
        adaptador.actualizar(filas)
        txtVacio.visibility = if (filas.isEmpty()) View.VISIBLE else View.GONE
        txtVacio.text = when {
            l == null -> "No hay listado de reservas.\nToque ⋮ arriba a la derecha para cargar el Excel, recibirlo por Bluetooth o traerlo del servidor."
            buscando -> "No hay reservas con «${q.trim()}».\nRevise la placa o busque por nombre o código."
            verLista == Vista.INGRESADOS -> "Aún no hay ingresos.\nUse los botones de abajo para escanear el QR o leer la placa."
            verLista == Vista.VALIDADOS -> "No hay vehículos validados esperando.\nAquí aparecen los que ya pasaron por la vía y todavía no reciben ticket adentro."
            else -> "¡Todas las reservas ya ingresaron!"
        }
    }

    // ---------------------------------------------------------------- utilidades

    private fun mostrarError(titulo: String, mensaje: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titulo)
            .setMessage(mensaje)
            .setPositiveButton("Entendido", null)
            .create()
            .atenderLector()
            .show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun ocultarTeclado() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(edtPlaca.windowToken, 0)
        edtPlaca.clearFocus()
    }

    @Suppress("DEPRECATION")
    private fun vibrar(ms: Long) {
        try {
            val v = getSystemService(Vibrator::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(ms)
        } catch (_: Exception) {
        }
    }

    private fun tonoError() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            tg.startTone(ToneGenerator.TONE_PROP_NACK, 400)
            handler.postDelayed({ tg.release() }, 600)
        } catch (_: Exception) {
        }
    }

    private fun nombreDe(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i)?.let { return it }
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "listado"
    }
}
