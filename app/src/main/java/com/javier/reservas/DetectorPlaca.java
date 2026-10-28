package com.javier.reservas;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * Detector muy liviano de placas (sin OCR) que trabaja sobre el cuadro YUV de la cámara.
 *
 * Busca una zona con forma de placa colombiana: fondo AMARILLO o BLANCO, con muchos trazos
 * oscuros verticales (los caracteres) y proporción de rectángulo (carro ≈ 2:1, moto ≈ 1,3:1).
 * Sirve para dos cosas:
 *   1. Decidir si vale la pena gastar batería en el OCR (solo si hay una placa a la vista).
 *   2. Decirle al OCR EXACTAMENTE dónde está la placa, para leer solo ese recorte y enfocar la cámara ahí.
 *
 * Trabaja en el cuadro "derecho" (ya girado según la rotación de la cámara). Cuesta unos pocos milisegundos.
 */
public final class DetectorPlaca {

    public static final int AMARILLA = 1;
    public static final int BLANCA = 2;

    /** Una placa encontrada, en píxeles del cuadro derecho. */
    public static final class Resultado {
        public final int izq, arr, der, aba;
        public final int anchoCuadro, altoCuadro;
        public final int color;
        public final float puntaje;

        Resultado(int izq, int arr, int der, int aba, int aw, int ah, int color, float puntaje) {
            this.izq = izq; this.arr = arr; this.der = der; this.aba = aba;
            this.anchoCuadro = aw; this.altoCuadro = ah;
            this.color = color; this.puntaje = puntaje;
        }

        public int ancho() { return der - izq; }
        public int alto() { return aba - arr; }
        public float relacion() { return alto() <= 0 ? 0f : (float) ancho() / alto(); }
        public float centroX() { return (izq + der) / 2f; }
        public float centroY() { return (arr + aba) / 2f; }
        /** Fracción del ancho del cuadro que ocupa la placa. */
        public float fraccionAncho() { return (float) ancho() / anchoCuadro; }

        @Override public String toString() {
            return (color == AMARILLA ? "amarilla" : "blanca") + " " + izq + "," + arr + "-" + der + "," + aba
                    + " rel=" + String.format(java.util.Locale.ROOT, "%.2f", relacion())
                    + " p=" + String.format(java.util.Locale.ROOT, "%.2f", puntaje);
        }
    }

    // ---- Parámetros (ajustados con placas sintéticas; ver pruebas)
    // Los umbrales de brillo se ajustan a la luz de la escena (de noche o con sombra todo es más oscuro)
    private static final int BORDE_MIN = 14, BORDE_MAX = 36;      // diferencia de brillo para contar un trazo
    private static final int OSCURO_MIN = 45, OSCURO_MAX = 110;   // brillo máximo de un trazo oscuro
    private static final int CLARO_MIN = 62, CLARO_MAX = 130;     // brillo mínimo del fondo blanco
    private static final int BLOQUE = 6;             // celdas por lado de cada bloque
    private static final float MIN_RELACION = 1.05f; // ancho/alto mínimo (moto en dos renglones)
    private static final float MAX_RELACION = 5.5f;  // ancho/alto máximo
    private static final float MIN_PUNTAJE = 0.30f;

    // memoria reutilizada entre cuadros (evita basura y gasto de batería)
    private int[] lum = new int[0];
    private int[] dif = new int[0];       // diferencia de brillo horizontal/vertical entre vecinos
    private byte[] cbA = new byte[0];
    private byte[] crA = new byte[0];
    private byte[] clase = new byte[0];   // 1 = amarillo, 2 = blanco, 0 = otro
    private byte[] borde = new byte[0];   // 1 = trazo vertical
    private boolean[] texto = new boolean[0];
    private boolean[] puente = new boolean[0];
    private boolean[] visto = new boolean[0];
    private int textoOscuro = 105;
    private int textoBorde = 28;

    /**
     * @param y,u,v      planos del cuadro (formato YUV_420_888 de CameraX)
     * @param yFila      bytes por fila del plano Y
     * @param uvFila     bytes por fila de los planos U y V
     * @param uvPaso     bytes entre dos muestras seguidas de U/V (1 o 2)
     * @param ancho,alto tamaño del cuadro tal como lo entrega la cámara
     * @param rotacion   grados (0, 90, 180, 270) que hay que girar el cuadro para verlo derecho
     */
    public Resultado detectar(ByteBuffer y, ByteBuffer u, ByteBuffer v,
                              int yFila, int uvFila, int uvPaso,
                              int ancho, int alto, int rotacion) {
        return detectar(y, u, v, yFila, uvFila, uvPaso, ancho, alto, rotacion, true);
    }

