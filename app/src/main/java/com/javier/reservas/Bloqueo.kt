package com.javier.reservas

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Pide la clave de desbloqueo del equipo (PIN, patrón o contraseña) para entrar a la app,
 * y otra vez si la app estuvo en segundo plano más de [GRACIA_MS].
 */
object Bloqueo : DefaultLifecycleObserver {

    private const val GRACIA_MS = 30_000L

    @Volatile var desbloqueado = false
    /** true mientras se muestra la pantalla del sistema para escribir la clave. */
    @Volatile var autenticando = false
    private var enFondoDesde = 0L
    private var instalado = false

    fun instalar() {
        if (instalado) return
        instalado = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    val requiereClave: Boolean get() = !desbloqueado

    override fun onStop(owner: LifecycleOwner) {
        if (!autenticando) enFondoDesde = System.currentTimeMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        if (autenticando) return
        if (enFondoDesde > 0 && System.currentTimeMillis() - enFondoDesde > GRACIA_MS) desbloqueado = false
        enFondoDesde = 0
    }
}
