package com.javier.reservas;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Voz para la radio (walkie-talkie): compresión μ-law a 8 kHz (8.000 bytes por segundo, calidad de teléfono, suficiente para
 * entender a quien habla) y el paquete que viaja por el Wi-Fi o por internet. Java puro, sin Android, para probarlo en el PC.
 */
public final class Voz {

    private Voz() {
    }

    /** Muestras por segundo. */
    public static final int FRECUENCIA = 8000;
    /** Cada paquete lleva este tiempo de voz. */
    public static final int BLOQUE_MS = 250;
    /** Muestras (y bytes comprimidos) en cada paquete. */
    public static final int MUESTRAS_BLOQUE = FRECUENCIA * BLOQUE_MS / 1000;
    /** Nadie habla más de esto seguido (evita un botón pegado que ocupe el canal). */
    public static final int MAX_SEGUNDOS = 60;

    // ---------------------------------------------------------------- μ-law

    private static final int SESGO = 0x84;
    private static final int CLIP = 32635;

    /** Comprime una muestra de 16 bits a 8 bits (G.711 μ-law). */
    public static byte codificar(short muestra) {
        int s = muestra;
        int signo = (s >> 8) & 0x80;
        if (signo != 0) s = -s;
        if (s > CLIP) s = CLIP;
        s += SESGO;
        int exponente = 7;
        for (int mascara = 0x4000; (s & mascara) == 0 && exponente > 0; mascara >>= 1) exponente--;
        int mantisa = (s >> (exponente + 3)) & 0x0F;
        return (byte) ~(signo | (exponente << 4) | mantisa);
    }

    /** Descomprime una muestra de 8 bits a 16 bits. */
    public static short decodificar(byte comprimida) {
        int u = ~comprimida & 0xFF;
        int signo = u & 0x80;
        int exponente = (u >> 4) & 0x07;
        int mantisa = u & 0x0F;
        int s = ((mantisa << 3) + SESGO) << exponente;
        s -= SESGO;
        return (short) (signo != 0 ? -s : s);
    }

    public static byte[] comprimir(short[] pcm, int n) {
        byte[] r = new byte[n];
        for (int i = 0; i < n; i++) r[i] = codificar(pcm[i]);
        return r;
    }

    public static short[] descomprimir(byte[] datos, int desde) {
        short[] r = new short[Math.max(0, datos.length - desde)];
        for (int i = 0; i < r.length; i++) r[i] = decodificar(datos[desde + i]);
        return r;
    }

    // ---------------------------------------------------------------- paquete

    /** Un trozo de voz de un equipo. */
    public static final class Paquete {
        /** Quién habla (identificador del equipo) y su nombre para mostrar. */
        public final String de;
        public final String nombre;
        /** Número de la transmisión (cada vez que se pulsa el botón) y del paquete dentro de ella. */
        public final long tx;
        public final int seq;
        /** Último paquete de la transmisión (soltó el botón). */
        public final boolean fin;
        /** Voz comprimida (puede venir vacía en el paquete final). */
        public final byte[] audio;

        public Paquete(String de, String nombre, long tx, int seq, boolean fin, byte[] audio) {
            this.de = de == null ? "" : de;
            this.nombre = nombre == null ? "" : nombre;
            this.tx = tx;
            this.seq = seq;
            this.fin = fin;
            this.audio = audio == null ? new byte[0] : audio;
        }

        /** Identificador único del paquete (sirve para no oírlo dos veces si llega por el Wi-Fi y por internet). */
        public String id() {
            return de + "|" + tx + "|" + seq;
        }
    }

    /** [tx 8][seq 4][bandera 1][largo de 1][de][largo nombre 1][nombre][audio…] */
    public static byte[] empaquetar(Paquete p) {
        byte[] de = recortar(p.de.getBytes(StandardCharsets.UTF_8), 60);
        byte[] nom = recortar(p.nombre.getBytes(StandardCharsets.UTF_8), 40);
        byte[] r = new byte[8 + 4 + 1 + 1 + de.length + 1 + nom.length + p.audio.length];
        int i = 0;
        for (int k = 7; k >= 0; k--) r[i++] = (byte) (p.tx >> (8 * k));
        for (int k = 3; k >= 0; k--) r[i++] = (byte) (p.seq >> (8 * k));
        r[i++] = (byte) (p.fin ? 1 : 0);
        r[i++] = (byte) de.length;
        System.arraycopy(de, 0, r, i, de.length);
        i += de.length;
        r[i++] = (byte) nom.length;
        System.arraycopy(nom, 0, r, i, nom.length);
        i += nom.length;
        System.arraycopy(p.audio, 0, r, i, p.audio.length);
        return r;
    }

    /** @return el paquete, o null si los bytes no son un paquete de voz válido */
    public static Paquete leer(byte[] b) {
        try {
            if (b == null || b.length < 15) return null;
            int i = 0;
            long tx = 0;
            for (int k = 0; k < 8; k++) tx = (tx << 8) | (b[i++] & 0xFF);
            int seq = 0;
            for (int k = 0; k < 4; k++) seq = (seq << 8) | (b[i++] & 0xFF);
            boolean fin = (b[i++] & 1) != 0;
            int ld = b[i++] & 0xFF;
            if (ld == 0 || ld > 60 || i + ld + 1 > b.length) return null;
            String de = new String(b, i, ld, StandardCharsets.UTF_8);
            i += ld;
            int ln = b[i++] & 0xFF;
            if (ln > 40 || i + ln > b.length) return null;
            String nombre = new String(b, i, ln, StandardCharsets.UTF_8);
            i += ln;
            byte[] audio = new byte[b.length - i];
            System.arraycopy(b, i, audio, 0, audio.length);
            if (audio.length > FRECUENCIA * 2) return null;
            return new Paquete(de, nombre, tx, seq, fin, audio);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static byte[] recortar(byte[] b, int max) {
        if (b.length <= max) return b;
        byte[] r = new byte[max];
        System.arraycopy(b, 0, r, 0, max);
        return r;
    }

    // ---------------------------------------------------------------- recepción

    /**
     * Decide qué paquetes se reproducen: descarta los repetidos (por ejemplo, el mismo paquete que llegó por el Wi-Fi y por
     * internet) y los que llegan tarde, ya pasado uno más nuevo de la misma transmisión.
     */
    public static final class Recepcion {
        private static final class Estado {
            long tx = Long.MIN_VALUE;
            int seq = -1;
        }

        private final Map<String, Estado> porEquipo = new HashMap<>();

        /** @return true si el paquete es nuevo y se debe reproducir */
        public synchronized boolean aceptar(Paquete p) {
            Estado e = porEquipo.get(p.de);
            if (e == null) {
                e = new Estado();
                porEquipo.put(p.de, e);
            }
            if (p.tx > e.tx) {
                e.tx = p.tx;
                e.seq = p.seq;
                return true;
            }
            if (p.tx == e.tx && p.seq > e.seq) {
                e.seq = p.seq;
                return true;
            }
            return false;
        }

        public synchronized void olvidar() {
            porEquipo.clear();
        }
    }
}
