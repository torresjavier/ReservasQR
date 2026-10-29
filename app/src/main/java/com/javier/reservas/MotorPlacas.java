package com.javier.reservas;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Motor de lectura de placas con inteligencia artificial (se ejecuta dentro del equipo, sin internet):
 *
 *  1. DETECTOR (YOLOv9 «license plate», 384×384): encuentra dónde está la placa en la imagen, sea de carro o de moto.
 *  2. LECTOR (fast-plate-ocr «cct-xs-v2», 128×64): lee los 10 símbolos de la placa recortada y entrega, para cada
 *     posición, la probabilidad de cada letra o número. {@link FormatoPlaca} obliga a que cumpla el formato colombiano.
 *
 * Modelos de https://github.com/ankandrew/open-image-models y https://github.com/ankandrew/fast-plate-ocr (licencia MIT).
 *
 * Esta clase solo prepara las imágenes y interpreta las salidas (se prueba en el computador); quien ejecuta los
 * modelos es un {@link Ejecutor} (en Android, {@link EjecutorOrt} con ONNX Runtime).
 */
public final class MotorPlacas implements AutoCloseable {

    /** Ejecuta los dos modelos. */
    public interface Ejecutor extends AutoCloseable {
        /**
         * @param chw tensor 1×3×t×t con la imagen en RGB, valores 0..1
         * @return filas de 7 valores: [lote, x1, y1, x2, y2, clase, confianza], en píxeles de la imagen de t×t
         */
        float[] detectar(float[] chw, int t) throws Exception;

        /**
         * @param rgb imagen de 64 (alto) × 128 (ancho) × 3 (RGB), bytes sin signo
         * @return probabilidades planas: 10 posiciones × 37 símbolos
         */
        float[] leer(byte[] rgb) throws Exception;

        @Override void close();
    }

    /** Una placa encontrada, en píxeles de la imagen donde se buscó. */
    public static final class Deteccion {
        public final float confianza;
        public final int x1, y1, x2, y2;

        Deteccion(float confianza, int x1, int y1, int x2, int y2) {
            this.confianza = confianza;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }

        public int ancho() { return x2 - x1; }
        public int alto() { return y2 - y1; }
    }

    /** Lado de la imagen cuadrada que recibe el detector. */
    public static final int TAM_DET = 384;
    public static final int OCR_ANCHO = 128;
    public static final int OCR_ALTO = 64;
    /** Confianza mínima del detector. */
    public static final float UMBRAL_DET = 0.30f;
    /** Una detección más pequeña que esto (en píxeles de la imagen donde se buscó) se ignora. */
    private static final int MIN_ANCHO = 14;
    private static final int MIN_ALTO = 6;

    private final Ejecutor ej;
    private final float[] tensor = new float[3 * TAM_DET * TAM_DET];
    private final byte[] rgbOcr = new byte[OCR_ALTO * OCR_ANCHO * 3];

    public MotorPlacas(Ejecutor ej) {
        this.ej = ej;
    }

    // ------------------------------------------------------------------ detector

