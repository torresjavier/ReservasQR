package com.javier.reservas

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Cola de registros por enviar al servidor. Se guarda en un archivo para no perder nada
 * si se apaga el equipo o no hay internet.
 */
object Bandeja {
    private const val ARCHIVO = "bandeja.json"

    @Volatile var pendientes: Int = -1
        private set

    private fun archivo(ctx: Context) = File(ctx.applicationContext.filesDir, ARCHIVO)

    private fun leer(ctx: Context): JSONArray = try {
        val f = archivo(ctx)
        if (f.exists()) JSONArray(f.readText()) else JSONArray()
    } catch (e: Exception) {
        JSONArray()
    }

    private fun escribir(ctx: Context, a: JSONArray) {
        val f = archivo(ctx)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(f)) {
            f.writeText(a.toString()); tmp.delete()
        }
        pendientes = a.length()
    }

    @Synchronized fun agregar(ctx: Context, ev: JSONObject) {
        val a = leer(ctx)
        a.put(ev)
        escribir(ctx, a)
    }

    @Synchronized fun cantidad(ctx: Context): Int {
        if (pendientes < 0) pendientes = leer(ctx).length()
        return pendientes
    }

    @Synchronized fun primeros(ctx: Context, n: Int): List<JSONObject> {
        val a = leer(ctx)
        return (0 until minOf(n, a.length())).map { a.getJSONObject(it) }
    }

    @Synchronized fun quitar(ctx: Context, ids: Set<String>) {
        val a = leer(ctx)
        val queda = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("id_evento") !in ids) queda.put(o)
        }
        escribir(ctx, queda)
    }
}

/** Conexión simple por HTTPS/HTTP sin librerías extra. */
object Http {
    fun pedir(metodo: String, url: String, token: String, cuerpo: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = metodo
            c.connectTimeout = 15_000
            c.readTimeout = 45_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "ReservasQR/" + Build.MODEL)
            if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
            if (cuerpo != null) {
                val bytes = cuerpo.toByteArray(Charsets.UTF_8)
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val codigo = c.responseCode
            val flujo = if (codigo < 400) c.inputStream else c.errorStream
            val texto = flujo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return codigo to texto
        } finally {
            c.disconnect()
        }
    }
}

/** Programa los envíos y descargas en segundo plano (WorkManager: solo con internet, cuida la batería). */
object Sincronizacion {

    /** Nunca se dejan más de 10 registros esperando: al llegar a ese número se envía de inmediato. */
    const val MAX_PENDIENTES = 10
    private const val ENVIO = "envio_registros"
    private const val ENVIO_PERIODICO = "envio_registros_periodico"
    private const val RESERVAS = "descarga_reservas"
    private const val RESERVAS_PERIODICO = "descarga_reservas_periodico"

    @Volatile var ultimoEnvio: Long = 0L
    @Volatile var ultimoError: String? = null
    @Volatile var ultimaDescarga: Long = 0L

    private val conInternet = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun configuradoEnvio(ctx: Context) = Ajustes(ctx).urlEnvio.isNotBlank()
    fun configuradoReservas(ctx: Context) = Ajustes(ctx).urlReservas.isNotBlank()

    /** Se llama después de cada registro: envía cuando se junta el lote. */
    fun alRegistrar(ctx: Context) {
        if (!configuradoEnvio(ctx)) return
        val n = Bandeja.cantidad(ctx)
        if (n >= Ajustes(ctx).loteEnvio || n >= MAX_PENDIENTES) enviarAhora(ctx)
    }

    fun enviarAhora(ctx: Context) {
        if (!configuradoEnvio(ctx)) return
        val w = OneTimeWorkRequestBuilder<EnvioWorker>()
            .setConstraints(conInternet)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(ENVIO, ExistingWorkPolicy.KEEP, w)
    }

    fun actualizarReservasAhora(ctx: Context) {
        if (!configuradoReservas(ctx)) return
        val w = OneTimeWorkRequestBuilder<ReservasWorker>()
            .setConstraints(conInternet)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(RESERVAS, ExistingWorkPolicy.REPLACE, w)
    }

    /** Tareas periódicas (cada 15 min, el mínimo de Android) para enviar lo que quede y traer reservas nuevas. */
    fun programar(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (configuradoEnvio(ctx)) {
            wm.enqueueUniquePeriodicWork(
                ENVIO_PERIODICO, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<EnvioWorker>(15, TimeUnit.MINUTES).setConstraints(conInternet).build()
            )
        } else wm.cancelUniqueWork(ENVIO_PERIODICO)

        if (configuradoReservas(ctx) && Ajustes(ctx).autoReservas) {
            wm.enqueueUniquePeriodicWork(
                RESERVAS_PERIODICO, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReservasWorker>(15, TimeUnit.MINUTES).setConstraints(conInternet).build()
            )
        } else wm.cancelUniqueWork(RESERVAS_PERIODICO)
    }
}

