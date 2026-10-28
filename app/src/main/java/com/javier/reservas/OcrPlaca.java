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

    /** Tipo de vehículo según la placa. */
    public enum Tipo { CARRO, MOTO }

    /** Una lectura posible de placa con cuántos caracteres hubo que corregir. */
    public static final class Lectura {
        public final String placa;
        public final int correcciones;
        public final Tipo tipo;

        Lectura(String placa, int correcciones, Tipo tipo) {
            this.placa = placa;
            this.correcciones = correcciones;
            this.tipo = tipo;
        }

        @Override public String toString() {
            return placa + "(" + correcciones + "," + tipo + ")";
        }
    }

    /** CARRO si la placa es ABC123; MOTO si es ABC12D o ABC12. */
    public static Tipo tipoDePlaca(String placa) {
        String n = Listado.normalizar(placa);
        if (n.length() == 6 && Character.isDigit(n.charAt(5))) return Tipo.CARRO;
        return Tipo.MOTO;
    }

    /** true si el texto tiene la forma de una placa colombiana (carro o moto), sin corregir nada. */
    public static boolean esPlacaValida(String placa) {
        String n = Listado.normalizar(placa);
        for (String f : FORMATOS) {
            if (f.length() != n.length()) continue;
            int[] c = {0};
            if (ajustar(n, f, c) != null && c[0] == 0) return true;
        }
        return false;
    }

    /**
     * Tipo de vehículo que sugiere la forma de la placa en la imagen:
     * las de carro son largas (≈ 2:1) y las de moto casi cuadradas, con las letras en dos renglones.
     * Devuelve null si no se puede saber.
     */
    public static Tipo tipoPorForma(float relacionAnchoAlto, boolean dosRenglones) {
        if (dosRenglones) return Tipo.MOTO;
        if (relacionAnchoAlto >= 1.75f) return Tipo.CARRO;
        if (relacionAnchoAlto > 0f && relacionAnchoAlto <= 1.55f) return Tipo.MOTO;
        return null;
    }

    /**
     * Saca las placas posibles de una línea de texto del OCR, por ejemplo "PYR·293" o "• ABC 12D •".
     * Las mejores (menos correcciones) primero.
     */
    public static List<Lectura> lecturas(String linea) {
        return lecturas(linea, null);
    }

    /**
     * Igual que {@link #lecturas(String)}, pero si se sabe que la placa es de carro o de moto
     * (por su forma en la imagen) se prefiere ese tipo cuando hay dudas entre una letra y un número
     * al final (ABC120 de carro o ABC12O de moto).
     */
    public static List<Lectura> lecturas(String linea, Tipo pista) {
        return lecturas(linea, pista, false);
    }

    /** Con {@code fuerte} (placa de moto en dos renglones) un tipo distinto al de la imagen casi se descarta. */
    public static List<Lectura> lecturas(String linea, final Tipo pista, final boolean fuerte) {
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
        for (Map.Entry<String, Integer> e : mejor.entrySet()) {
            out.add(new Lectura(e.getKey(), e.getValue(), tipoDePlaca(e.getKey())));
        }
        // Menos correcciones primero; una placa de otro tipo que el que se ve en la imagen cuenta como media corrección más
        out.sort((a, b) -> {
            int castigo = fuerte ? 5 : 1;
            int ca = a.correcciones * 2 + (pista != null && a.tipo != pista ? castigo : 0);
            int cb = b.correcciones * 2 + (pista != null && b.tipo != pista ? castigo : 0);
            return Integer.compare(ca, cb);
        });
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