    /**
     * Busca placas en una imagen ARGB. Devuelve las que superan el umbral, la más segura primero.
     * Las coordenadas son de la propia imagen.
     */
    public List<Deteccion> detectar(int[] argb, int w, int h) throws Exception {
        if (w < 8 || h < 8 || argb.length < w * h) return Collections.emptyList();
        final int t = TAM_DET;
        // Se ajusta la imagen al cuadrado conservando la proporción (bandas grises 114 alrededor), como en el entrenamiento
        final double r = Math.min((double) t / h, (double) t / w);
        final int nw = (int) Math.round(w * r);
        final int nh = (int) Math.round(h * r);
        final double dw = (t - nw) / 2.0;
        final double dh = (t - nh) / 2.0;
        final int izq = (int) Math.round(dw - 0.1);
        final int arr = (int) Math.round(dh - 0.1);
        final int plano = t * t;
        java.util.Arrays.fill(tensor, 114f / 255f);
        final double ex = (double) w / nw;
        final double ey = (double) h / nh;
        for (int y = 0; y < nh && arr + y < t; y++) {
            double fy = (y + 0.5) * ey - 0.5;
            int y0 = (int) Math.floor(fy);
            float ay = (float) (fy - y0);
            if (y0 < 0) { y0 = 0; ay = 0f; }
            int y1 = y0 + 1;
            if (y0 >= h - 1) { y0 = h - 1; y1 = h - 1; ay = 0f; }
            final int fila0 = y0 * w, fila1 = y1 * w;
            final int base = (arr + y) * t + izq;
            for (int x = 0; x < nw && izq + x < t; x++) {
                double fx = (x + 0.5) * ex - 0.5;
                int x0 = (int) Math.floor(fx);
                float ax = (float) (fx - x0);
                if (x0 < 0) { x0 = 0; ax = 0f; }
                int x1 = x0 + 1;
                if (x0 >= w - 1) { x0 = w - 1; x1 = w - 1; ax = 0f; }
                final int p00 = argb[fila0 + x0], p01 = argb[fila0 + x1], p10 = argb[fila1 + x0], p11 = argb[fila1 + x1];
                final float w00 = (1f - ax) * (1f - ay), w01 = ax * (1f - ay), w10 = (1f - ax) * ay, w11 = ax * ay;
                final int i = base + x;
                tensor[i] = ((((p00 >> 16) & 0xFF) * w00 + ((p01 >> 16) & 0xFF) * w01
                        + ((p10 >> 16) & 0xFF) * w10 + ((p11 >> 16) & 0xFF) * w11)) / 255f;
                tensor[plano + i] = ((((p00 >> 8) & 0xFF) * w00 + ((p01 >> 8) & 0xFF) * w01
                        + ((p10 >> 8) & 0xFF) * w10 + ((p11 >> 8) & 0xFF) * w11)) / 255f;
                tensor[2 * plano + i] = (((p00 & 0xFF) * w00 + (p01 & 0xFF) * w01
                        + (p10 & 0xFF) * w10 + (p11 & 0xFF) * w11)) / 255f;
            }
        }
        float[] salida = ej.detectar(tensor, t);
        return interpretar(salida, r, dw, dh, w, h);
    }

    /** Pasa las filas del modelo a cajas en la imagen original (quita las bandas y deshace la reducción). */
    static List<Deteccion> interpretar(float[] salida, double r, double dw, double dh, int w, int h) {
        List<Deteccion> out = new ArrayList<>();
        if (salida == null) return out;
        for (int i = 0; i + 6 < salida.length; i += 7) {
            float conf = salida[i + 6];
            if (conf < UMBRAL_DET) continue;
            int x1 = clamp((int) ((salida[i + 1] - dw) / r), 0, w);
            int y1 = clamp((int) ((salida[i + 2] - dh) / r), 0, h);
            int x2 = clamp((int) ((salida[i + 3] - dw) / r), 0, w);
            int y2 = clamp((int) ((salida[i + 4] - dh) / r), 0, h);
            if (x2 - x1 < MIN_ANCHO || y2 - y1 < MIN_ALTO) continue;
            out.add(new Deteccion(conf, x1, y1, x2, y2));
        }
        Collections.sort(out, (a, b) -> Float.compare(b.confianza, a.confianza));
        return out;
    }

    // ------------------------------------------------------------------ lector

    /**
     * Lee una placa ya recortada (ARGB). Devuelve log-probabilidades [10 posiciones][37 símbolos].
     */
    public float[][] leer(int[] argb, int w, int h) throws Exception {
        if (w < 4 || h < 4 || argb.length < w * h) return null;
        redimensionarRgb(argb, w, h, rgbOcr, OCR_ANCHO, OCR_ALTO);
        float[] p = ej.leer(rgbOcr);
        if (p == null || p.length < FormatoPlaca.RANURAS * FormatoPlaca.ALFABETO.length()) return null;
        return FormatoPlaca.aLogaritmos(p, FormatoPlaca.RANURAS);
    }

