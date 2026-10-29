package com.javier.reservas;

import java.util.Collection;

/**
 * Lee las probabilidades del modelo de placas (10 posiciones × 37 símbolos) obligando a cumplir el formato colombiano:
 *
 *   carro  AAA000   (3 letras + 3 números)
 *   moto   AAA00A   (3 letras + 2 números + 1 letra)
 *   moto   AAA00    (formato antiguo)
 *
 * Así, en las 3 primeras posiciones SOLO puede salir una letra y en la 4.ª y 5.ª SOLO un número: una G nunca se confunde
 * con un 6, ni una O con un 0, porque la posición ya dice qué clase de símbolo es. La última posición decide si es
 * carro (número) o moto (letra) comparando cuál de los dos formatos encaja mejor con lo que vio el modelo.
 *
 * Todo trabaja con logaritmos de probabilidad (0 = seguro; −1 ≈ 37 %; −3 ≈ 5 %; −10 ≈ nada probable).
 */
public final class FormatoPlaca {

    private FormatoPlaca() {}

    public static final String ALFABETO = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_";
    /** Posiciones que entrega el modelo. */
    public static final int RANURAS = 10;
    private static final int PAD = ALFABETO.indexOf('_');
    private static final float MUY_BAJO = -1e6f;

    private static final String LETRAS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String DIGITOS = "0123456789";
    /** L = letra, D = dígito. */
    private static final String[] FORMATOS = {"LLLDDD", "LLLDDL", "LLLDD"};

    /** Placa decodificada con su puntaje (suma de log-probabilidades; 0 es lo mejor posible). */
    public static final class Decodificado {
        public final String placa;
        public final boolean moto;
        public final float puntaje;

        Decodificado(String placa, boolean moto, float puntaje) {
            this.placa = placa;
            this.moto = moto;
            this.puntaje = puntaje;
        }

        @Override public String toString() {
            return placa + (moto ? " MOTO " : " CARRO ") + String.format(java.util.Locale.ROOT, "%.2f", puntaje);
        }
    }

    /** Placa del listado que mejor encaja con las probabilidades, y la siguiente mejor (para saber si hay duda). */
    public static final class Coincidencia {
        public final String placa;
        public final float puntaje;
        /** Puntaje de la segunda mejor placa distinta (−1e6 si no hay otra). */
        public final float segundo;

        Coincidencia(String placa, float puntaje, float segundo) {
            this.placa = placa;
            this.puntaje = puntaje;
            this.segundo = segundo;
        }
    }

    /** Convierte probabilidades (0..1) en log-probabilidades, sin llegar a −infinito. */
    public static float[][] aLogaritmos(float[] probabilidades, int ranuras) {
        float[][] r = new float[ranuras][ALFABETO.length()];
        for (int i = 0; i < ranuras; i++) {
            for (int k = 0; k < ALFABETO.length(); k++) {
                float p = probabilidades[i * ALFABETO.length() + k];
                r[i][k] = (float) Math.log(Math.max(p, 1e-9f));
            }
        }
        return r;
    }

    /** La mejor placa que cumple alguno de los formatos de carro o moto. null si la tabla no tiene el tamaño esperado. */
    public static Decodificado decodificar(float[][] lp) {
        if (lp == null || lp.length < 6) return null;
        Decodificado mejor = null;
        for (String f : FORMATOS) {
            if (f.length() > lp.length) continue;
            StringBuilder sb = new StringBuilder(f.length());
            for (int i = 0; i < f.length(); i++) {
                String permitidos = f.charAt(i) == 'L' ? LETRAS : DIGITOS;
                char mejorC = permitidos.charAt(0);
                float mejorV = MUY_BAJO;
                for (int k = 0; k < permitidos.length(); k++) {
                    char c = permitidos.charAt(k);
                    float v = lp[i][ALFABETO.indexOf(c)];
                    if (v > mejorV) {
                        mejorV = v;
                        mejorC = c;
                    }
                }
                sb.append(mejorC);
            }
            String placa = sb.toString();
            float s = puntaje(lp, placa);
            if (mejor == null || s > mejor.puntaje) {
                mejor = new Decodificado(placa, !"LLLDDD".equals(f), s);
            }
        }
        return mejor;
    }

    /**
     * Qué tan bien encaja una placa con las probabilidades: suma de log-probabilidades de cada símbolo, y de «fin de
     * texto» en las posiciones que sobran (así una placa de 6 no compite con una lectura de 7 símbolos).
     */
    public static float puntaje(float[][] lp, String placa) {
        float t = 0f;
        for (int i = 0; i < lp.length; i++) {
            int k = i < placa.length() ? ALFABETO.indexOf(placa.charAt(i)) : PAD;
            if (k < 0 || (i < placa.length() && k == PAD)) return MUY_BAJO;
            t += lp[i][k];
        }
        return t;
    }

    /** true si la placa (ya sin espacios ni guiones) tiene forma de carro o de moto colombiana. */
    public static boolean tieneFormato(String placa) {
        for (String f : FORMATOS) {
            if (f.length() != placa.length()) continue;
            boolean ok = true;
            for (int i = 0; i < f.length() && ok; i++) {
                char c = placa.charAt(i);
                ok = f.charAt(i) == 'L' ? (c >= 'A' && c <= 'Z') : (c >= '0' && c <= '9');
            }
            if (ok) return true;
        }
        return false;
    }

    /**
     * Entre las placas del listado, la que más probabilidad tiene según el modelo. Recorrer 1000 o 50.000 placas
     * cuesta muy poco (unas decenas de miles de sumas). null si el listado no tiene ninguna con formato válido.
     */
    public static Coincidencia mejorEnListado(float[][] lp, Collection<String> placas) {
        String mejor = null;
        float sMejor = MUY_BAJO;
        float sSegundo = MUY_BAJO;
        for (String p : placas) {
            if (p == null || p.length() < 5 || p.length() > 6 || p.length() > lp.length || !tieneFormato(p)) continue;
            float s = puntaje(lp, p);
            if (s > sMejor) {
                sSegundo = sMejor;
                sMejor = s;
                mejor = p;
            } else if (s > sSegundo) {
                sSegundo = s;
            }
        }
        return mejor == null ? null : new Coincidencia(mejor, sMejor, sSegundo);
    }
}