    /**
     * @param probarFina false = solo la escala gruesa (mitad de costo). La cámara alterna: un cuadro sí y otro no
     *                   prueba también la escala fina, que es la que cuesta más y solo sirve para placas lejanas.
     */
    public Resultado detectar(ByteBuffer y, ByteBuffer u, ByteBuffer v,
                              int yFila, int uvFila, int uvPaso,
                              int ancho, int alto, int rotacion, boolean probarFina) {
        final int lado = Math.max(ancho, alto);
        // Escala gruesa: placas medianas y grandes (los bloques son más anchos que el grosor de los trazos).
        Resultado r = detectarEn(y, u, v, yFila, uvFila, uvPaso, ancho, alto, rotacion, Math.max(2, Math.round(lado / 400f)));
        if (r != null) return r;
        // Escala fina: placas pequeñas o lejanas (y motos), que en la escala gruesa quedan en 1–2 bloques.
        if (!probarFina) return null;
        int fino = Math.max(2, Math.round(lado / 800f));
        if (fino >= Math.max(2, Math.round(lado / 400f))) return null;
        return detectarEn(y, u, v, yFila, uvFila, uvPaso, ancho, alto, rotacion, fino);
    }

    private Resultado detectarEn(ByteBuffer y, ByteBuffer u, ByteBuffer v,
                                 int yFila, int uvFila, int uvPaso,
                                 int ancho, int alto, int rotacion, int paso) {
        final boolean giro = rotacion == 90 || rotacion == 270;
        final int uw = giro ? alto : ancho;
        final int uh = giro ? ancho : alto;
        final int gw = uw / paso, gh = uh / paso;
        if (gw < BLOQUE * 3 || gh < BLOQUE * 2) return null;
        preparar(gw * gh);

        // ---- 1) Cuadrícula de brillo, color y trazos
        final int ylim = y.limit(), ulim = u.limit(), vlim = v.limit();
        for (int gy = 0; gy < gh; gy++) {
            final int uy = gy * paso + paso / 2;
            for (int gx = 0; gx < gw; gx++) {
                final int ux = gx * paso + paso / 2;
                final int i = gy * gw + gx;
                int bx, by, bxa, bxb, bya, byb;
                switch (rotacion) {
                    case 90:  bx = uy;           by = alto - 1 - ux;
                              bxa = bx; bya = clamp(by + 1, alto);  bxb = bx; byb = clamp(by - 1, alto); break;
                    case 180: bx = ancho - 1 - ux; by = alto - 1 - uy;
                              bxa = clamp(bx + 1, ancho); bya = by; bxb = clamp(bx - 1, ancho); byb = by; break;
                    case 270: bx = ancho - 1 - uy; by = ux;
                              bxa = bx; bya = clamp(by - 1, alto);  bxb = bx; byb = clamp(by + 1, alto); break;
                    default:  bx = ux;           by = uy;
                              bxa = clamp(bx - 1, ancho); bya = by; bxb = clamp(bx + 1, ancho); byb = by; break;
                }
                final int p = by * yFila + bx;
                final int yy = p < ylim ? y.get(p) & 0xFF : 0;
                final int pa = bya * yFila + bxa, pb = byb * yFila + bxb;
                final int ya = pa < ylim ? y.get(pa) & 0xFF : yy;
                final int yb = pb < ylim ? y.get(pb) & 0xFF : yy;
                lum[i] = yy;
                dif[i] = Math.abs(ya - yb);

                final int pc = (by >> 1) * uvFila + (bx >> 1) * uvPaso;
                cbA[i] = (byte) ((pc < ulim ? u.get(pc) & 0xFF : 128) - 128);
                crA[i] = (byte) ((pc < vlim ? v.get(pc) & 0xFF : 128) - 128);
            }
        }

        // ---- 1b) Umbrales según la luz de la escena (percentil 96 del brillo)
        final int[] hist = new int[256];
        for (int i = 0; i < gw * gh; i++) hist[lum[i]]++;
        int acum = 0, p90 = 255; // (p90 = brillo alto de la escena; incluye la placa si es lo bastante grande)
        final int objetivo = (int) (gw * gh * 0.96f);
        for (int i = 0; i < 256; i++) {
            acum += hist[i];
            if (acum >= objetivo) { p90 = i; break; }
        }
        final int umbralBorde = clampI((int) (p90 * 0.14f), BORDE_MIN, BORDE_MAX);
        final int oscuro = clampI((int) (p90 * 0.45f), OSCURO_MIN, OSCURO_MAX);
        final int claro = clampI((int) (p90 * 0.52f), CLARO_MIN, CLARO_MAX);
        final int amarilloMin = Math.max(40, (int) (claro * 0.72f));
        for (int i = 0; i < gw * gh; i++) {
            final int yy = lum[i];
            final int cb = cbA[i], cr = crA[i];
            borde[i] = (byte) (dif[i] >= umbralBorde ? 1 : 0);
            byte c = 0;
            if (yy > amarilloMin && cb < -14 && cr > 4 && cr < 100) c = 1;                   // amarillo
            else if (yy >= claro && Math.abs(cb) < 22 && Math.abs(cr) < 22) c = 2;          // blanco
            clase[i] = c;
        }
        textoOscuro = oscuro;
        textoBorde = umbralBorde;

        // ---- 2) Bloques con pinta de "caracteres sobre fondo de placa"
        final int bw = gw / BLOQUE, bh = gh / BLOQUE;
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                int bordes = 0, oscuros = 0, fondo = 0, n = 0;
                // Brillo más claro y más oscuro del bloque: la tinta es lo oscuro RELATIVO al fondo de la placa
                int lmin = 255, lmax = 0;
                for (int j = 0; j < BLOQUE; j++) {
                    int fila = (by * BLOQUE + j) * gw + bx * BLOQUE;
                    for (int k = 0; k < BLOQUE; k++) {
                        int l = lum[fila + k];
                        if (l < lmin) lmin = l;
                        if (l > lmax) lmax = l;
                    }
                }
                final boolean contrasteFuerte = lmax - lmin >= 3 * textoBorde;
                final int corte = lmin + (int) ((lmax - lmin) * 0.45f);
                for (int j = 0; j < BLOQUE; j++) {
                    int fila = (by * BLOQUE + j) * gw + bx * BLOQUE;
                    for (int k = 0; k < BLOQUE; k++) {
                        int i = fila + k;
                        n++;
                        bordes += borde[i];
                        if (lum[i] < textoOscuro || (contrasteFuerte && lum[i] < corte)) oscuros++;
                        else if (clase[i] != 0) fondo++;
                    }
                }
                // Debe haber trazos, tinta oscura y fondo de color de placa a la vez
                texto[by * bw + bx] = bordes >= 4 && oscuros >= 3 && oscuros <= n * 0.7 && fondo >= n * 0.2;
            }
        }

        // ---- 3) Unir bloques vecinos (también con un hueco, entre letras y números)
        java.util.Arrays.fill(puente, 0, bw * bh, false);
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                if (!texto[by * bw + bx]) continue;
                puente[by * bw + bx] = true;
                if (bx + 1 < bw) puente[by * bw + bx + 1] = true;
                if (bx + 2 < bw && texto[by * bw + bx + 2]) puente[by * bw + bx + 1] = true;
                if (bx > 0) puente[by * bw + bx - 1] = true;
            }
        }
        java.util.Arrays.fill(visto, 0, bw * bh, false);

        Resultado mejor = null;
        ArrayDeque<Integer> cola = new ArrayDeque<>();
        for (int s = 0; s < bw * bh; s++) {
            if (!puente[s] || visto[s]) continue;
            int minX = bw, minY = bh, maxX = -1, maxY = -1, nTexto = 0;
            cola.clear();
            cola.add(s);
            visto[s] = true;
            while (!cola.isEmpty()) {
                int c = cola.poll();
                int cx = c % bw, cy = c / bw;
                if (texto[c]) {
                    nTexto++;
                    if (cx < minX) minX = cx;
                    if (cx > maxX) maxX = cx;
                    if (cy < minY) minY = cy;
                    if (cy > maxY) maxY = cy;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = cx + dx, ny = cy + dy;
                        if (nx < 0 || ny < 0 || nx >= bw || ny >= bh) continue;
                        int ni = ny * bw + nx;
                        if (puente[ni] && !visto[ni]) { visto[ni] = true; cola.add(ni); }
                    }
                }
            }
            if (maxX < 0 || nTexto < 3) continue;

            int wB = maxX - minX + 1, hB = maxY - minY + 1;
            float rel = (float) (wB * BLOQUE) / (hB * BLOQUE);
            if (wB < 3 || hB < 1) continue;
            // Con bloques tan grandes una placa de una sola línea puede ocupar solo 1-2 bloques de alto
            if (rel < MIN_RELACION * 0.9f || rel > MAX_RELACION * 1.4f) continue;
            float densidad = (float) nTexto / (wB * hB);
            if (densidad < 0.30f) continue;
            float area = (float) (wB * hB) / (bw * bh);
            if (area > 0.45f) continue;

            // Rectángulo en píxeles (se agranda medio bloque por lado: los bloques recortan los bordes)
            int izq = Math.max(0, (minX * BLOQUE - BLOQUE / 2) * paso);
            int arr = Math.max(0, (minY * BLOQUE - BLOQUE / 2) * paso);
            int der = Math.min(uw, ((maxX + 1) * BLOQUE + BLOQUE / 2) * paso);
            int aba = Math.min(uh, ((maxY + 1) * BLOQUE + BLOQUE / 2) * paso);

            // Color de fondo de la placa: amarillo o blanco entre las celdas que no son tinta
            int amar = 0, blan = 0;
            int gx0 = minX * BLOQUE, gx1 = (maxX + 1) * BLOQUE, gy0 = minY * BLOQUE, gy1 = (maxY + 1) * BLOQUE;
            for (int gy = gy0; gy < gy1; gy++) {
                for (int gx = gx0; gx < gx1; gx++) {
                    byte c = clase[gy * gw + gx];
                    if (c == 1) amar++; else if (c == 2) blan++;
                }
            }
            int claros = amar + blan;
            if (claros < 12) continue;
            int color = amar >= blan ? AMARILLA : BLANCA;
            float pureza = (float) Math.max(amar, blan) / claros;
            if (pureza < 0.6f) continue;

            float forma = 1f;
            float relPx = (float) (der - izq) / Math.max(1, aba - arr);
            if (relPx < MIN_RELACION || relPx > MAX_RELACION) forma = 0.5f;
            float tamano = Math.min(1f, nTexto / 10f);
            float puntaje = densidad * pureza * forma * tamano;
            if (puntaje < MIN_PUNTAJE) continue;
            if (mejor == null || puntaje > mejor.puntaje) {
                mejor = new Resultado(izq, arr, der, aba, uw, uh, color, puntaje);
            }
        }
        return mejor;
    }

    /**
     * Recorta una zona del cuadro (ya derecho) y la devuelve como píxeles ARGB, lista para el OCR.
     * Solo convierte esa zona, no todo el cuadro: es mucho más rápido que convertir la imagen completa.
     * @return arreglo de (der-izq)*(aba-arr) píxeles
     */
    public static int[] recortar(ByteBuffer y, ByteBuffer u, ByteBuffer v,
                                 int yFila, int uvFila, int uvPaso,
                                 int ancho, int alto, int rotacion,
                                 int izq, int arr, int der, int aba) {
        final int w = Math.max(1, der - izq), h = Math.max(1, aba - arr);
        final int[] px = new int[w * h];
        final int ylim = y.limit(), ulim = u.limit(), vlim = v.limit();
        for (int j = 0; j < h; j++) {
            final int uy = arr + j;
            for (int i = 0; i < w; i++) {
                final int ux = izq + i;
                int bx, by;
                switch (rotacion) {
                    case 90:  bx = uy;             by = alto - 1 - ux; break;
                    case 180: bx = ancho - 1 - ux; by = alto - 1 - uy; break;
                    case 270: bx = ancho - 1 - uy; by = ux; break;
                    default:  bx = ux;             by = uy; break;
                }
                bx = clamp(bx, ancho);
                by = clamp(by, alto);
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

    /**
     * Igual que {@link #recortar} pero solo el brillo (0..255): mucho más rápido, para medir y para el OCR.
     * @param paso toma un píxel de cada «paso» (1 = sin reducir) para no pasarle imágenes enormes al OCR
     * @return arreglo de ((der-izq)/paso) * ((aba-arr)/paso) brillos; el tamaño lo da {@link #tamanoRecorte}
     */
    public static int[] recortarGris(ByteBuffer y, int yFila, int ancho, int alto, int rotacion,
                                     int izq, int arr, int der, int aba, int paso) {
        final int k = Math.max(1, paso);
        final int w = Math.max(1, (der - izq) / k), h = Math.max(1, (aba - arr) / k);
        final int[] g = new int[w * h];
        final int ylim = y.limit();
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
                bx = clamp(bx, ancho);
                by = clamp(by, alto);
                final int p = by * yFila + bx;
                g[j * w + i] = p < ylim ? y.get(p) & 0xFF : 0;
            }
        }
        return g;
    }

    /** Ancho y alto (en ese orden) que tendrá el resultado de {@link #recortarGris} con esos mismos límites. */
    public static int[] tamanoRecorte(int izq, int arr, int der, int aba, int paso) {
        final int k = Math.max(1, paso);
        return new int[]{Math.max(1, (der - izq) / k), Math.max(1, (aba - arr) / k)};
    }

    private static int clampI(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : (v >= max ? max - 1 : v);
    }

    private void preparar(int celdas) {
        if (lum.length < celdas) {
            lum = new int[celdas];
            dif = new int[celdas];
            cbA = new byte[celdas];
            crA = new byte[celdas];
            clase = new byte[celdas];
            borde = new byte[celdas];
        }
        int bloques = celdas / (BLOQUE * BLOQUE) + 64;
        if (texto.length < bloques) {
            texto = new boolean[bloques];
            puente = new boolean[bloques];
            visto = new boolean[bloques];
        }
    }
}