    /** Reduce o amplía con interpolación bilineal (igual que cv2.resize con INTER_LINEAR). Salida RGB, 3 bytes por píxel. */
    static void redimensionarRgb(int[] argb, int w, int h, byte[] destino, int nw, int nh) {
        final double ex = (double) w / nw;
        final double ey = (double) h / nh;
        int o = 0;
        for (int y = 0; y < nh; y++) {
            double fy = (y + 0.5) * ey - 0.5;
            int y0 = (int) Math.floor(fy);
            float ay = (float) (fy - y0);
            if (y0 < 0) { y0 = 0; ay = 0f; }
            int y1 = y0 + 1;
            if (y0 >= h - 1) { y0 = h - 1; y1 = h - 1; ay = 0f; }
            final int fila0 = y0 * w, fila1 = y1 * w;
            for (int x = 0; x < nw; x++) {
                double fx = (x + 0.5) * ex - 0.5;
                int x0 = (int) Math.floor(fx);
                float ax = (float) (fx - x0);
                if (x0 < 0) { x0 = 0; ax = 0f; }
                int x1 = x0 + 1;
                if (x0 >= w - 1) { x0 = w - 1; x1 = w - 1; ax = 0f; }
                final int p00 = argb[fila0 + x0], p01 = argb[fila0 + x1], p10 = argb[fila1 + x0], p11 = argb[fila1 + x1];
                final float w00 = (1f - ax) * (1f - ay), w01 = ax * (1f - ay), w10 = (1f - ax) * ay, w11 = ax * ay;
                destino[o++] = (byte) (int) (((p00 >> 16) & 0xFF) * w00 + ((p01 >> 16) & 0xFF) * w01
                        + ((p10 >> 16) & 0xFF) * w10 + ((p11 >> 16) & 0xFF) * w11 + 0.5f);
                destino[o++] = (byte) (int) (((p00 >> 8) & 0xFF) * w00 + ((p01 >> 8) & 0xFF) * w01
                        + ((p10 >> 8) & 0xFF) * w10 + ((p11 >> 8) & 0xFF) * w11 + 0.5f);
                destino[o++] = (byte) (int) ((p00 & 0xFF) * w00 + (p01 & 0xFF) * w01
                        + (p10 & 0xFF) * w10 + (p11 & 0xFF) * w11 + 0.5f);
            }
        }
    }

    // ------------------------------------------------------------------ cámara

    /**
     * Recorta una zona del cuadro de la cámara (YUV) ya derecha y a color, tomando un píxel de cada {@code paso}.
     * Las coordenadas son del cuadro derecho (el que ve el operador). El tamaño del resultado lo da
     * {@link DetectorPlaca#tamanoRecorte}.
     */
    public static int[] recortarArgb(ByteBuffer y, ByteBuffer u, ByteBuffer v,
                                     int yFila, int uvFila, int uvPaso,
                                     int ancho, int alto, int rotacion,
                                     int izq, int arr, int der, int aba, int paso) {
        final int k = Math.max(1, paso);
        final int w = Math.max(1, (der - izq) / k), h = Math.max(1, (aba - arr) / k);
        final int[] px = new int[w * h];
        final int ylim = y.limit(), ulim = u.limit(), vlim = v.limit();
        for (int j = 0; j < h; j++) {
            final int uy = arr + j * k;
            for (int i = 0; i < w; i++) {
                final int ux = izq + i * k;
                int bx, by;
                switch (rotacion) {
                    case 90:  bx = uy;             by = alto - 1 - ux; break;
                    case 180: bx = ancho - 1 - ux; by = alto - 1 - uy; break;
                    case 270: bx = ancho - 1 - uy; by = ux; break;
                    default:  bx = ux;             by = uy; break;
                }
                bx = clamp(bx, 0, ancho - 1);
                by = clamp(by, 0, alto - 1);
                final int p = by * yFila + bx;
                final int yy = p < ylim ? y.get(p) & 0xFF : 0;
                final int pc = (by >> 1) * uvFila + (bx >> 1) * uvPaso;
                final int cb = (pc < ulim ? u.get(pc) & 0xFF : 128) - 128;
                final int cr = (pc < vlim ? v.get(pc) & 0xFF : 128) - 128;
                int r = yy + (int) (1.402f * cr);
                int g = yy - (int) (0.344136f * cb + 0.714136f * cr);
                int b = yy + (int) (1.772f * cb);
                r = r < 0 ? 0 : (r > 255 ? 255 : r);
                g = g < 0 ? 0 : (g > 255 ? 255 : g);
                b = b < 0 ? 0 : (b > 255 ? 255 : b);
                px[j * w + i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return px;
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    @Override
    public void close() {
        ej.close();
    }
}
