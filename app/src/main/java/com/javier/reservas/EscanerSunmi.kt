package com.javier.reservas

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel

/**
 * Lector de códigos integrado de los Sunmi (V3H, V2 Pro, L2…).
 * Se conecta al servicio "com.sunmi.scanner" para encender el lector desde un botón.
 * Los códigos leídos llegan por el broadcast [ACCION_DATOS] (extra "data") o como teclado.
 */
object EscanerSunmi {

    const val ACCION_DATOS = "com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED"
    const val EXTRA_DATOS = "data"

    private const val PAQUETE = "com.sunmi.scanner"
    private const val DESCRIPTOR = "com.sunmi.scanner.IScanInterface"

    // Orden de métodos de IScanInterface: sendKeyEvent(0), scan(1), stop(2), getScannerModel(3)
    private const val SCAN = 1
    private const val STOP = 2
    private const val MODELO = 3
    private const val SIN_LECTOR = 100

    @Volatile private var binder: IBinder? = null
    private var conectando = false

    /** Modelo del lector según Sunmi (100 = el equipo no trae lector). */
    var modelo: Int = -1
        private set

    var alCambiar: (() -> Unit)? = null

    val disponible: Boolean
        get() = binder?.isBinderAlive == true && modelo != SIN_LECTOR

    private val conexion = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            conectando = false
            modelo = llamarEntero(MODELO) ?: -1
            alCambiar?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            conectando = false
            alCambiar?.invoke()
        }
    }

    fun conectar(ctx: Context) {
        if (binder != null || conectando) return
        try {
            val i = Intent(DESCRIPTOR).setPackage(PAQUETE)
            conectando = ctx.applicationContext.bindService(i, conexion, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            conectando = false
        }
    }

    /** Enciende el lector (la luz roja). Devuelve false si no se pudo. */
    fun escanear(): Boolean = llamar(SCAN)

    fun detener(): Boolean = llamar(STOP)

    private fun llamar(codigo: Int): Boolean {
        val b = binder ?: return false
        val datos = Parcel.obtain()
        val respuesta = Parcel.obtain()
        return try {
            datos.writeInterfaceToken(DESCRIPTOR)
            b.transact(IBinder.FIRST_CALL_TRANSACTION + codigo, datos, respuesta, 0)
            respuesta.readException()
            true
        } catch (e: Exception) {
            false
        } finally {
            datos.recycle()
            respuesta.recycle()
        }
    }

    private fun llamarEntero(codigo: Int): Int? {
        val b = binder ?: return null
        val datos = Parcel.obtain()
        val respuesta = Parcel.obtain()
        return try {
            datos.writeInterfaceToken(DESCRIPTOR)
            b.transact(IBinder.FIRST_CALL_TRANSACTION + codigo, datos, respuesta, 0)
            respuesta.readException()
            respuesta.readInt()
        } catch (e: Exception) {
            null
        } finally {
            datos.recycle()
            respuesta.recycle()
        }
    }
}

/** Datos que comparte la pantalla principal con la cámara de placas. */
object Compartido {
    @Volatile var listado: Listado? = null
}
