package com.javier.reservas

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Avisos al operador cuando lee una reserva:
 *  - lectura buena → un pitido corto (si el sonido está apagado en Ajustes o el volumen está en cero, una vibración breve);
 *  - reserva que no se encuentra, repetida o con algo raro → el equipo vibra (cada pantalla lo hace con su propio patrón).
 */
object Avisos {

    private val main = Handler(Looper.getMainLooper())
    private var tono: ToneGenerator? = null
    private val soltar = Runnable {
        try {
            tono?.release()
        } catch (_: Exception) {
        }
        tono = null
    }

    /** Confirma una lectura buena. */
    fun leida(ctx: Context) {
        if (Ajustes(ctx).sonidoAlLeer && pitar(ctx)) return
        vibrar(ctx, longArrayOf(0, 80))
    }

    /** @return false si no se pudo sonar (sin volumen o sin generador de tonos) */
    private fun pitar(ctx: Context): Boolean {
        return try {
            val am = ctx.getSystemService(AudioManager::class.java)
            if (am != null && am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) return false
            var t = tono
            if (t == null) {
                t = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                tono = t
            }
            t.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            main.removeCallbacks(soltar)
            main.postDelayed(soltar, 4_000)
            true
        } catch (_: Exception) {
            tono = null
            false
        }
    }

    @Suppress("DEPRECATION")
    fun vibrar(ctx: Context, patron: LongArray) {
        try {
            val v = ctx.getSystemService(Vibrator::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(patron, -1))
            else v.vibrate(patron, -1)
        } catch (_: Exception) {
        }
    }
}
