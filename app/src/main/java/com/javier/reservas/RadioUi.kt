package com.javier.reservas

import android.Manifest
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * El botón de la radio (walkie-talkie) que flota en el borde derecho de la pantalla. Se mantiene pulsado para hablar:
 * se pone ROJO mientras transmite; se pone VERDE con el nombre de quien habla cuando se oye a otro equipo.
 */
object RadioUi {

    /** Lo que la pantalla debe llamar en onResume / onPause. */
    class Enlace(private val reanudarF: () -> Unit, private val pausarF: () -> Unit) {
        fun reanudar() = reanudarF()
        fun pausar() = pausarF()
    }

    /** Agrega el botón a la pantalla. Llamar en onCreate, después de setContentView. */
    fun adjuntar(act: AppCompatActivity): Enlace {
        val permiso = act.registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            Toast.makeText(
                act,
                if (ok) "Micrófono permitido: mantenga pulsado «RADIO» para hablar." else "Sin permiso del micrófono no se puede hablar por la radio (sí se oye).",
                Toast.LENGTH_LONG
            ).show()
        }
        val densidad = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * densidad).toInt()

        val boton = TextView(act).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(14), 0, dp(14), 0)
            minWidth = dp(104)
            elevation = dp(6).toFloat()
        }
        val raiz = act.findViewById<ViewGroup>(android.R.id.content) ?: return Enlace({}, {})
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56), Gravity.END or Gravity.CENTER_VERTICAL)
        lp.marginEnd = dp(6)
        raiz.addView(boton, lp)

        fun fondo(color: Int): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(28).toFloat()
            setColor(color)
            setStroke(dp(2), Color.argb(150, 255, 255, 255))
        }

        fun pintar() {
            if (!Operacion.ajustes.radioActiva) {
                boton.visibility = View.GONE
                return
            }
            boton.visibility = View.VISIBLE
            when (Radio.estado) {
                Radio.Estado.HABLANDO -> {
                    boton.text = "● HABLANDO"
                    boton.background = fondo(Color.rgb(211, 47, 47))
                }
                Radio.Estado.ESCUCHANDO -> {
                    boton.text = "🔊 " + Radio.quien.take(14)
                    boton.background = fondo(Color.rgb(46, 125, 50))
                }
                Radio.Estado.REPOSO -> {
                    boton.text = "🎙 RADIO"
                    boton.background = fondo(Color.argb(235, 38, 38, 38))
                }
            }
        }

        boton.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!Radio.tienePermiso(act)) {
                        permiso.launch(Manifest.permission.RECORD_AUDIO)
                    } else if (Radio.hablar(act)) {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    } else {
                        Toast.makeText(act, "No se pudo usar el micrófono.", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Radio.soltar()
                    true
                }
                else -> true
            }
        }

        val oyente: () -> Unit = { pintar() }
        pintar()
        return Enlace(
            reanudarF = {
                Radio.dejarDeEscuchar(oyente)   // por si ya estaba (se llama también al guardar Ajustes)
                Radio.escuchar(oyente)
                Radio.aplicarAjustes()
                pintar()
            },
            pausarF = {
                Radio.dejarDeEscuchar(oyente)
                Radio.soltar()
            }
        )
    }
}
