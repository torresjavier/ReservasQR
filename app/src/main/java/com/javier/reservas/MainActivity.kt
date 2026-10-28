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
    private lateinit var chipImpresora: TextView
    private lateinit var chipLector: TextView
    private lateinit var chipEnvio: TextView
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

    private var verIngresados = false
    private var dialogoActual: AlertDialog? = null
    private val bufferTeclas = StringBuilder()
    private var ultimaTecla = 0L
    private var ultimaLectura = ""
    private var ultimaLecturaT = 0L
    private var uriPendiente: Uri? = null
    private var ayudaInicialMostrada = false
    /** El operador canceló la clave: se espera a que toque «Desbloquear». */
    private var esperarBoton = false
    private val handler = Handler(Looper.getMainLooper())
    private val co = Locale("es", "CO")
    private val fmtHora = SimpleDateFormat("hh:mm a", co)
    private val fmtFecha = SimpleDateFormat("dd/MM/yyyy hh:mm a", co)
    private val alCambiarDatos: () -> Unit = { refrescar() }

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

    /** Lecturas del lector Sunmi cuando está en modo «broadcast». */
    private val receptorEscaner = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val datos = intent?.getStringExtra(EscanerSunmi.EXTRA_DATOS) ?: return
            recibirLectura(datos)
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
        chipImpresora = findViewById(R.id.chipImpresora)
        chipLector = findViewById(R.id.chipLector)
        chipEnvio = findViewById(R.id.chipEnvio)
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
        segPendientes.setOnClickListener { verIngresados = false; refrescarLista() }
        segIngresados.setOnClickListener { verIngresados = true; refrescarLista() }
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

        cargarFotoPerfil()
        refrescar()
        if (savedInstanceState == null) manejarIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Operacion.dejarDeEscuchar(alCambiarDatos)
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

    // ---------------------------------------------------------------- lector Sunmi (teclado / broadcast)

    /**
     * Lector Sunmi en modo «teclado»: escribe el código como teclas y termina con Enter.
     * Cuando no hay un campo de texto activo, se juntan esas teclas y se procesan como un QR.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (currentFocus is EditText || Bloqueo.requiereClave) return super.dispatchKeyEvent(event)
        val ahora = System.currentTimeMillis()
        if (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (event.action == KeyEvent.ACTION_UP && bufferTeclas.length >= 3) {
                val t = bufferTeclas.toString()
                bufferTeclas.setLength(0)
                recibirLectura(t)
                return true
            }
            if (bufferTeclas.isNotEmpty()) return true
            return super.dispatchKeyEvent(event)
        }
        val ch = event.unicodeChar
        if (ch > 0 && !Character.isISOControl(ch)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (ahora - ultimaTecla > 400) bufferTeclas.setLength(0)
                bufferTeclas.appendCodePoint(ch)
                ultimaTecla = ahora
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun recibirLectura(datos: String) {
        if (Bloqueo.requiereClave) return
        val t = datos.trim()
        if (t.isEmpty()) return
        val ahora = System.currentTimeMillis()
        if (t == ultimaLectura && ahora - ultimaLecturaT < 1500) return // llega duplicado por broadcast y teclado
        ultimaLectura = t
        ultimaLecturaT = ahora
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
            mostrarTarjeta(
                Estilo.ERROR, "✗ NO ESTÁ EN EL LISTADO", null, texto.trim(),
                secundario = accion("Buscar placa") { d, _ -> d.dismiss(); enfocarBuscador() },
                principal = accion("Escanear otro") { d, _ -> d.dismiss(); iniciarEscaneo() }
            )
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

    private fun registrar(fila: Int, origen: Operacion.Origen) {
        when (val r = Operacion.registrar(fila, origen)) {
            is Operacion.Registro.Nuevo -> {
                vibrar(80)
                val l = listado ?: return
                val auto = ajustes.autoImprimir
                mostrarTarjeta(
                    if (l.esVip(fila)) Estilo.VIP else Estilo.OK,
                    if (l.esVip(fila)) "★ VIP · RESERVA INGRESADA" else "✓ RESERVA INGRESADA",
                    fila, null,
                    secundario = accion(if (auto) "Reimprimir" else "Imprimir ticket") { _, est -> imprimir(fila, auto, est) },
                    principal = siguiente(origen),
                    imprimirAhora = auto
                )
            }
            is Operacion.Registro.Repetido -> {
                vibrar(250)
                mostrarTarjeta(
                    Estilo.AVISO, "YA INGRESÓ · ${fmtHora.format(Date(r.hora))}", fila, null,
                    secundario = accion("Reimprimir") { _, est -> imprimir(fila, true, est) },
                    principal = siguiente(origen)
                )
            }
        }
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
        } else {
            mostrarTarjeta(
                Estilo.INFO, "RESERVA PENDIENTE", fila, null,
                secundario = accion("Cerrar") { d, _ -> d.dismiss() },
                principal = accion("Registrar ingreso") { d, _ -> d.dismiss(); registrar(fila, Operacion.Origen.BUSQUEDA) }
            )
        }
    }

    private fun confirmarAnular(fila: Int) {
        val l = listado ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Anular el ingreso?")
            .setMessage("${l.titulo(fila)} volverá a quedar como pendiente. Se avisará al servidor.")
            .setPositiveButton("Anular") { _, _ -> Operacion.anular(fila) }
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
        imprimirAhora: Boolean = false
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
            l.valor(fila, Listado.Campo.EVENTO).takeIf { it.isNotBlank() }?.let { det += "Evento: $it" }
            l.valor(fila, Listado.Campo.CODIGO).takeIf { it.isNotBlank() }?.let { det += "Reserva: $it" }
            l.valor(fila, Listado.Campo.TIPO).takeIf { it.isNotBlank() }?.let { det += "Tipo: ${it.uppercase()}" }
            l.ingresos[fila]?.let { det += "Ingreso: ${fmtFecha.format(Date(it))}" }
            Operacion.info[fila]?.let { i ->
                val como = when (i.origen) {
                    Operacion.Origen.PLACA.api -> "cámara (placa leída: ${i.placaLeida})"
                    Operacion.Origen.QR.api -> "QR"
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
        val d = MaterialAlertDialogBuilder(this).setView(v).create()
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
        item(R.drawable.ic_ajustes, "Ajustes", "Operador, ticket, servidor") { mostrarAjustes() }
        item(R.drawable.ic_borrar, "Borrar todos los ingresos", "Deja todas las reservas como pendientes", rojo = true) { confirmarReinicio() }

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
        if (l == null || l.ingresos.isEmpty()) {
            hacer(); return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Cargar un listado nuevo?")
            .setMessage("Hay ${l.ingresos.size} ingresos registrados en «${l.nombreArchivo}». Si carga otro listado se reemplazan. Puede exportarlos primero.")
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
                    verIngresados = false
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

    private fun mostrarAjustes() {
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
        val lote = v.findViewById<EditText>(R.id.ajLote)
        val autoRes = v.findViewById<MaterialSwitch>(R.id.ajAutoReservas)

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
        lote.setText(ajustes.loteEnvio.toString())
        autoRes.isChecked = ajustes.autoReservas

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
            ajustes.loteEnvio = lote.text.toString().toIntOrNull() ?: 5
            ajustes.autoReservas = autoRes.isChecked
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
        txtFaltan.text = if (total > 0) "${total - dentro} pendientes" else ""
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
        chipImpresora.text = punto(if (Impresora.esSunmi) "Impresora Sunmi" else "Sin impresora Sunmi", Impresora.esSunmi)
        val lector = EscanerSunmi.disponible && ajustes.usarLectorSunmi
        chipLector.text = punto(if (lector) "Lector Sunmi" else "QR por cámara", lector)
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
        segPendientes.text = "Pendientes · ${total - dentro}"
        segIngresados.text = "Ingresados · $dentro"
        val buscando = q.isNotBlank()
        listOf(segPendientes to !verIngresados, segIngresados to verIngresados).forEach { (v, sel) ->
            val activo = sel && !buscando
            v.setBackgroundResource(if (activo) R.drawable.tab_activa else R.drawable.tab_inactiva)
            v.setTextColor(getColor(if (activo) R.color.negro else R.color.texto_suave))
        }

        val filas: List<Int> = when {
            l == null -> emptyList()
            buscando -> l.buscarTexto(q, 200)
            verIngresados -> l.ingresados()
            else -> l.pendientes()
        }
        adaptador.actualizar(filas)
        txtVacio.visibility = if (filas.isEmpty()) View.VISIBLE else View.GONE
        txtVacio.text = when {
            l == null -> "No hay listado de reservas.\nToque ⋮ arriba a la derecha para cargar el Excel, recibirlo por Bluetooth o traerlo del servidor."
            buscando -> "No hay reservas con «${q.trim()}».\nRevise la placa o busque por nombre o código."
            verIngresados -> "Aún no hay ingresos.\nUse los botones de abajo para escanear el QR o leer la placa."
            else -> "¡Todas las reservas ya ingresaron!"
        }
    }

    // ---------------------------------------------------------------- utilidades

    private fun mostrarError(titulo: String, mensaje: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titulo)
            .setMessage(mensaje)
            .setPositiveButton("Entendido", null)
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
