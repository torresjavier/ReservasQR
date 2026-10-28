package com.javier.reservas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Junta las lecturas de varios cuadros seguidos y decide cuál es la placa.
 *
 * Con el vehículo en movimiento cada cuadro puede leer un carácter distinto (ABC123, A8C123, ABC1Z3…).
 * Aquí cada lectura vota con un peso (más si fue limpia y nítida) y, entre las lecturas que se parecen
 * (O/Q/0, I/1, B/8…), se elige carácter por carácter el que más votos tuvo.
 */
public final class VotoPlacas {

    /** Resultado del voto. */
    public static final class Consenso {
        public final String placa;
        /** Suma de pesos de las lecturas que apoyan esta placa. 1,0 ≈ una lectura limpia y nítida. */
        public final float peso;
        public final int lecturas;
        /** Cuántas de esas lecturas no necesitaron ninguna corrección. */
        public final int exactas;

        Consenso(String placa, float peso, int lecturas, int exactas) {
            this.placa = placa;
            this.peso = peso;
            this.lecturas = lecturas;
            this.exactas = exactas;
        }

        @Override public String toString() {
            return placa + " peso=" + String.format(java.util.Locale.ROOT, "%.2f", peso) + " n=" + lecturas + " exactas=" + exactas;
        }
    }

    private static final class Obs {
        final String placa;
        final float peso;
        final boolean exacta;
        final long t;

        Obs(String placa, float peso, boolean exacta, long t) {
            this.placa = placa;
            this.peso = peso;
            this.exacta = exacta;
            this.t = t;
        }
    }

    private final long ventanaMs;
    private final ArrayList<Obs> obs = new ArrayList<>();

    /** @param ventanaMs cuánto tiempo vale una lectura (2–3 s: lo que tarda un vehículo en pasar) */
    public VotoPlacas(long ventanaMs) {
        this.ventanaMs = ventanaMs;
    }

    public void limpiar() {
        obs.clear();
    }

    public boolean vacio() {
        return obs.isEmpty();
    }

    /**
     * @param placa        placa ya ajustada al formato (ABC123, ABC12D, ABC12)
     * @param correcciones cuántos caracteres hubo que corregir (0 = leída tal cual)
     * @param calidad      0,3–1,3: nitidez de la imagen y acuerdo con el tipo de vehículo visto
     */
    public void agregar(String placa, int correcciones, float calidad, long ahoraMs) {
        float base = correcciones <= 0 ? 1.0f : (correcciones == 1 ? 0.6f : 0.35f);
        float q = calidad < 0.3f ? 0.3f : (calidad > 1.3f ? 1.3f : calidad);
        obs.add(new Obs(placa, base * q, correcciones <= 0, ahoraMs));
        if (obs.size() > 60) obs.remove(0);
    }

    /** La placa con más apoyo en la ventana, o null si no hay lecturas. */
    public Consenso mejor(long ahoraMs) {
        for (Iterator<Obs> it = obs.iterator(); it.hasNext(); ) {
            if (ahoraMs - it.next().t > ventanaMs) it.remove();
        }
        if (obs.isEmpty()) return null;

        // Se agrupan las lecturas que son "la misma placa" salvo por las confusiones típicas
        Map<String, ArrayList<Obs>> grupos = new HashMap<>();
        for (Obs o : obs) {
            String clave = o.placa.length() + ":" + OcrPlaca.canonica(o.placa);
            ArrayList<Obs> g = grupos.get(clave);
            if (g == null) {
                g = new ArrayList<>();
                grupos.put(clave, g);
            }
            g.add(o);
        }

        Consenso mejor = null;
        for (ArrayList<Obs> g : grupos.values()) {
            float total = 0f;
            int exactas = 0;
            for (Obs o : g) {
                float edad = 1f - 0.4f * Math.min(1f, (float) (ahoraMs - o.t) / ventanaMs);
                total += o.peso * edad;
                if (o.exacta) exactas++;
            }
            if (mejor == null || total > mejor.peso) {
                mejor = new Consenso(porPosicion(g), total, g.size(), exactas);
            }
        }
        return mejor;
    }

    /** Carácter por carácter: el que más peso tiene. Si el resultado no es una placa válida, se usa la lectura más pesada. */
    private static String porPosicion(ArrayList<Obs> g) {
        int n = g.get(0).placa.length();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            Map<Character, Float> votos = new HashMap<>();
            for (Obs o : g) {
                char c = o.placa.charAt(i);
                Float v = votos.get(c);
                votos.put(c, (v == null ? 0f : v) + o.peso);
            }
            char mejorC = g.get(0).placa.charAt(i);
            float mejorV = -1f;
            for (Map.Entry<Character, Float> e : votos.entrySet()) {
                // en empate gana el carácter que está antes en el alfabeto: el resultado es siempre el mismo
                if (e.getValue() > mejorV + 1e-6f || (Math.abs(e.getValue() - mejorV) <= 1e-6f && e.getKey() < mejorC)) {
                    mejorV = e.getValue();
                    mejorC = e.getKey();
                }
            }
            sb.append(mejorC);
        }
        String s = sb.toString();
        if (OcrPlaca.esPlacaValida(s)) return s;
        Obs pesada = g.get(0);
        for (Obs o : g) if (o.peso > pesada.peso) pesada = o;
        return pesada.placa;
    }
}
