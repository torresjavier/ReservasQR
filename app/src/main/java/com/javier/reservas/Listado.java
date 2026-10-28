package com.javier.reservas;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Listado de reservas cargado desde el Excel: columnas, búsqueda por QR / placa y registro de ingresos.
 * Sin dependencias de Android para poder probarlo fuera del teléfono.
 */
public final class Listado {

    /** Columnas que entiende la app. */
    public enum Campo {
        NOMBRE("NOMBRE", "Nombre del cliente", "Ana Gómez", false),
        CODIGO("CODIGO_RESERVA", "Código de la reserva (el que va en el QR)", "R-1001", true),
        PLACA("PLACA", "Placa del vehículo", "ABC123", true),
        EVENTO("EVENTO", "Nombre del evento", "Concierto Movistar Arena", false),
        TIPO("TIPO", "Tipo de cliente: VIP o NORMAL", "VIP", false);

        public final String titulo;
        public final String descripcion;
        public final String ejemplo;
        /** Al menos una de las columnas clave (código o placa) debe existir. */
        public final boolean clave;

        Campo(String titulo, String descripcion, String ejemplo, boolean clave) {
            this.titulo = titulo;
            this.descripcion = descripcion;
            this.ejemplo = ejemplo;
            this.clave = clave;
        }
    }

    private static final Pattern NO_ALNUM = Pattern.compile("[^A-Z0-9]+");
    private static final Pattern MARCAS = Pattern.compile("\\p{M}+");
    private static final Pattern SEPARA_TOKENS = Pattern.compile("[^A-Z0-9\\-_.]+");

    public String nombreArchivo = "";
    public List<String> encabezados = new ArrayList<>();
    public List<List<String>> filas = new ArrayList<>();
    /** Índice de columna para cada {@link Campo} (por ordinal); -1 = no existe. */
    public final int[] columnas = new int[Campo.values().length];
    /** fila → momento del ingreso (ms). */
    public final Map<Integer, Long> ingresos = new HashMap<>();
    /** fila → momento en que se validó en la vía (primer momento), antes de ingresar y recibir el ticket. */
    public final Map<Integer, Long> validaciones = new HashMap<>();

    private Map<String, List<Integer>> idxCodigo = new HashMap<>();
    private Map<String, List<Integer>> idxPlaca = new HashMap<>();
    private Map<String, List<Integer>> idxTodo = new HashMap<>();
    /** Placa "canónica" (O=Q=D=0, I=L=1, B=8…) → filas. Búsqueda OCR instantánea aunque haya miles. */
    private Map<String, List<Integer>> idxCanon = new HashMap<>();

    public Listado() {
        java.util.Arrays.fill(columnas, -1);
    }

    // ---------------------------------------------------------------- carga

    public static Listado desdeTabla(String nombre, List<List<String>> tabla) {
        List<List<String>> utiles = new ArrayList<>();
        for (List<String> f : tabla) {
            boolean vacia = true;
            for (String v : f) if (v != null && !v.trim().isEmpty()) { vacia = false; break; }
            if (!vacia) utiles.add(f);
        }
        if (utiles.size() < 2) {
            throw new IllegalArgumentException("El archivo no tiene reservas. La primera fila debe tener los títulos de las columnas y debajo, una reserva por fila.");
        }
        int ancho = 0;
        for (List<String> f : utiles) ancho = Math.max(ancho, f.size());

        Listado l = new Listado();
        l.nombreArchivo = nombre;
        List<String> enc = new ArrayList<>();
        for (int c = 0; c < ancho; c++) {
            String h = c < utiles.get(0).size() && utiles.get(0).get(c) != null ? utiles.get(0).get(c).trim() : "";
            enc.add(h.isEmpty() ? "Columna " + XlsxWriter.colName(c) : h);
        }
        l.encabezados = enc;
        for (int r = 1; r < utiles.size(); r++) {
            List<String> src = utiles.get(r);
            List<String> fila = new ArrayList<>(ancho);
            for (int c = 0; c < ancho; c++) {
                String v = c < src.size() && src.get(c) != null ? src.get(c).trim() : "";
                fila.add(v);
            }
            l.filas.add(fila);
        }
        l.autoMapear();
        return l;
    }

