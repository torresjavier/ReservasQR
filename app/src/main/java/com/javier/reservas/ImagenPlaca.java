package com.javier.reservas;

/**
 * Herramientas de imagen para leer mejor las placas (sin depender de Android, se prueban en el computador).
 *
 * Todas trabajan con arreglos de píxeles ARGB (como Bitmap.getPixels) o con brillos 0..255.
 *  - Medir si la imagen está nítida (para descartar cuadros movidos cuando el vehículo se mueve).
 *  - Variantes de la misma imagen que ayudan al OCR: contraste estirado, blanco y negro adaptativo, Otsu,
 *    afilado fuerte y afilado horizontal (contra el movimiento lateral).
 */
public final class ImagenPlaca {

    private ImagenPlaca() {}

    /** Cantidad de variantes disponibles (0 = la original). */
    public static final int NUM_VARIANTES = 5;
    public static final int V_ORIGINAL = 0;
    public static final int V_CONTRASTE = 1;
    public static final int V_ADAPTATIVO = 2;
    public static final int V_OTSU = 3;
    public static final int V_MOVIMIENTO = 4;

    // ------------------------------------------------------------------ básicos

    /** Brillo 0..255 de cada píxel ARGB. */
    public static int[] gris(int[] argb) {
        int[] g = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            g[i] = (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8;
        }
        return g;
    }

