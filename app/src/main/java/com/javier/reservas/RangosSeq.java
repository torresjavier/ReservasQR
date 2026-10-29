package com.javier.reservas;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Lista de números de secuencia escrita en rangos: «5-9,12,40-41». Sin nada de Android. */
public final class RangosSeq {

    private RangosSeq() {}

    /** Máximo de números que se aceptan al leer (protege de textos mal formados o malintencionados). */
    private static final int MAXIMO = 500_000;

    public static String codificar(List<Long> ordenados) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < ordenados.size()) {
            long ini = ordenados.get(i);
            long fin = ini;
            int j = i + 1;
            while (j < ordenados.size() && ordenados.get(j) == fin + 1) {
                fin = ordenados.get(j);
                j++;
            }
            if (b.length() > 0) b.append(',');
            b.append(ini);
            if (fin > ini) b.append('-').append(fin);
            i = j;
        }
        return b.toString();
    }

    /** @return null si el texto no es válido */
    public static TreeSet<Long> decodificar(String texto) {
        TreeSet<Long> r = new TreeSet<>();
        if (texto == null || texto.isEmpty()) return r;
        for (String p : texto.split(",")) {
            if (p.isEmpty()) continue;
            try {
                int g = p.indexOf('-');
                if (g < 0) {
                    r.add(Long.parseLong(p));
                } else {
                    long a = Long.parseLong(p.substring(0, g));
                    long z = Long.parseLong(p.substring(g + 1));
                    if (z < a || z - a > MAXIMO) return null;
                    for (long x = a; x <= z; x++) r.add(x);
                }
            } catch (NumberFormatException e) {
                return null;
            }
            if (r.size() > MAXIMO) return null;
        }
        return r;
    }

    /** Ayuda para pruebas. */
    public static List<Long> lista(Iterable<Long> it) {
        List<Long> l = new ArrayList<>();
        for (Long x : it) l.add(x);
        return l;
    }
}