    /** Listado vacío con las columnas estándar (para cuando las reservas llegan solo del servidor). */
    public static Listado vacio(String nombre) {
        Listado l = new Listado();
        l.nombreArchivo = nombre;
        for (Campo c : Campo.values()) {
            l.encabezados.add(c.titulo);
            l.columnas[c.ordinal()] = c.ordinal();
        }
        l.reindexar();
        return l;
    }

    /** Detecta qué columna es cada campo según el título. */
    public void autoMapear() {
        java.util.Arrays.fill(columnas, -1);
        String[] h = new String[encabezados.size()];
        for (int i = 0; i < h.length; i++) h[i] = " " + NO_ALNUM.matcher(normalizar(encabezados.get(i))).replaceAll(" ").trim() + " ";

        // 1) Títulos exactos de la plantilla
        String[][] exactos = {
                {"NOMBRE", "NOMBRES", "CLIENTE", "NOMBRE CLIENTE", "NOMBRE DEL CLIENTE"},
                {"CODIGO RESERVA", "CODIGO DE RESERVA", "CODIGO", "RESERVA", "QR", "CODIGO QR", "ID RESERVA", "ID", "LOCALIZADOR"},
                {"PLACA", "PLACAS", "PLACA VEHICULO", "MATRICULA"},
                {"EVENTO", "NOMBRE EVENTO", "NOMBRE DEL EVENTO"},
                {"TIPO", "TIPO CLIENTE", "TIPO DE CLIENTE", "CATEGORIA"}
        };
        boolean[] usada = new boolean[h.length];
        for (Campo c : Campo.values()) {
            for (String e : exactos[c.ordinal()]) {
                for (int i = 0; i < h.length && columnas[c.ordinal()] < 0; i++) {
                    if (!usada[i] && h[i].trim().equals(e)) { columnas[c.ordinal()] = i; usada[i] = true; }
                }
            }
        }

        // 2) Títulos que contienen palabras clave (el orden evita confusiones como "NOMBRE DEL EVENTO")
        Object[][] reglas = {
                {Campo.EVENTO, new String[]{"EVENTO"}},
                {Campo.TIPO, new String[]{"TIPO", "CATEGORIA", "CLASE", "NIVEL"}},
                {Campo.PLACA, new String[]{"PLACA", "VEHICULO", "MATRICULA", "PATENTE"}},
                {Campo.CODIGO, new String[]{"CODIGO", "RESERVA", "QR", "LOCALIZADOR", "TICKET", "BOLETA"}},
                {Campo.NOMBRE, new String[]{"NOMBRE", "CLIENTE", "TITULAR", "INVITADO", "PERSONA"}}
        };
        for (int i = 0; i < h.length; i++) {
            if (usada[i]) continue;
            for (Object[] r : reglas) {
                Campo c = (Campo) r[0];
                boolean coincide = false;
                for (String p : (String[]) r[1]) if (h[i].contains(" " + p + " ") || h[i].contains(" " + p)) { coincide = true; break; }
                if (coincide) {
                    if (columnas[c.ordinal()] < 0) { columnas[c.ordinal()] = i; usada[i] = true; }
                    break;
                }
            }
        }
        reindexar();
    }

    public void asignar(Campo c, int columna) {
        columnas[c.ordinal()] = columna >= 0 && columna < encabezados.size() ? columna : -1;
        reindexar();
    }

    public int columna(Campo c) {
        return columnas[c.ordinal()];
    }

    public boolean tieneClave() {
        return columna(Campo.CODIGO) >= 0 || columna(Campo.PLACA) >= 0;
    }

    public void reindexar() {
        idxCodigo = new HashMap<>();
        idxPlaca = new HashMap<>();
        idxTodo = new HashMap<>();
        idxCanon = new HashMap<>();
        int cc = columna(Campo.CODIGO), cp = columna(Campo.PLACA);
        for (int i = 0; i < filas.size(); i++) {
            List<String> f = filas.get(i);
            if (cc >= 0) {
                String v = f.get(cc);
                agregar(idxCodigo, normalizar(v), i);
                String p = normPlaca(v);
                agregar(idxCodigo, p, i);
                agregar(idxCodigo, sinCeros(p), i);
            }
            if (cp >= 0) {
                String pl = normPlaca(f.get(cp));
                agregar(idxPlaca, pl, i);
                agregar(idxCanon, OcrPlaca.canonica(pl), i);
            }
            if (!tieneClave()) {
                for (String v : f) {
                    agregar(idxTodo, normalizar(v), i);
                    agregar(idxTodo, sinCeros(normPlaca(v)), i);
                }
            }
        }
    }

