package com.javier.reservas;

import java.util.List;

/**
 * Decide cómo queda una reserva en este equipo según TODO lo que se sabe de ella por la red de equipos.
 * El resultado no depende del orden en que llegaron los eventos ni de si se repiten: llegar tarde o dos veces da lo mismo.
 *
 *  - Una validación vale si es más nueva que la última anulación de validación (igual para el ingreso).
 *  - Si la red dice que está validada/ingresada y aquí no, se pone (con la hora más temprana que se sepa).
 *  - Si la red dice que se anuló y aquí sigue igual que antes de la anulación, se quita.
 *  - Lo que este equipo hizo después de la anulación no se toca.
 * Sin nada de Android.
 */
public final class EstadoCompartido {

    private EstadoCompartido() {}

    public static final class Cambio {
        /** Hora de validación que hay que poner aquí (null = dejar como está). */
        public Long validacion;
        public boolean quitarValidacion;
        public Long ingreso;
        public boolean quitarIngreso;
        /** Evento de otro equipo que originó la validación / el ingreso que se pone (para saber quién fue). */
        public EventoRed origenValidacion;
        public EventoRed origenIngreso;

        public boolean hayCambio() {
            return validacion != null || quitarValidacion || ingreso != null || quitarIngreso;
        }
    }

    /**
     * @param eventos         todo lo que se sabe de la reserva en el día de hoy (en cualquier orden)
     * @param validacionLocal hora de validación que ya tiene este equipo (null si no tiene)
     * @param ingresoLocal    hora de ingreso que ya tiene este equipo (null si no tiene)
     */
    public static Cambio calcular(List<EventoRed> eventos, Long validacionLocal, Long ingresoLocal) {
        long ultAV = Long.MIN_VALUE, ultAI = Long.MIN_VALUE;
        for (EventoRed e : eventos) {
            if (EventoRed.ANULA_VALIDACION.equals(e.tipo)) ultAV = Math.max(ultAV, e.hora);
            else if (EventoRed.ANULA_INGRESO.equals(e.tipo)) ultAI = Math.max(ultAI, e.hora);
        }
        EventoRed primeraV = null, primeraI = null;
        for (EventoRed e : eventos) {
            if (EventoRed.VALIDACION.equals(e.tipo) && e.hora > ultAV) {
                if (primeraV == null || e.hora < primeraV.hora) primeraV = e;
            } else if (EventoRed.INGRESO.equals(e.tipo) && e.hora > ultAI) {
                if (primeraI == null || e.hora < primeraI.hora) primeraI = e;
            }
        }
        Cambio c = new Cambio();
        if (primeraV != null) {
            if (validacionLocal == null) {
                c.validacion = primeraV.hora;
                c.origenValidacion = primeraV;
            }
        } else if (validacionLocal != null && ultAV != Long.MIN_VALUE && validacionLocal <= ultAV) {
            c.quitarValidacion = true;
        }
        if (primeraI != null) {
            if (ingresoLocal == null) {
                c.ingreso = primeraI.hora;
                c.origenIngreso = primeraI;
            }
        } else if (ingresoLocal != null && ultAI != Long.MIN_VALUE && ingresoLocal <= ultAI) {
            c.quitarIngreso = true;
        }
        return c;
    }
}
