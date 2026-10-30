package com.javier.reservas

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Mantiene viva la red entre equipos cuando la app no está en pantalla (o el equipo tiene la pantalla apagada).
 * Muestra una notificación fija porque Android lo exige a todo lo que trabaja en segundo plano.
 * Mientras corre, mantiene despierto el Wi-Fi y recibe los avisos de los otros equipos.
 */
class ServicioRed : Service() {

    private var multicast: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var despierto: PowerManager.WakeLock? = null
    private var ultimoTexto = ""

    private val alCambiarRed: () -> Unit = { actualizarNotificacion() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Operacion.iniciar(applicationContext)
        crearCanal()
        RedLocal.escuchar(alCambiarRed)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android exige pasar a primer plano enseguida, aunque después haya que cerrar el servicio.
        try {
            ServiceCompat.startForeground(
                this, ID_NOTIFICACION, notificacion(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            )
        } catch (_: Exception) {
            // Android no dejó pasar a primer plano: la red sigue funcionando mientras la app esté abierta.
            RedLocal.arrancar()
            stopSelf()
            return START_NOT_STICKY
        }
        val a = Operacion.ajustes
        if (!a.redActiva) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        tomarCerrojos()
        RedLocal.arrancar()   // si la red entre equipos está apagada, no hace nada
        return START_STICKY
    }

    override fun onDestroy() {
        RedLocal.dejarDeEscuchar(alCambiarRed)
        soltarCerrojos()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Wi-Fi y CPU despiertos

    @Suppress("DEPRECATION")
    private fun tomarCerrojos() {
        if (multicast != null) return
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // Sin esto, algunos equipos descartan los avisos «aquí estoy» (difusión) para ahorrar batería.
            multicast = wm.createMulticastLock("reservas:difusion").apply {
                setReferenceCounted(false)
                acquire()
            }
            val modo = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(modo, "reservas:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        try {
            val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            despierto = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "reservas:red").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun soltarCerrojos() {
        try { multicast?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        try { despierto?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        multicast = null
        wifiLock = null
        despierto = null
    }

    // ---------------------------------------------------------------- notificación

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CANAL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CANAL, "Red entre equipos", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mantiene la comunicación con los otros equipos cercanos en segundo plano"
                setShowBadge(false)
            }
        )
    }

    private fun notificacion(): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        ultimoTexto = textoNotificacion()
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_red)
            .setContentTitle("Reservas QR · " + Operacion.ajustes.nombreEquipo)
            .setContentText(ultimoTexto)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(abrir)
            .build()
    }

    /** Lo que dice la notificación fija: cómo va la red entre equipos. */
    private fun textoNotificacion(): String = RedLocal.resumen()

    private fun actualizarNotificacion() {
        if (textoNotificacion() == ultimoTexto) return
        try {
            getSystemService(NotificationManager::class.java)?.notify(ID_NOTIFICACION, notificacion())
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CANAL = "red_equipos"
        private const val ID_NOTIFICACION = 4587
    }
}
