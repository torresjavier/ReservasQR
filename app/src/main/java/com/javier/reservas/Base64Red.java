package com.javier.reservas;

/**
 * Base64 mínimo (alfabeto estándar, con relleno «=») para llevar bytes dentro de una línea de texto.
 * Se escribe aquí porque java.util.Base64 no existe en Android 7 y se quiere poder probar fuera de Android.
 */
public final class Base64Red {

    private static final char[] ALFABETO = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final int[] INVERSO = new int[128];

    static {
        java.util.Arrays.fill(INVERSO, -1);
        for (int i = 0; i < ALFABETO.length; i++) INVERSO[ALFABETO[i]] = i;
    }

    private Base64Red() {}

    public static String codificar(byte[] d) {
        if (d == null || d.length == 0) return "";
        StringBuilder b = new StringBuilder((d.length + 2) / 3 * 4);
        int i = 0;
        for (; i + 2 < d.length; i += 3) {
            int n = ((d[i] & 255) << 16) | ((d[i + 1] & 255) << 8) | (d[i + 2] & 255);
            b.append(ALFABETO[(n >> 18) & 63]).append(ALFABETO[(n >> 12) & 63]).append(ALFABETO[(n >> 6) & 63]).append(ALFABETO[n & 63]);
        }
        int resto = d.length - i;
        if (resto == 1) {
            int n = (d[i] & 255) << 16;
            b.append(ALFABETO[(n >> 18) & 63]).append(ALFABETO[(n >> 12) & 63]).append("==");
        } else if (resto == 2) {
            int n = ((d[i] & 255) << 16) | ((d[i + 1] & 255) << 8);
            b.append(ALFABETO[(n >> 18) & 63]).append(ALFABETO[(n >> 12) & 63]).append(ALFABETO[(n >> 6) & 63]).append('=');
        }
        return b.toString();
    }

    /** @return los bytes, o null si el texto no es Base64 válido */
    public static byte[] decodificar(String s) {
        if (s == null) return null;
        int largo = s.length();
        if (largo == 0) return new byte[0];
        if ((largo & 3) != 0) return null;
        int relleno = 0;
        if (s.charAt(largo - 1) == '=') relleno++;
        if (s.charAt(largo - 2) == '=') relleno++;
        byte[] r = new byte[largo / 4 * 3 - relleno];
        int o = 0;
        for (int i = 0; i < largo; i += 4) {
            int n = 0;
            for (int k = 0; k < 4; k++) {
                char c = s.charAt(i + k);
                int v;
                if (c == '=') {
                    if (i + k < largo - relleno) return null;
                    v = 0;
                } else {
                    v = c < 128 ? INVERSO[c] : -1;
                    if (v < 0) return null;
                }
                n = (n << 6) | v;
            }
            if (o < r.length) r[o++] = (byte) (n >> 16);
            if (o < r.length) r[o++] = (byte) (n >> 8);
            if (o < r.length) r[o++] = (byte) n;
        }
        return r;
    }
}