    private static void agregar(Map<String, List<Integer>> m, String k, int fila) {
        if (k == null || k.isEmpty()) return;
        List<Integer> l = m.get(k);
        if (l == null) { l = new ArrayList<>(1); m.put(k, l); }
        if (!l.contains(fila)) l.add(fila);
    }

    // ---------------------------------------------------------------- consultas

    public String valor(int fila, Campo c) {
        int col = columna(c);
        if (col < 0 || fila < 0 || fila >= filas.size()) return "";
        return filas.get(fila).get(col);
    }

    public boolean esVip(int fila) {
        return normalizar(valor(fila, Campo.TIPO)).contains("VIP");
    }

    public boolean ingresado(int fila) {
        return ingresos.containsKey(fila);
    }

    /** Texto principal para mostrar una reserva cuando falta la placa. */
    public String titulo(int fila) {
        for (Campo c : new Campo[]{Campo.PLACA, Campo.CODIGO, Campo.NOMBRE}) {
            String v = valor(fila, c);
            if (!v.isEmpty()) return v;
        }
        for (String v : filas.get(fila)) if (!v.isEmpty()) return v;
        return "Fila " + (fila + 2);
    }

    /** Busca las filas que corresponden al contenido leído de un QR. */
    public List<Integer> buscarQr(String texto) {
        String n = normalizar(texto);
        if (n.isEmpty()) return Collections.emptyList();
        String p = normPlaca(n);

        List<Integer> r;
        if ((r = idxCodigo.get(n)) != null) return r;
        if ((r = idxCodigo.get(p)) != null) return r;
        if ((r = idxCodigo.get(sinCeros(p))) != null) return r;
        if ((r = idxPlaca.get(p)) != null) return r;
        if ((r = idxTodo.get(n)) != null) return r;
        if ((r = idxTodo.get(sinCeros(p))) != null) return r;

        // El QR puede traer más texto (un enlace, JSON, "Reserva: R-1001 Placa: ABC123"...)
        LinkedHashSet<Integer> res = new LinkedHashSet<>();
        for (String t : SEPARA_TOKENS.split(n)) {
            if (t.length() < 3) continue;
            String tp = normPlaca(t);
            for (Map<String, List<Integer>> m : java.util.Arrays.asList(idxCodigo, idxPlaca, idxTodo)) {
                List<Integer> x = m.get(t);
                if (x == null) x = m.get(tp);
                if (x == null) x = m.get(sinCeros(tp));
                if (x != null) res.addAll(x);
            }
            if (!res.isEmpty()) break;
        }
        return new ArrayList<>(res);
    }

