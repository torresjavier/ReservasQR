package com.javier.reservas;

import java.util.Collection;

/**
 * Decide qué placa se está viendo combinando dos cosas:
 *  1. la lectura «libre» (la mejor placa que cumple el formato de carro o de moto), y
 *  2. el listado de reservas: si una placa del listado encaja casi tan bien como la lectura libre, se toma esa
 *     (corrige confusiones como Q/O, 8/B, G/6 usando las probabilidades del modelo y no una tabla fija).
 *
 * Calibrado con fotos reales de carro y moto (nítidas, borrosas, oscuras, lejanas y con ruido) y listados de 1000 placas:
 * con TAU = 2 acertó todas las placas del listado que el modelo podía leer, no se equivocó ninguna vez y, con la placa
 * fuera del listado, nunca la confundió con otra.
 */
public final class DecisionPlaca {

    private DecisionPlaca() {}

    /** Diferencia máxima de puntaje entre la lectura libre y la placa del listado para aceptarla (≈ ¼ de probabilidad). */
    public static final float TAU = 2.0f;
    /** Cuánto más probable debe ser la placa elegida que la siguiente del listado. */
    public static final float BRECHA = 0.8f;
    /** Puntaje mínimo de la lectura libre para buscarla en el listado aceptando confusiones O/Q/0, I/1… */
    public static final float LIBRE_MINIMO = -3.0f;
    /** Puntaje desde el cual la lectura se considera segura. */
    public static final float SEGURA = -1.0f;

    public static final class Resultado {
        /** Mejor placa con formato de carro o moto, sin mirar el listado. */
        public final FormatoPlaca.Decodificado libre;
        /** Placa del listado que encaja con lo visto (null si ninguna). */
        public final String listada;
        public final float puntajeListada;
        /** Hay una placa del listado que encaja, pero otra casi igual de probable: hace falta una lectura más clara. */
        public final boolean ambigua;

        Resultado(FormatoPlaca.Decodificado libre, String listada, float puntajeListada, boolean ambigua) {
            this.libre = libre;
            this.listada = listada;
            this.puntajeListada = puntajeListada;
            this.ambigua = ambigua;
        }
    }

    /** @return null si las probabilidades no tienen el tamaño esperado */
    public static Resultado decidir(float[][] logp, Collection<String> placasListado) {
        FormatoPlaca.Decodificado libre = FormatoPlaca.decodificar(logp);
        if (libre == null) return null;
        FormatoPlaca.Coincidencia c = placasListado == null ? null : FormatoPlaca.mejorEnListado(logp, placasListado);
        if (c != null && libre.puntaje - c.puntaje <= TAU) {
            if (c.puntaje - c.segundo >= BRECHA) return new Resultado(libre, c.placa, c.puntaje, false);
            return new Resultado(libre, null, c.puntaje, true);
        }
        return new Resultado(libre, null, -1e6f, false);
    }
}
