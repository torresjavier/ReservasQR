package com.javier.reservas

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

    private enum class Estilo { OK, AVISO, ERROR, INFO }

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

    private var listado: Listado? = null
    private lateinit var ajustes: Ajustes
    private lateinit var archivoEstado: File

    private lateinit var header: View
    private lateinit var avatar: TextView
    private lateinit var txtOperador: TextView
    private lateinit var txtSubtitulo: TextView
    private lateinit var txtPill: TextView
    private lateinit var txtImpresora: TextView
    private lateinit var tabIngresos: TextView
    private lateinit var tabBuscar: TextView
    private lateinit var panelIngresos: View
    private lateinit var panelBuscar: View
    private lateinit var txtListado: TextView
    private lateinit var txtAdentro: TextView
    private lateinit var chipVer: TextView
    private lateinit var vacioIngresos: TextView
    private lateinit var vacioBuscar: TextView
    private lateinit var edtPlaca: EditText

    private lateinit var adIngresos: ReservaAdapter
    private lateinit var adBuscar: ReservaAdapter

    private var tab = 0
    private var verPendientes = false
    private val handler = Handler(Looper.getMainLooper())
    private val co = Locale("es", "CO")
    private val fmtHora = SimpleDateFormat("hh:mm a", co)
    private val fmtFecha = SimpleDateFormat("dd/MM/yyyy hh:mm a", co)

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
        else mostrarError("Sin permiso de cámara", "Para leer el QR active el permiso de cámara en Ajustes → Aplicaciones → Reservas QR → Permisos.")
    }

    private val pedirBt = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) hacerVisibleAhora()
        else mostrarError("Sin permiso de Bluetooth", "Active el permiso «Dispositivos cercanos» para Reservas QR en Ajustes → Aplicaciones → Permisos.")
    }

    private val hacerVisible = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != RESULT_CANCELED) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Bluetooth listo")
                .setMessage("Este equipo está visible por 5 minutos.\n\nDesde el otro teléfono: abra el Excel → Compartir → Bluetooth → elija este equipo.\n\nCuando llegue, acepte el archivo y toque la notificación «Archivo recibido» para abrirlo con Reservas QR (o use «Abrir archivo recibido»).")
                .setPositiveButton("Abrir archivo recibido") { _, _ -> abrirRecibido() }
                .setNegativeButton("Cerrar", null)
                .show()
        } else {
            toast("No se activó la visibilidad por Bluetooth.")
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

        ajustes = Ajustes(this)
        archivoEstado = File(filesDir, "reservas.json")
        listado = Persistencia.cargar(archivoEstado)

        header = findViewById(R.id.header)
        avatar = findViewById(R.id.avatar)
        txtOperador = findViewById(R.id.txtOperador)
        txtSubtitulo = findViewById(R.id.txtSubtitulo)
        txtPill = findViewById(R.id.txtPill)
        txtImpresora = findViewById(R.id.txtImpresora)
        tabIngresos = findViewById(R.id.tabIngresos)
        tabBuscar = findViewById(R.id.tabBuscar)
        panelIngresos = findViewById(R.id.panelIngresos)
        panelBuscar = findViewById(R.id.panelBuscar)
        txtListado = findViewById(R.id.txtListado)
        txtAdentro = findViewById(R.id.txtAdentro)
        chipVer = findViewById(R.id.chipVer)
        vacioIngresos = findViewById(R.id.vacioIngresos)
        vacioBuscar = findViewById(R.id.vacioBuscar)
        edtPlaca = findViewById(R.id.edtPlaca)

        val padTop = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            header.setPadding(header.paddingLeft, padTop + bars.top, header.paddingRight, header.paddingBottom)
            v.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }

        adIngresos = ReservaAdapter({ listado }) { mostrarDetalle(it) }
        adBuscar = ReservaAdapter({ listado }) { mostrarDetalle(it) }
        findViewById<RecyclerView>(R.id.listaIngresos).apply {
            layoutManager = LinearLayoutManager(this@MainActivity); adapter = adIngresos
        }
        findViewById<RecyclerView>(R.id.listaBuscar).apply {
            layoutManager = LinearLayoutManager(this@MainActivity); adapter = adBuscar
        }

        tabIngresos.setOnClickListener { irATab(0) }
        tabBuscar.setOnClickListener { irATab(1) }
        findViewById<Button>(R.id.btnEscanear).setOnClickListener { iniciarEscaneo() }
        findViewById<Button>(R.id.btnCargar).setOnClickListener { confirmarSiHayAvance { pedirArchivo(null) } }
        findViewById<Button>(R.id.btnBluetooth).setOnClickListener { mostrarBluetooth() }
        findViewById<Button>(R.id.btnColumnas).setOnClickListener { mostrarColumnas(false) }
        findViewById<Button>(R.id.btnExportar).setOnClickListener { exportar() }
        findViewById<Button>(R.id.btnReiniciar).setOnClickListener { confirmarReinicio() }
        findViewById<View>(R.id.btnAjustes).setOnClickListener { mostrarAjustes() }
        findViewById<View>(R.id.zonaOperador).setOnClickListener { mostrarAjustes() }
        findViewById<View>(R.id.zonaImpresora).setOnClickListener { imprimirPrueba() }
        chipVer.setOnClickListener { verPendientes = !verPendientes; refrescarIngresos() }

        edtPlaca.doAfterTextChanged { refrescarBusqueda() }
        edtPlaca.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_ACTION_SEARCH) {
                ocultarTeclado(); true
            } else false
        }

        Impresora.alCambiar = { refrescarImpresora() }
        Impresora.conectar(this)

        irATab(0)
        refrescar()
        if (savedInstanceState == null) {
            manejarIntent(intent)
            if (listado == null) handler.postDelayed({ if (listado == null) mostrarColumnas(false) }, 400)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        manejarIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Impresora.conectar(this)
        refrescarImpresora()
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
            intent.action = Intent.ACTION_MAIN // que no se vuelva a importar al girar la pantalla
            confirmarSiHayAvance { importar(uri) }
        }
    }

    // ---------------------------------------------------------------- cargar listado

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
                    listado = l
                    guardar()
                    edtPlaca.setText("")
                    verPendientes = false
                    irATab(0)
                    refrescar()
                    mostrarColumnas(true)
                }.onFailure { e ->
                    mostrarError("No se pudo leer el archivo", (e.message ?: e.toString()) + "\n\nToque «Columnas» para ver cómo debe estar el Excel.")
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
                    estado.setTextColor(getColor(if (c.clave) R.color.dorado else R.color.texto_suave))
                } else {
                    val col = l.columna(c)
                    when {
                        col >= 0 -> {
                            estado.text = "✓ ${XlsxWriter.colName(col)} · ${l.encabezados[col]}".let { if (it.length > 26) it.take(26) + "…" else it }
                            estado.setTextColor(getColor(R.color.verde))
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
                            guardar(); refrescar(); pintar()
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
        val adaptador = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adaptador == null) {
            mostrarError("Sin Bluetooth", "Este equipo no tiene Bluetooth. Cargue el Excel desde archivos, WhatsApp o correo con «Cargar Excel».")
            return
        }
        val nombre = nombreBluetooth(adaptador)
        MaterialAlertDialogBuilder(this)
            .setTitle("Recibir el Excel por Bluetooth")
            .setMessage(
                "1. Toque «Activar»: se enciende el Bluetooth y este equipo queda visible 5 minutos" +
                    (if (nombre != null) " con el nombre «$nombre»." else ".") +
                    "\n\n2. En el otro teléfono abra el Excel → Compartir → Bluetooth → elija este equipo." +
                    "\n\n3. Acepte el archivo aquí y toque la notificación «Archivo recibido» para abrirlo con Reservas QR, o use «Abrir recibido»."
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

    // ---------------------------------------------------------------- escanear

    private fun iniciarEscaneo() {
        if (listado == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Falta el listado")
                .setMessage("Primero cargue el Excel con las reservas para poder verificar los QR.")
                .setPositiveButton("Cargar Excel") { _, _ -> pedirArchivo(null) }
                .setNeutralButton("Por Bluetooth") { _, _ -> mostrarBluetooth() }
                .setNegativeButton("Cancelar", null)
                .show()
            return
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
                secundario = accion("Buscar placa") { d, _ -> d.dismiss(); irATab(1) },
                principal = accion("Escanear otro") { d, _ -> d.dismiss(); iniciarEscaneo() }
            )
            return
        }
        val libre = candidatas.firstOrNull { !l.ingresado(it) }
        if (libre != null) registrar(libre, desdeBusqueda = false)
        else mostrarYaIngresado(candidatas.first(), desdeBusqueda = false)
    }

    // ---------------------------------------------------------------- registrar / mostrar

    private fun registrar(fila: Int, desdeBusqueda: Boolean) {
        val l = listado ?: return
        if (l.ingresado(fila)) {
            mostrarYaIngresado(fila, desdeBusqueda); return
        }
        l.ingresos[fila] = System.currentTimeMillis()
        guardar()
        refrescar()
        vibrar(80)
        val auto = ajustes.autoImprimir
        mostrarTarjeta(
            Estilo.OK, "✓ RESERVA INGRESADA", fila, null,
            secundario = accion((if (auto) "Reimprimir" else "Imprimir ticket")) { _, est -> imprimir(fila, auto, est) },
            principal = siguiente(desdeBusqueda),
            imprimirAhora = auto
        )
    }

    private fun mostrarYaIngresado(fila: Int, desdeBusqueda: Boolean) {
        vibrar(250)
        mostrarTarjeta(
            Estilo.AVISO, "YA INGRESÓ", fila, null,
            secundario = accion("Reimprimir") { _, est -> imprimir(fila, true, est) },
            principal = siguiente(desdeBusqueda)
        )
    }

    private fun accion(texto: String, f: (AlertDialog, TextView) -> Unit): Pair<String, (AlertDialog, TextView) -> Unit> = texto to f

    private fun siguiente(desdeBusqueda: Boolean): Pair<String, (AlertDialog, TextView) -> Unit> =
        if (desdeBusqueda) accion("Listo") { d, _ -> d.dismiss(); edtPlaca.selectAll() }
        else accion("Escanear otro") { d, _ -> d.dismiss(); iniciarEscaneo() }

    private fun mostrarDetalle(fila: Int) {
        val l = listado ?: return
        val desdeBusqueda = tab == 1
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
                principal = accion("Registrar ingreso") { d, _ -> d.dismiss(); ocultarTeclado(); registrar(fila, desdeBusqueda) }
            )
        }
    }

    private fun confirmarAnular(fila: Int) {
        val l = listado ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Anular el ingreso?")
            .setMessage("${l.titulo(fila)} volverá a quedar como pendiente.")
            .setPositiveButton("Anular") { _, _ ->
                l.ingresos.remove(fila); guardar(); refrescar()
            }
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
        val tPlaca = v.findViewById<TextView>(R.id.resPlaca)
        val tVip = v.findViewById<TextView>(R.id.resVip)
        val tNombre = v.findViewById<TextView>(R.id.resNombre)
        val tDetalle = v.findViewById<TextView>(R.id.resDetalle)
        val tImpresion = v.findViewById<TextView>(R.id.resImpresion)
        val bSec = v.findViewById<Button>(R.id.resSecundario)
        val bPri = v.findViewById<Button>(R.id.resPrincipal)

        tTitulo.text = titulo
        val (fondo, texto) = when (estilo) {
            Estilo.OK -> R.color.verde to R.color.negro
            Estilo.AVISO -> R.color.aviso to R.color.negro
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
            // Otras columnas del Excel (teléfono, puesto, etc.)
            val usadas = l.columnas.filter { it >= 0 }.toSet()
            l.encabezados.forEachIndexed { i, h ->
                val valor = l.filas[fila][i]
                if (i !in usadas && valor.isNotBlank()) det += "$h: $valor"
            }
            tDetalle.text = det.joinToString("\n")
            if (estilo == Estilo.OK && l.esVip(fila)) tTitulo.setBackgroundColor(getColor(R.color.dorado))
        } else {
            tPlaca.visibility = View.GONE
            tVip.visibility = View.GONE
            tNombre.text = textoLibre.orEmpty()
            tDetalle.text = "Este código no corresponde a ninguna reserva del listado «${l?.nombreArchivo.orEmpty()}».\nPuede buscar la placa manualmente."
        }
        tImpresion.visibility = View.GONE

        bSec.text = secundario.first
        bPri.text = principal.first
        val d = MaterialAlertDialogBuilder(this).setView(v).create()
        bSec.setOnClickListener { secundario.second(d, tImpresion) }
        bPri.setOnClickListener { principal.second(d, tImpresion) }
        d.show()

        if (imprimirAhora && fila != null) imprimir(fila, false, tImpresion)
    }

    // ---------------------------------------------------------------- imprimir

    private fun imprimir(fila: Int, reimpresion: Boolean, estado: TextView?) {
        val l = listado ?: return
        val t = l.ingresos[fila] ?: System.currentTimeMillis()
        val nombre = l.valor(fila, Listado.Campo.NOMBRE)
        val evento = l.valor(fila, Listado.Campo.EVENTO)
        val codigo = l.valor(fila, Listado.Campo.CODIGO)
        val lineas = Ticket.lineas(
            negocio = ajustes.negocio,
            titulo = "RESERVA INGRESADA",
            nombre = nombre,
            codigo = codigo,
            placa = l.valor(fila, Listado.Campo.PLACA),
            evento = evento,
            tipo = l.valor(fila, Listado.Campo.TIPO),
            vip = l.esVip(fila),
            fechaHora = fmtFecha.format(Date(t)),
            mensaje = ajustes.textoBienvenida(nombre, evento),
            mensajeVip = ajustes.mensajeVip,
            qr = if (ajustes.imprimirQr && codigo.isNotBlank()) codigo else null,
            reimpresion = reimpresion,
            operador = ajustes.operador.takeIf { it != "Operador" }.orEmpty()
        )
        enviarAImpresora(lineas, estado)
    }

    private fun imprimirPrueba() {
        val lineas = Ticket.lineas(
            negocio = ajustes.negocio,
            titulo = "RESERVA INGRESADA",
            nombre = "Cliente de prueba",
            codigo = "R-0000",
            placa = "ABC123",
            evento = "Evento de prueba",
            tipo = "VIP",
            vip = true,
            fechaHora = fmtFecha.format(Date()),
            mensaje = ajustes.textoBienvenida("Cliente de prueba", "Evento de prueba"),
            mensajeVip = ajustes.mensajeVip,
            qr = if (ajustes.imprimirQr) "R-0000" else null,
            reimpresion = false,
            operador = ajustes.operador.takeIf { it != "Operador" }.orEmpty()
        )
        enviarAImpresora(lineas, null)
    }

    private fun enviarAImpresora(lineas: List<Linea>, estado: TextView?) {
        val sunmi = Impresora.esSunmi
        estado?.visibility = View.VISIBLE
        estado?.text = if (sunmi) "🖨 Imprimiendo ticket…" else "🖨 Abriendo impresión de Android…"
        if (estado == null && sunmi) toast("Imprimiendo ticket…")
        Impresora.imprimir(this, lineas) { error ->
            if (error == null) {
                estado?.text = if (sunmi) "✓ Ticket impreso. Entréguelo al cliente." else "Ticket enviado a la impresión de Android."
            } else {
                if (estado != null) {
                    estado.text = "⚠ $error"
                    estado.setTextColor(getColor(R.color.aviso))
                } else {
                    mostrarError("No se imprimió", error)
                }
            }
        }
    }

    // ---------------------------------------------------------------- exportar / plantilla

    private fun exportar() {
        val l = listado
        if (l == null) {
            toast("No hay listado para exportar."); return
        }
        val base = l.nombreArchivo.substringBeforeLast('.').ifBlank { "reservas" }
        val filas = l.paraExportar(fmtFecha)
        val verdes = l.ingresos.keys.map { it + 1 }.toSet()
        compartirXlsx(
            "${base}_ingresos.xlsx", filas, verdes,
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

    private fun compartirXlsx(nombre: String, filas: List<List<String>>, verdes: Set<Int>, asunto: String, texto: String) {
        try {
            val dir = File(cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val limpio = nombre.replace(Regex("[^\\p{L}\\p{N} _.-]"), "_")
            val f = File(dir, limpio)
            f.outputStream().use { XlsxWriter.write(it, filas, verdes) }
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
            .setMessage("Se quitarán los ${l.ingresos.size} ingresos y todas las reservas quedarán pendientes. El listado se conserva.")
            .setPositiveButton("Borrar ingresos") { _, _ ->
                l.ingresos.clear(); guardar(); refrescar()
            }
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
        op.setText(ajustes.operador.takeIf { it != "Operador" }.orEmpty())
        neg.setText(ajustes.negocio)
        bien.setText(ajustes.bienvenida)
        vip.setText(ajustes.mensajeVip)
        auto.isChecked = ajustes.autoImprimir
        qr.isChecked = ajustes.imprimirQr

        fun guardarCampos() {
            ajustes.operador = op.text.toString()
            ajustes.negocio = neg.text.toString()
            ajustes.bienvenida = bien.text.toString().ifBlank { Ajustes.BIENVENIDA }
            ajustes.mensajeVip = vip.text.toString()
            ajustes.autoImprimir = auto.isChecked
            ajustes.imprimirQr = qr.isChecked
            refrescar()
        }
        v.findViewById<Button>(R.id.ajPrueba).setOnClickListener { guardarCampos(); imprimirPrueba() }

        MaterialAlertDialogBuilder(this)
            .setTitle("Ajustes")
            .setView(v)
            .setPositiveButton("Guardar") { _, _ -> guardarCampos() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------- pantalla

    private fun irATab(t: Int) {
        tab = t
        val activo = listOf(tabIngresos, tabBuscar)
        activo.forEachIndexed { i, v ->
            val sel = i == t
            v.setBackgroundResource(if (sel) R.drawable.tab_activa else R.drawable.tab_inactiva)
            v.setTextColor(getColor(if (sel) R.color.negro else R.color.texto_suave))
        }
        panelIngresos.visibility = if (t == 0) View.VISIBLE else View.GONE
        panelBuscar.visibility = if (t == 1) View.VISIBLE else View.GONE
        if (t == 1) {
            refrescarBusqueda()
            edtPlaca.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(edtPlaca, InputMethodManager.SHOW_IMPLICIT)
        } else ocultarTeclado()
    }

    private fun refrescar() {
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

        val pill = SpannableString("●  $dentro/$total")
        pill.setSpan(ForegroundColorSpan(getColor(R.color.verde)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        txtPill.text = pill

        tabIngresos.text = "Ingresos · $dentro"
        txtListado.text = if (l == null) "Sin listado. Toque «Columnas» para ver qué debe tener el Excel."
        else "📄 ${l.nombreArchivo}  ·  $total reservas"

        refrescarImpresora()
        refrescarIngresos()
        if (tab == 1) refrescarBusqueda()
    }

    private fun refrescarImpresora() {
        if (!::txtImpresora.isInitialized) return
        if (Impresora.esSunmi) {
            val s = SpannableString("Imprime en Sunmi · lista")
            s.setSpan(StyleSpan(Typeface.BOLD), 11, 16, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(ForegroundColorSpan(getColor(R.color.texto)), 11, 16, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            txtImpresora.text = s
        } else {
            txtImpresora.text = "Sin impresora Sunmi · usará la impresión de Android"
        }
    }

    private fun refrescarIngresos() {
        val l = listado
        val dentro = l?.ingresos?.size ?: 0
        val pend = (l?.filas?.size ?: 0) - dentro
        txtAdentro.text = "$dentro adentro · $pend pendientes"
        chipVer.text = if (verPendientes) "Ver ingresados" else "Ver pendientes"
        chipVer.visibility = if (l == null) View.GONE else View.VISIBLE
        val filas = when {
            l == null -> emptyList<Int>()
            verPendientes -> l.pendientes()
            else -> l.ingresados()
        }
        adIngresos.actualizar(filas)
        vacioIngresos.visibility = if (filas.isEmpty()) View.VISIBLE else View.GONE
        vacioIngresos.text = when {
            l == null -> "Cargue el listado de reservas con «Cargar Excel» o «Bluetooth»."
            verPendientes -> "¡Todas las reservas ya ingresaron!"
            else -> "Aún no hay ingresos.\nEscanee el QR de la primera reserva."
        }
    }

    private fun refrescarBusqueda() {
        val l = listado
        val q = edtPlaca.text?.toString().orEmpty()
        val filas = if (l == null || q.isBlank()) emptyList<Int>() else l.buscarTexto(q, 100)
        adBuscar.actualizar(filas)
        vacioBuscar.visibility = if (filas.isEmpty()) View.VISIBLE else View.GONE
        vacioBuscar.text = when {
            l == null -> "Primero cargue el listado de reservas."
            q.isBlank() -> "Escriba la placa para buscar la reserva.\nToque el resultado para registrar el ingreso e imprimir el ticket."
            else -> "No hay reservas con «${q.trim()}».\nRevise la placa o busque por nombre."
        }
    }

    private fun guardar() {
        try {
            Persistencia.guardar(archivoEstado, listado)
        } catch (e: Exception) {
            toast("No se pudo guardar: ${e.message}")
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