    /** Búsqueda manual: placa (también parcial), código o nombre. Coincidencias exactas primero. */
    public List<Integer> buscarTexto(String consulta, int max) {
        String q = normPlaca(consulta);
        String qn = normalizar(consulta);
        List<Integer> exactas = new ArrayList<>(), empiezan = new ArrayList<>(), contienen = new ArrayList<>();
        if (q.isEmpty()) return exactas;
        for (int i = 0; i < filas.size(); i++) {
            String placa = normPlaca(valor(i, Campo.PLACA));
            String codigo = normPlaca(valor(i, Campo.CODIGO));
            if (placa.equals(q) || codigo.equals(q)) exactas.add(i);
            else if (placa.startsWith(q) || codigo.startsWith(q)) empiezan.add(i);
            else if (placa.contains(q) || codigo.contains(q)
                    || (qn.length() >= 3 && normalizar(valor(i, Campo.NOMBRE)).contains(qn))
                    || (!tieneClave() && filaContiene(i, qn))) contienen.add(i);
        }
        List<Integer> out = new ArrayList<>(exactas);
        out.addAll(empiezan);
        out.addAll(contienen);
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /**
     * Busca una placa leída por la cámara (OCR). Primero la coincidencia exacta; si no hay,
     * acepta las confusiones típicas (O/Q/0, I/1, B/8...). Devuelve las filas encontradas.
     */
    public List<Integer> buscarPlacaOcr(String leida) {
        String p = normPlaca(leida);
        if (p.length() < 4) return Collections.emptyList();
        List<Integer> exactas = idxPlaca.get(p);
        if (exactas != null) return exactas;
        List<Integer> parecidas = idxCanon.get(OcrPlaca.canonica(p));
        return parecidas != null ? parecidas : Collections.<Integer>emptyList();
    }

    /** true si la placa leída es exactamente igual a la del listado (sin correcciones). */
    public boolean placaExacta(String leida) {
        return idxPlaca.containsKey(normPlaca(leida));
    }

    /** Placa del listado, normalizada (sin espacios ni guiones). */
    public String placaNormal(int fila) {
        return normPlaca(valor(fila, Campo.PLACA));
    }

    /**
     * Agrega o actualiza reservas que llegan del servidor sin perder las del Excel ni los ingresos.
     * Se busca la reserva por código (o por placa si no trae código). Las columnas que no existan se crean.
     * @return {nuevas, actualizadas}
     */
    public int[] fusionar(List<String> encNuevos, List<List<String>> filasNuevas) {
        int nuevas = 0, actualizadas = 0;
        if (filasNuevas.isEmpty()) return new int[]{0, 0};
        List<List<String>> tabla = new ArrayList<>();
        tabla.add(encNuevos);
        tabla.addAll(filasNuevas);
        Listado t = desdeTabla("servidor", tabla);

        // Columna destino para cada columna del servidor
        int[] destino = new int[t.encabezados.size()];
        java.util.Arrays.fill(destino, -1);
        for (Campo c : Campo.values()) {
            int origen = t.columna(c);
            if (origen < 0) continue;
            if (columna(c) < 0) columnas[c.ordinal()] = agregarColumna(c.titulo);
            destino[origen] = columna(c);
        }
        for (int i = 0; i < destino.length; i++) {
            if (destino[i] >= 0) continue;
            String h = t.encabezados.get(i);
            int existente = -1;
            for (int j = 0; j < encabezados.size(); j++) {
                if (normalizar(encabezados.get(j)).equals(normalizar(h))) { existente = j; break; }
            }
            destino[i] = existente >= 0 ? existente : agregarColumna(h);
        }
        reindexar();

        for (int r = 0; r < t.filas.size(); r++) {
            String codigo = t.valor(r, Campo.CODIGO);
            String placa = t.valor(r, Campo.PLACA);
            int fila = -1;
            if (!codigo.isEmpty()) {
                List<Integer> x = idxCodigo.get(normalizar(codigo));
                if (x != null && !x.isEmpty()) fila = x.get(0);
            } else if (!placa.isEmpty()) {
                List<Integer> x = idxPlaca.get(normPlaca(placa));
                if (x != null && !x.isEmpty()) fila = x.get(0);
            }
            List<String> src = t.filas.get(r);
            if (fila < 0) {
                List<String> f = new ArrayList<>(encabezados.size());
                for (int c = 0; c < encabezados.size(); c++) f.add("");
                filas.add(f);
                fila = filas.size() - 1;
                nuevas++;
            } else {
                actualizadas++;
            }
            List<String> f = filas.get(fila);
            for (int c = 0; c < src.size(); c++) {
                String v = src.get(c);
                if (v != null && !v.isEmpty()) f.set(destino[c], v);
            }
            // reindexar parcial para que una reserva repetida en el mismo lote se actualice y no se duplique
            if (columna(Campo.CODIGO) >= 0) agregar(idxCodigo, normalizar(f.get(columna(Campo.CODIGO))), fila);
            if (columna(Campo.PLACA) >= 0) agregar(idxPlaca, normPlaca(f.get(columna(Campo.PLACA))), fila);
        }
        reindexar();
        return new int[]{nuevas, actualizadas};
    }

    private int agregarColumna(String titulo) {
        encabezados.add(titulo);
        for (List<String> f : filas) f.add("");
        return encabezados.size() - 1;
    }

    private boolean filaContiene(int fila, String qn) {
        for (String v : filas.get(fila)) if (normalizar(v).contains(qn)) return true;
        return false;
    }

    /** Ingresos, del más reciente al más antiguo. */
    public List<Integer> ingresados() {
        List<Integer> l = new ArrayList<>(ingresos.keySet());
        l.sort((a, b) -> Long.compare(ingresos.get(b), ingresos.get(a)));
        return l;
    }

    /** Reservas que aún no pasan por la vía ni han ingresado. */
    public List<Integer> pendientes() {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < filas.size(); i++) if (!ingresos.containsKey(i) && !validaciones.containsKey(i)) l.add(i);
        return l;
    }

