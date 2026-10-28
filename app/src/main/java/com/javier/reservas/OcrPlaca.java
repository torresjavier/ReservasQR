package com.javier.reservas;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Convierte el texto que lee el OCR en placas válidas y corrige las confusiones típicas
 * (O/Q/D/0, I/L/J/1, B/8, S/5, Z/2, G/6, A/4, T/7) según el formato de placa colombiana:
 *   carro  ABC123  (3 letras + 3 números)
 *   moto   ABC12D  (3 letras + 2 números + 1 letra)
 *   moto   ABC12   (formato antiguo)
 */
public final class OcrPlaca {

    private OcrPlaca() {}

    /** Formatos: L = letra, D = dígito. */
    private static final String[] FORMATOS = {"LLLDDD", "LLLDDL", "LLLDD"};

    /** Máximo de caracteres corregidos para aceptar una lectura. */
    private static final int MAX_CORRECCIONES = 2;

    /** Caracteres que el OCR suele confundir entre sí. */
    private static final String[] GRUPOS = {"OQD0U", "IL1J", "B8", "S5", "Z2", "G6", "A4", "T7"};

    /** Una lectura posible de placa con cuántos caracteres hubo que corregir. */
    public static final class Lectura {
        public final String placa;
        public final int correcciones;

        Lectura(String placa, int correcciones) {
            this.placa = placa;
            this.correcciones = correcciones;
        }

        @Override public String toString() {
            return placa + "(" + correcciones + ")";
        }
    }

    /**
     * Saca las placas posibles de una línea de texto del OCR, por ejemplo "PYR·293" o "• ABC 12D •".
     * Las mejores (menos correcciones) primero.
     */
    public static List<Lectura> lecturas(String linea) {
        String n = Listado.normalizar(linea);
        String[] partes = n.split("[^A-Z0-9]+");
        Map<String, Integer> mejor = new LinkedHashMap<>();
        for (int i = 0; i < partes.length; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < Math.min(partes.length, i + 3); j++) {
                sb.append(partes[j]);
                String s = sb.toString();
                if (s.length() < 5 || s.length() > 7) continue;
                // Una placa siempre trae letras y números: evita leer "BOGOTA" o "COLOMBIA" como placa
                if (!s.matches(".*[0-9].*[0-9].*") || !s.matches(".*[A-Z].*[A-Z].*")) continue;
                for (String f : FORMATOS) {
                    if (f.length() != s.length()) continue;
                    int[] corr = {0};
                    String p = ajustar(s, f, corr);
                    if (p != null && corr[0] <= MAX_CORRECCIONES) {
                        Integer prev = mejor.get(p);
                        if (prev == null || corr[0] < prev) mejor.put(p, corr[0]);
                    }
                }
            }
        }
        List<Lectura> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : mejor.entrySet()) out.add(new Lectura(e.getKey(), e.getValue()));
        out.sort((a, b) -> Integer.compare(a.correcciones, b.correcciones));
        return out;
    }

    /** Adapta el texto al formato corrigiendo letras↔números; null si no se puede. */
    static String ajustar(String s, String formato, int[] correcciones) {
        StringBuilder r = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean letra = formato.charAt(i) == 'L';
            if (letra) {
                if (c >= 'A' && c <= 'Z') r.append(c);
                else {
                    char x = digitoALetra(c);
                    if (x == 0) return null;
                    r.append(x);
                    correcciones[0]++;
                }
            } else {
                if (c >= '0' && c <= '9') r.append(c);
                else {
                    char x = letraADigito(c);
                    if (x == 0) return null;
                    r.append(x);
                    correcciones[0]++;
                }
            }
        }
        return r.toString();
    }

    static char digitoALetra(char c) {
        switch (c) {
            case '0': return 'O';
            case '1': return 'I';
            case '2': return 'Z';
            case '4': return 'A';
            case '5': return 'S';
            case '6': return 'G';
            case '7': return 'T';
            case '8': return 'B';
            default: return 0;
        }
    }

    static char letraADigito(char c) {
        switch (c) {
            case 'O': case 'Q': case 'D': case 'U': return '0';
            case 'I': case 'L': case 'J': return '1';
            case 'Z': return '2';
            case 'A': return '4';
            case 'S': return '5';
            case 'G': return '6';
            case 'T': return '7';
            case 'B': return '8';
            default: return 0;
        }
    }

    /** true si dos caracteres son iguales o el OCR los suele confundir (por ejemplo O y Q, I y 1). */
    public static boolean parecidos(char a, char b) {
        if (a == b) return true;
        for (String g : GRUPOS) if (g.indexOf(a) >= 0 && g.indexOf(b) >= 0) return true;
        return false;
    }

    /** Representa cada grupo de caracteres confundibles con uno solo: "QYR2O3" y "OYR203" dan lo mismo. */
    public static String canonica(String placa) {
        StringBuilder r = new StringBuilder(placa.length());
        for (int i = 0; i < placa.length(); i++) {
            char c = placa.charAt(i);
            char rep = c;
            for (String g : GRUPOS) if (g.indexOf(c) >= 0) { rep = g.charAt(0); break; }
            r.append(rep);
        }
        return r.toString();
    }

    /** Compara dos placas ya normalizadas aceptando las confusiones del OCR. */
    public static boolean coinciden(String leida, String delListado) {
        if (leida.length() != delListado.length()) return false;
        for (int i = 0; i < leida.length(); i++) {
            if (!parecidos(leida.charAt(i), delListado.charAt(i))) return false;
        }
        return true;
    }
}