/** Envía la bandeja en lotes de máximo 10 registros, con su foto en base64. */
class EnvioWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        Operacion.iniciar(ctx)
        val aj = Ajustes(ctx)
        val url = aj.urlEnvio
        if (url.isBlank()) return Result.success()

        repeat(30) { // hasta 300 registros por ejecución
            val lote = Bandeja.primeros(ctx, Sincronizacion.MAX_PENDIENTES)
            if (lote.isEmpty()) {
                Sincronizacion.ultimoError = null
                Operacion.avisar()
                return Result.success()
            }
            val registros = JSONArray()
            val ids = HashSet<String>()
            for (ev in lote) {
                val r = JSONObject(ev.toString())
                val nombreFoto = r.optString("foto_archivo", "")
                r.remove("foto_archivo")
                if (nombreFoto.isNotBlank()) {
                    val f = File(ctx.filesDir, nombreFoto)
                    if (f.exists()) {
                        r.put("foto", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
                        r.put("foto_tipo", "image/jpeg")
                    }
                }
                registros.put(r)
                ids.add(ev.optString("id_evento"))
            }
            val cuerpo = JSONObject().apply {
                put("dispositivo", aj.idDispositivo)
                put("modelo", Build.MODEL ?: "")
                put("operador", aj.operador)
                put("enviado", Operacion.iso(System.currentTimeMillis()))
                put("cantidad", registros.length())
                put("registros", registros)
            }
            val respuesta = try {
                Http.pedir("POST", url, aj.token, cuerpo.toString())
            } catch (e: Exception) {
                Sincronizacion.ultimoError = "Sin conexión con el servidor"
                Operacion.avisar()
                return Result.retry()
            }
            val (codigo, texto) = respuesta
            when {
                codigo in 200..299 -> {
                    // Si el servidor dice cuáles recibió, se quitan solo esos
                    val recibidos = try {
                        JSONObject(texto).optJSONArray("recibidos")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    } catch (_: Exception) {
                        null
                    }
                    Bandeja.quitar(ctx, recibidos?.takeIf { it.isNotEmpty() } ?: ids)
                    Sincronizacion.ultimoEnvio = System.currentTimeMillis()
                    Sincronizacion.ultimoError = null
                    Operacion.avisar()
                }
                codigo == 401 || codigo == 403 -> {
                    Sincronizacion.ultimoError = "El servidor rechazó la clave (token)"
                    Operacion.avisar()
                    return Result.failure()
                }
                codigo in 400..499 && codigo != 408 && codigo != 429 -> {
                    Sincronizacion.ultimoError = "El servidor respondió $codigo"
                    Operacion.avisar()
                    return Result.failure()
                }
                else -> {
                    Sincronizacion.ultimoError = "El servidor respondió $codigo, se reintentará"
                    Operacion.avisar()
                    return Result.retry()
                }
            }
        }
        return Result.success()
    }
}

/** Descarga reservas nuevas o cambiadas del servidor y las agrega al listado del equipo. */
class ReservasWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        Operacion.iniciar(ctx)
        val aj = Ajustes(ctx)
        val base = aj.urlReservas
        if (base.isBlank()) return Result.success()

        val sep = if (base.contains("?")) "&" else "?"
        val url = base + sep + "dispositivo=" + URLEncoder.encode(aj.idDispositivo, "UTF-8") +
            (if (aj.ultimaSincReservas.isNotBlank()) "&desde=" + URLEncoder.encode(aj.ultimaSincReservas, "UTF-8") else "")

        val (codigo, texto) = try {
            Http.pedir("GET", url, aj.token, null)
        } catch (e: Exception) {
            Sincronizacion.ultimoError = "No se pudieron descargar reservas"
            Operacion.avisar()
            return Result.retry()
        }
        if (codigo !in 200..299) {
            Sincronizacion.ultimoError = "Reservas: el servidor respondió $codigo"
            Operacion.avisar()
            return if (codigo >= 500) Result.retry() else Result.failure()
        }

        return try {
            val t = texto.trim()
            val lista: JSONArray
            var hora = ""
            if (t.startsWith("[")) {
                lista = JSONArray(t)
            } else {
                val o = JSONObject(t)
                lista = o.optJSONArray("reservas") ?: JSONArray()
                hora = o.optString("hora", "")
            }
            // Columnas = todas las claves que trae el servidor
            val encabezados = ArrayList<String>()
            for (i in 0 until lista.length()) {
                val o = lista.optJSONObject(i) ?: continue
                o.keys().forEach { k -> if (k !in encabezados) encabezados.add(k) }
            }
            val filas = ArrayList<List<String>>()
            for (i in 0 until lista.length()) {
                val o = lista.optJSONObject(i) ?: continue
                filas.add(encabezados.map { k -> if (o.isNull(k)) "" else o.optString(k, "") })
            }
            if (filas.isNotEmpty()) {
                Operacion.enPrincipal { Operacion.fusionarDelServidor(encabezados, filas) }
            }
            aj.ultimaSincReservas = hora.ifBlank { Operacion.iso(System.currentTimeMillis()) }
            Sincronizacion.ultimaDescarga = System.currentTimeMillis()
            Operacion.avisar()
            Result.success()
        } catch (e: Exception) {
            Sincronizacion.ultimoError = "Reservas: respuesta no válida del servidor"
            Operacion.avisar()
            Result.failure()
        }
    }
}