    /** Reduce por un factor entero (promedio de bloques). Sirve para medir rápido en zonas grandes. */
    public static int[] reducir(int[] g, int w, int h, int k, int[] tam) {
        if (k <= 1) {
            tam[0] = w;
            tam[1] = h;
            return g;
        }
        int nw = w / k, nh = h / k;
        int[] r = new int[Math.max(1, nw * nh)];
        for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
                int s = 0;
                for (int j = 0; j < k; j++) {
                    int fila = (y * k + j) * w + x * k;
                    for (int i = 0; i < k; i++) s += g[fila + i];
                }
                r[y * nw + x] = s / (k * k);
            }
        }
        tam[0] = nw;
        tam[1] = nh;
        return r;
    }

    private static int[] histograma(int[] g) {
        int[] h = new int[256];
        for (int v : g) h[v < 0 ? 0 : (v > 255 ? 255 : v)]++;
        return h;
    }

    private static int percentil(int[] hist, int total, float f) {
        long objetivo = (long) (total * f);
        long acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= objetivo) return i;
        }
        return 255;
    }

    /** Diferencia entre los brillos casi más claros y casi más oscuros (0..255). */
    public static int contraste(int[] g) {
        int[] h = histograma(g);
        return percentil(h, g.length, 0.97f) - percentil(h, g.length, 0.03f);
    }

    // ------------------------------------------------------------------ medidas

    /**
     * Nitidez sin escala (0..1): qué tan abruptos son los bordes más fuertes, comparados con el contraste de la imagen.
     * Es el MENOR valor entre horizontal y vertical: una placa enfocada da ≈ 0,9; desenfocada o movida de lado, menos de 0,5.
     * Se calcula en una versión reducida (rápido), de máximo ~320 px de ancho.
     * Se usa sobre el recorte de la placa para elegir el mejor cuadro y para pesar cada lectura.
     */
    public static float nitidez(int[] g, int w, int h) {
        int k = Math.max(1, w / 320);
        int[] tam = new int[2];
        int[] r = reducir(g, w, h, k, tam);
        int rw = tam[0], rh = tam[1];
        if (rw < 8 || rh < 8) return 0f;
        int[] hx = new int[256];
        int[] hy = new int[256];
        int nx = 0;
        for (int y = 1; y < rh - 1; y++) {
            int fila = y * rw;
            for (int x = 1; x < rw - 1; x++) {
                int gx = Math.abs(r[fila + x + 1] - r[fila + x - 1]);
                int gy = Math.abs(r[fila + rw + x] - r[fila + x - rw]);
                hx[gx > 255 ? 255 : gx]++;
                hy[gy > 255 ? 255 : gy]++;
                nx++;
            }
        }
        int c = Math.max(20, contraste(r));
        float px = percentil(hx, nx, 0.98f);
        float py = percentil(hy, nx, 0.98f);
        return Math.min(1f, Math.min(px, py) / c);
    }

    /** Brillo medio 0..255 (para saber si la imagen está muy oscura o quemada). */
    public static int brilloMedio(int[] g) {
        long s = 0;
        for (int v : g) s += v;
        return g.length == 0 ? 0 : (int) (s / g.length);
    }

    // ------------------------------------------------------------------ variantes

    /** Variante n (0..NUM_VARIANTES-1) de la imagen, en ARGB. 0 devuelve la misma imagen. */
    public static int[] variante(int[] argb, int w, int h, int n) {
        // La imagen integral usa enteros: con más de ~8 millones de píxeles podría desbordarse (no pasa con recortes de placa)
        if ((long) w * h > 8_000_000L) return argb;
        switch (n) {
            case V_CONTRASTE:  return aArgb(contrasteYAfilado(gris(argb), w, h, 1.0f));
            case V_ADAPTATIVO: return aArgb(adaptativo(gris(argb), w, h));
            case V_OTSU:       return aArgb(otsu(gris(argb), w, h));
            case V_MOVIMIENTO: return aArgb(afiladoHorizontal(estirar(gris(argb)), w, h));
            default:           return argb;
        }
    }

    /** Estira el brillo para que ocupe todo el rango 0..255 (ignora el 2 % más extremo). */
    public static int[] estirar(int[] g) {
        int[] hist = histograma(g);
        int lo = percentil(hist, g.length, 0.02f);
        int hi = percentil(hist, g.length, 0.98f);
        if (hi - lo < 12) hi = lo + 12;
        int[] r = new int[g.length];
        for (int i = 0; i < g.length; i++) {
            int v = (g[i] - lo) * 255 / (hi - lo);
            r[i] = v < 0 ? 0 : (v > 255 ? 255 : v);
        }
        return r;
    }

    /** Contraste estirado + máscara de enfoque (resta una versión borrosa para marcar los bordes). */
    public static int[] contrasteYAfilado(int[] g, int w, int h, float cantidad) {
        int[] e = estirar(g);
        int radio = Math.max(2, Math.min(w, h) / 40);
        int[] desenfocado = promedio(e, w, h, radio);
        int[] r = new int[e.length];
        for (int i = 0; i < e.length; i++) {
            int v = e[i] + (int) (cantidad * (e[i] - desenfocado[i]));
            r[i] = v < 0 ? 0 : (v > 255 ? 255 : v);
        }
        return r;
    }

    /** Afilado solo en horizontal: devuelve algo de nitidez a los trazos verticales cuando el vehículo pasó de lado. */
    public static int[] afiladoHorizontal(int[] g, int w, int h) {
        int radio = Math.max(2, w / 90);
        int[] r = new int[g.length];
        for (int y = 0; y < h; y++) {
            int fila = y * w;
            long acc = 0;
            int n = 0;
            // ventana deslizante [x-radio, x+radio]
            for (int x = 0; x <= Math.min(w - 1, radio); x++) { acc += g[fila + x]; n++; }
            for (int x = 0; x < w; x++) {
                int prom = (int) (acc / n);
                int v = g[fila + x] + (int) (1.7f * (g[fila + x] - prom));
                r[fila + x] = v < 0 ? 0 : (v > 255 ? 255 : v);
                int salir = x - radio, entrar = x + radio + 1;
                if (salir >= 0) { acc -= g[fila + salir]; n--; }
                if (entrar < w) { acc += g[fila + entrar]; n++; }
            }
        }
        return r;
    }

    /** Blanco y negro con umbral local (bueno con sombras, reflejos o luz despareja). Texto negro sobre fondo blanco. */
    public static int[] adaptativo(int[] g, int w, int h) {
        int[] e = estirar(g);
        int ventana = Math.max(15, Math.min(101, Math.min(w, h) / 2)) | 1;
        int radio = ventana / 2;
        int[] integral = new int[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            int fila = 0;
            for (int x = 0; x < w; x++) {
                fila += e[y * w + x];
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + fila;
            }
        }
        int[] r = new int[e.length];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - radio), y1 = Math.min(h, y + radio + 1);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - radio), x1 = Math.min(w, x + radio + 1);
                int suma = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1]
                        - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0];
                int media = suma / ((x1 - x0) * (y1 - y0));
                r[y * w + x] = e[y * w + x] < media * 0.86f ? 0 : 255;
            }
        }
        return invertirSiHaceFalta(r);
    }

    /** Blanco y negro con un único umbral (método de Otsu). */
    public static int[] otsu(int[] g, int w, int h) {
        int[] e = estirar(g);
        int[] hist = histograma(e);
        long total = e.length;
        long sumaTotal = 0;
        for (int i = 0; i < 256; i++) sumaTotal += (long) i * hist[i];
        long sumaB = 0, pesoB = 0;
        double mejor = -1;
        int umbral = 128;
        for (int t = 0; t < 256; t++) {
            pesoB += hist[t];
            if (pesoB == 0) continue;
            long pesoF = total - pesoB;
            if (pesoF == 0) break;
            sumaB += (long) t * hist[t];
            double mB = (double) sumaB / pesoB;
            double mF = (double) (sumaTotal - sumaB) / pesoF;
            double varianza = (double) pesoB * pesoF * (mB - mF) * (mB - mF);
            if (varianza > mejor) {
                mejor = varianza;
                umbral = t;
            }
        }
        int[] r = new int[e.length];
        for (int i = 0; i < e.length; i++) r[i] = e[i] <= umbral ? 0 : 255;
        return invertirSiHaceFalta(r);
    }

    /** La tinta es la minoría: si más de la mitad quedó negra, se invierte para que el texto sea negro. */
    private static int[] invertirSiHaceFalta(int[] bn) {
        int negros = 0;
        for (int v : bn) if (v == 0) negros++;
        if (negros * 2 > bn.length) {
            for (int i = 0; i < bn.length; i++) bn[i] = 255 - bn[i];
        }
        return bn;
    }

    /** Promedio local (ventana cuadrada de radio r) con imagen integral. */
    private static int[] promedio(int[] g, int w, int h, int r) {
        int[] integral = new int[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            int fila = 0;
            for (int x = 0; x < w; x++) {
                fila += g[y * w + x];
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + fila;
            }
        }
        int[] out = new int[g.length];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - r), y1 = Math.min(h, y + r + 1);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - r), x1 = Math.min(w, x + r + 1);
                int suma = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1]
                        - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0];
                out[y * w + x] = suma / ((x1 - x0) * (y1 - y0));
            }
        }
        return out;
    }

    /** Brillo 0..255 → píxel ARGB gris. */
    public static int[] aArgb(int[] g) {
        int[] r = new int[g.length];
        for (int i = 0; i < g.length; i++) {
            int v = g[i] < 0 ? 0 : (g[i] > 255 ? 255 : g[i]);
            r[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        return r;
    }
}