    public boolean validado(int fila) {
        return validaciones.containsKey(fila);
    }

    /** Validadas en la vía que todavía no reciben ticket adentro; las que llevan más tiempo esperando, primero. */
    public List<Integer> validados() {
        List<Integer> l = new ArrayList<>();
        for (Integer f : validaciones.keySet()) if (!ingresos.containsKey(f)) l.add(f);
        l.sort((a, b) -> Long.compare(validaciones.get(a), validaciones.get(b)));
        return l;
    }

    public int cantidadValidados() {
        int n = 0;
        for (Integer f : validaciones.keySet()) if (!ingresos.containsKey(f)) n++;
        return n;
    }

    /** Busca una reserva por su código o, si no hay código, por su placa exacta. -1 si no existe. */
    public int buscarFila(String codigo, String placa) {
        if (codigo != null && !codigo.trim().isEmpty()) {
            List<Integer> x = idxCodigo.get(normalizar(codigo));
            if (x != null && !x.isEmpty()) return x.get(0);
        }
        if (placa != null && !placa.trim().isEmpty()) {
            List<Integer> x = idxPlaca.get(normPlaca(placa));
            if (x != null && !x.isEmpty()) return x.get(0);
        }
        return -1;
    }

    public List<List<String>> paraExportar(java.text.DateFormat formato) {
        List<List<String>> out = new ArrayList<>(filas.size() + 1);
        List<String> enc = new ArrayList<>(encabezados);
        enc.add("ESTADO");
        enc.add("HORA_VALIDACION");
        enc.add("HORA_INGRESO");
        out.add(enc);
        for (int i = 0; i < filas.size(); i++) {
            List<String> f = new ArrayList<>(filas.get(i));
            Long t = ingresos.get(i);
            Long v = validaciones.get(i);
            f.add(t != null ? "INGRESADO" : (v != null ? "VALIDADO" : "PENDIENTE"));
            f.add(v != null ? formato.format(new java.util.Date(v)) : "");
            f.add(t != null ? formato.format(new java.util.Date(t)) : "");
            out.add(f);
        }
        return out;
    }

    /** Filas para la plantilla de ejemplo. */
    public static List<List<String>> plantilla() {
        List<List<String>> t = new ArrayList<>();
        List<String> enc = new ArrayList<>();
        for (Campo c : Campo.values()) enc.add(c.titulo);
        t.add(enc);
        t.add(java.util.Arrays.asList("Ana Gómez", "R-1001", "ABC123", "Concierto Movistar Arena", "VIP"));
        t.add(java.util.Arrays.asList("Carlos Ruiz", "R-1002", "XYZ789", "Concierto Movistar Arena", "NORMAL"));
        t.add(java.util.Arrays.asList("Laura Pérez", "R-1003", "PYR293", "Concierto Movistar Arena", "NORMAL"));
        return t;
    }

    // ---------------------------------------------------------------- normalización

    public static String normalizar(String s) {
        if (s == null) return "";
        String t = MARCAS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
        return t.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /** "pyr-293", "PYR 293" → "PYR293" */
    public static String normPlaca(String s) {
        return NO_ALNUM.matcher(normalizar(s)).replaceAll("");
    }

    private static String sinCeros(String n) {
        if (n == null || n.length() < 2 || n.charAt(0) != '0') return null;
        for (int i = 0; i < n.length(); i++) if (!Character.isDigit(n.charAt(i))) return null;
        String t = n.replaceFirst("^0+", "");
        return t.isEmpty() ? "0" : t;
    }
}
