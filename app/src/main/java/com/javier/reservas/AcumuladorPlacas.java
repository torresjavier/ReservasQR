package com.javier.reservas;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * Junta las lecturas del modelo de varios cuadros seguidos (el vehículo va pasando y cada cuadro ve la placa un poco distinta).
 *
 * En vez de votar por texto, promedia las log-probabilidades de cada posición, dando más peso a los cuadros nítidos y a
 * los que el modelo vio con seguridad. Un cuadro que solo vio basura (puntaje muy bajo) ni se cuenta.
 */
public final class AcumuladorPlacas {

    /** Resultado del promedio de la ventana. */
    public static final class Promedio {
        /** Log-probabilidades medias por posición y símbolo. */
        public final float[][] logp;
        /** Suma de pesos de los cuadros usados. 1,0 ≈ un cuadro limpio y nítido. */
        public final float peso;
        public final int cuadros;

        Promedio(float[][] logp, float peso, int cuadros) {
            this.logp = logp;
            this.peso = peso;
            this.cuadros = cuadros;
        }
    }

    private static final class Cuadro {
        final float[][] logp;
        final float peso;
        final String lectura;
        final long t;

        Cuadro(float[][] logp, float peso, String lectura, long t) {
            this.logp = logp;
            this.peso = peso;
            this.lectura = lectura;
            this.t = t;
        }
    }

    /** Un cuadro cuyo mejor puntaje es peor que esto no se usa (lo que se vio no parece una placa). */
    public static final float PUNTAJE_MINIMO = -9f;

    private final long ventanaMs;
    private final ArrayList<Cuadro> cuadros = new ArrayList<>();

    /** @param ventanaMs cuánto vale una lectura (2–3 s: lo que tarda un vehículo en pasar) */
    public AcumuladorPlacas(long ventanaMs) {
        this.ventanaMs = ventanaMs;
    }

    public void limpiar() {
        cuadros.clear();
    }

    public boolean vacio() {
        return cuadros.isEmpty();
    }

    /**
     * @param logp    log-probabilidades del cuadro (posiciones × símbolos)
     * @param calidad 0,3–1,3: nitidez de la imagen de la placa
     * @return false si el cuadro se descartó por no parecer una placa
     */
    public boolean agregar(float[][] logp, float calidad, long ahoraMs) {
        FormatoPlaca.Decodificado d = FormatoPlaca.decodificar(logp);
        if (d == null || d.puntaje < PUNTAJE_MINIMO) return false;
        float q = calidad < 0.3f ? 0.3f : (calidad > 1.3f ? 1.3f : calidad);
        // Seguridad del modelo: puntaje 0 → 1; −2 → 0,5; −3,8 o peor → 0,05
        float seguridad = Math.max(0.05f, Math.min(1f, 1f + d.puntaje / 4f));
        cuadros.add(new Cuadro(logp, q * seguridad, d.placa, ahoraMs));
        if (cuadros.size() > 40) cuadros.remove(0);
        return true;
    }

    /** Promedio de lo visto en la ventana, o null si no hay cuadros. */
    public Promedio promedio(long ahoraMs) {
        for (Iterator<Cuadro> it = cuadros.iterator(); it.hasNext(); ) {
            if (ahoraMs - it.next().t > ventanaMs) it.remove();
        }
        if (cuadros.isEmpty()) return null;
        int filas = cuadros.get(0).logp.length;
        int cols = FormatoPlaca.ALFABETO.length();
        float[][] suma = new float[filas][cols];
        float total = 0f;
        for (Cuadro c : cuadros) {
            float edad = 1f - 0.4f * Math.min(1f, (float) (ahoraMs - c.t) / ventanaMs);
            float w = c.peso * edad;
            total += w;
            for (int i = 0; i < filas; i++) {
                for (int k = 0; k < cols; k++) suma[i][k] += w * c.logp[i][k];
            }
        }
        if (total <= 0f) return null;
        for (int i = 0; i < filas; i++) {
            for (int k = 0; k < cols; k++) suma[i][k] /= total;
        }
        return new Promedio(suma, total, cuadros.size());
    }

    /** En cuántos cuadros de la ventana la mejor lectura, por sí sola, fue exactamente esta placa. */
    public int coincidencias(String placa) {
        int n = 0;
        for (Cuadro c : cuadros) if (c.lectura.equals(placa)) n++;
        return n;
    }
}
