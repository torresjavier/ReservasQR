package com.javier.reservas;

import java.util.Calendar;
import java.util.Locale;

/**
 * Algo que hizo un equipo y que los demás equipos cercanos deben saber: validó una reserva en la vía, la ingresó,
 * o anuló una de esas dos cosas. Lo que se comparte entre equipos es lo mínimo (código, placa, hora y quién);
 * los datos de la persona no viajan por la red local.
 * Cada evento se identifica por su equipo de origen y un número de secuencia propio.
 * No usa nada de Android para poder probarse aparte.
 */
public final class EventoRed {

    public static final String VALIDACION = "V";
    public static final String INGRESO = "I";
    public static final String ANULA_VALIDACION = "AV";
    public static final String ANULA_INGRESO = "AI";

    /** Cantidad de campos de un evento ya codificado. */
    public static final int NUM_CAMPOS = 9;

    public final String origen;
    public final long seq;
    public final String tipo;
    /** Día de operación al que pertenece (yyyyMMdd; el día cambia a las 5 a. m.). */
    public final String jornada;
    public final String codigo;
    public final String placa;
    public final long hora;
    /** Nombre del equipo que lo hizo (para mostrarlo: «VÍA 1»). */
    public final String equipo;
    public final String operador;

    public EventoRed(String origen, long seq, String tipo, String jornada, String codigo, String placa,
                     long hora, String equipo, String operador) {
        this.origen = origen == null ? "" : origen;
        this.seq = seq;
        this.tipo = tipo == null ? "" : tipo;
        this.jornada = jornada == null ? "" : jornada;
        this.codigo = codigo == null ? "" : codigo.trim();
        this.placa = placa == null ? "" : placa.trim();
        this.hora = hora;
        this.equipo = equipo == null ? "" : equipo.trim();
        this.operador = operador == null ? "" : operador.trim();
    }

    public String id() {
        return origen + "#" + seq;
    }

    public static boolean tipoValido(String t) {
        return VALIDACION.equals(t) || INGRESO.equals(t) || ANULA_VALIDACION.equals(t) || ANULA_INGRESO.equals(t);
    }

    /** Clave para comparar códigos y placas: solo letras y números, en mayúsculas. */
    public static String clave(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) b.append(Character.toUpperCase(c));
        }
        return b.toString();
    }

    /** Día de operación de un instante: el día cambia a las 5 de la mañana (los eventos de la madrugada son del día anterior). */
    public static String jornadaDe(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms - 5L * 3_600_000L);
        return String.format(Locale.ROOT, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    // ------------------------------------------------------------------ texto de una línea (campos separados por tabulador)

    public static String escapar(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder b = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '\t': b.append("\\t"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    public static String desescapar(String s) {
        if (s == null || s.indexOf('\\') < 0) return s == null ? "" : s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 't': b.append('\t'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    default: b.append(n);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** Los {@link #NUM_CAMPOS} campos separados por tabulador, listos para enviar o guardar. */
    public String codificar() {
        return escapar(origen) + "\t" + seq + "\t" + escapar(tipo) + "\t" + escapar(jornada) + "\t"
                + escapar(codigo) + "\t" + escapar(placa) + "\t" + hora + "\t" + escapar(equipo) + "\t" + escapar(operador);
    }

    /**
     * @param partes línea ya separada por tabuladores
     * @param desde  posición del primer campo del evento dentro de [partes]
     * @return null si no es un evento válido
     */
    public static EventoRed decodificar(String[] partes, int desde) {
        if (partes == null || desde < 0 || partes.length - desde < NUM_CAMPOS) return null;
        try {
            String origen = desescapar(partes[desde]);
            long seq = Long.parseLong(partes[desde + 1]);
            String tipo = desescapar(partes[desde + 2]);
            String jornada = desescapar(partes[desde + 3]);
            String codigo = desescapar(partes[desde + 4]);
            String placa = desescapar(partes[desde + 5]);
            long hora = Long.parseLong(partes[desde + 6]);
            String equipo = desescapar(partes[desde + 7]);
            String operador = desescapar(partes[desde + 8]);
            if (origen.isEmpty() || seq <= 0 || hora <= 0 || !tipoValido(tipo)) return null;
            if (clave(codigo).isEmpty() && clave(placa).isEmpty()) return null;
            return new EventoRed(origen, seq, tipo, jornada, codigo, placa, hora, equipo, operador);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static EventoRed decodificarLinea(String linea) {
        if (linea == null) return null;
        return decodificar(linea.split("\t", -1), 0);
    }

    @Override
    public String toString() {
        return tipo + " " + (codigo.isEmpty() ? placa : codigo) + " @" + hora + " por " + equipo + " (" + id() + ")";
    }
}
