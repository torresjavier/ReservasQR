package com.javier.reservas

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Apariencia de la app que el operador puede cambiar en Ajustes:
 *  - tamaño de las letras (se aplica a todo el texto, que está en «sp»),
 *  - tamaño de los iconos (los iconos marcados con android:tag="icono" y los iconos de los botones),
 *  - color de los botones: rojo, blanco, gris o negro.
 *
 * Todo se guarda en las mismas preferencias de Ajustes y se lee antes de que arranque la operación, así que
 * cada pantalla (BaseActivity) se arma ya con los valores elegidos.
 */
object Apariencia {

    const val CLAVE_LETRAS = "apLetras"
    const val CLAVE_ICONOS = "apIconos"
    const val CLAVE_BOTONES = "apBotones"

    const val LETRAS_NORMAL = 1
    const val ICONOS_NORMAL = 1

    const val BOTONES_ROJO = "rojo"
    const val BOTONES_BLANCO = "blanco"
    const val BOTONES_GRIS = "gris"
    const val BOTONES_NEGRO = "negro"

    /** Nombre en pantalla y factor de cada opción. La posición es lo que se guarda. */
    val LETRAS: List<Pair<String, Float>> = listOf(
        "Pequeñas" to 0.88f,
        "Normales" to 1f,
        "Grandes" to 1.15f,
        "Muy grandes" to 1.3f
    )
    val ICONOS: List<Pair<String, Float>> = listOf(
        "Pequeños" to 0.8f,
        "Normales" to 1f,
        "Grandes" to 1.25f,
        "Muy grandes" to 1.5f
    )
    val BOTONES: List<Pair<String, String>> = listOf(
        BOTONES_ROJO to "Rojos",
        BOTONES_BLANCO to "Blancos",
        BOTONES_GRIS to "Grises",
        BOTONES_NEGRO to "Negros"
    )

    private fun prefs(c: Context) = c.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    fun posicionLetras(c: Context): Int = prefs(c).getInt(CLAVE_LETRAS, LETRAS_NORMAL).coerceIn(0, LETRAS.size - 1)

    fun posicionIconos(c: Context): Int = prefs(c).getInt(CLAVE_ICONOS, ICONOS_NORMAL).coerceIn(0, ICONOS.size - 1)

    fun colorBotones(c: Context): String {
        val v = prefs(c).getString(CLAVE_BOTONES, BOTONES_ROJO) ?: BOTONES_ROJO
        return if (BOTONES.any { it.first == v }) v else BOTONES_ROJO
    }

    fun escalaLetras(c: Context): Float = LETRAS[posicionLetras(c)].second

    fun escalaIconos(c: Context): Float = ICONOS[posicionIconos(c)].second

    /** Estilo (overlay del tema) con los colores de los botones elegidos. */
    fun estiloBotones(c: Context): Int = when (colorBotones(c)) {
        BOTONES_BLANCO -> R.style.Botones_Blanco
        BOTONES_GRIS -> R.style.Botones_Gris
        BOTONES_NEGRO -> R.style.Botones_Negro
        else -> R.style.Botones_Rojo
    }

    /** El mismo contexto, pero con el tamaño de letra elegido (reemplaza al de la configuración del sistema). */
    fun conLetras(base: Context): Context {
        val escala = escalaLetras(base)
        val actual = base.resources.configuration
        if (Math.abs(actual.fontScale - escala) < 0.001f) return base
        val cfg = Configuration(actual)
        cfg.fontScale = escala
        return base.createConfigurationContext(cfg)
    }

    // ---------------------------------------------------------------- iconos

    /**
     * Ajusta el tamaño de los iconos de una pantalla o diálogo ya inflado: los ImageView con android:tag="icono"
     * (tamaño y márgenes internos) y el icono de cada botón. Se puede llamar varias veces: parte siempre del tamaño
     * original, que guarda la primera vez.
     */
    fun iconos(raiz: View?) {
        if (raiz == null) return
        val k = escalaIconos(raiz.context)
        recorrer(raiz, k)
    }

    private fun recorrer(v: View, k: Float) {
        escalar(v, k)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) recorrer(v.getChildAt(i), k)
        }
    }

    private fun escalar(v: View, k: Float) {
        if (v is MaterialButton) {
            if (v.icon != null) {
                val guardado = v.getTag(R.id.tag_icono_base)
                val base = if (guardado is Int) guardado else v.iconSize
                if (guardado !is Int) v.setTag(R.id.tag_icono_base, base)
                if (base > 0) v.iconSize = Math.round(base * k)
            }
            // Botón redondo de solo icono (por ejemplo «Leer placa»): crece junto con su icono
            if (v.tag == "icono") escalarCaja(v, k)
            return
        }
        if (v is ImageView && v.tag == "icono") escalarCaja(v, k)
    }

    private fun escalarCaja(v: View, k: Float) {
        val lp = v.layoutParams ?: return
        val guardado = v.getTag(R.id.tag_icono_caja)
        val base: IntArray = if (guardado is IntArray) guardado else intArrayOf(
            lp.width, lp.height, v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom
        ).also { v.setTag(R.id.tag_icono_caja, it) }
        if (base[0] > 0) lp.width = Math.round(base[0] * k)
        if (base[1] > 0) lp.height = Math.round(base[1] * k)
        v.setPadding(
            Math.round(base[2] * k), Math.round(base[3] * k),
            Math.round(base[4] * k), Math.round(base[5] * k)
        )
        v.layoutParams = lp
    }
}

/**
 * Base de las pantallas de la app: aplica el tamaño de letra y el color de botones elegidos en Ajustes.
 */
open class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Apariencia.conLetras(newBase))
    }

    /** Evita que AppCompat pierda el tamaño de letra al aplicar el modo claro/oscuro. */
    override fun applyOverrideConfiguration(overrideConfiguration: Configuration?) {
        if (overrideConfiguration != null) {
            val uiMode = overrideConfiguration.uiMode
            overrideConfiguration.setTo(baseContext.resources.configuration)
            overrideConfiguration.uiMode = uiMode
        }
        super.applyOverrideConfiguration(overrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Apariencia.estiloBotones(this), true)
        super.onCreate(savedInstanceState)
    }

    /** Ajusta los iconos de toda la pantalla (llamar justo después de setContentView). */
    protected fun ajustarIconos() {
        Apariencia.iconos(findViewById<View>(android.R.id.content))
    }
}
