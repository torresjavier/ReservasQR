package com.javier.reservas;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

/**
 * Lector liviano de archivos .xlsx (primera hoja). No necesita librerías externas.
 * Devuelve una tabla de textos tal como se ven en Excel.
 */
public final class XlsxReader {

    private static final int NORMAL = 0, FECHA = 1, FECHA_HORA = 2;
    private static final long MAX_PARTE = 150L * 1024 * 1024;

    private XlsxReader() {}

    public static List<List<String>> read(InputStream in) throws IOException {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                if (name.startsWith("/")) name = name.substring(1);
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.startsWith("xl/") && (lower.endsWith(".xml") || lower.endsWith(".rels"))) {
                    parts.put(lower, readAll(zip));
                }
            }
        }
        byte[] workbook = parts.get("xl/workbook.xml");
        if (workbook == null) throw new IOException("El archivo no parece un Excel .xlsx válido.");

        List<String> shared = parseSharedStrings(parts.get("xl/sharedstrings.xml"));
        int[] styles = parseStyles(parts.get("xl/styles.xml"));

        byte[] sheet = null;
        String path = firstSheetPath(workbook, parts.get("xl/_rels/workbook.xml.rels"));
        if (path != null) sheet = parts.get(path.toLowerCase(Locale.ROOT));
        if (sheet == null) {
            String best = null;
            for (String k : parts.keySet()) {
                if (k.startsWith("xl/worksheets/sheet") && k.endsWith(".xml")) {
                    if (best == null || k.length() < best.length() || (k.length() == best.length() && k.compareTo(best) < 0)) best = k;
                }
            }
            if (best != null) sheet = parts.get(best);
        }
        if (sheet == null) throw new IOException("No se encontró ninguna hoja en el archivo.");
        return parseSheet(sheet, shared, styles);
    }

    // ---------- partes ----------

    private static List<String> parseSharedStrings(byte[] data) throws IOException {
        final List<String> out = new ArrayList<>();
        if (data == null) return out;
        parse(data, new DefaultHandler() {
            final StringBuilder sb = new StringBuilder();
            boolean inSi, inT, inRPh;

            @Override public void startElement(String u, String l, String q, Attributes a) {
                String n = local(q);
                if (n.equals("si")) { inSi = true; sb.setLength(0); }
                else if (n.equals("rPh")) inRPh = true;
                else if (n.equals("t") && inSi && !inRPh) inT = true;
            }

            @Override public void endElement(String u, String l, String q) {
                String n = local(q);
                if (n.equals("si")) { out.add(sb.toString()); inSi = false; }
                else if (n.equals("rPh")) inRPh = false;
                else if (n.equals("t")) inT = false;
            }

            @Override public void characters(char[] ch, int start, int len) {
                if (inT) sb.append(ch, start, len);
            }
        });
        return out;
    }

    private static int[] parseStyles(byte[] data) throws IOException {
        if (data == null) return new int[0];
        final Map<Integer, String> customFormats = new HashMap<>();
        final List<Integer> xfFormats = new ArrayList<>();
        parse(data, new DefaultHandler() {
            boolean inCellXfs;

            @Override public void startElement(String u, String l, String q, Attributes a) {
                String n = local(q);
                if (n.equals("numFmt")) {
                    try {
                        customFormats.put(Integer.parseInt(attr(a, "numFmtId")), attr(a, "formatCode"));
                    } catch (Exception ignored) { }
                } else if (n.equals("cellXfs")) inCellXfs = true;
                else if (n.equals("xf") && inCellXfs) {
                    int id = 0;
                    try { id = Integer.parseInt(attr(a, "numFmtId")); } catch (Exception ignored) { }
                    xfFormats.add(id);
                }
            }

            @Override public void endElement(String u, String l, String q) {
                if (local(q).equals("cellXfs")) inCellXfs = false;
            }
        });
        int[] res = new int[xfFormats.size()];
        for (int i = 0; i < res.length; i++) {
            int id = xfFormats.get(i);
            String code = customFormats.get(id);
            if (code != null) res[i] = classify(code);
            else if ((id >= 14 && id <= 17)) res[i] = FECHA;
            else if (id == 22) res[i] = FECHA_HORA;
            else res[i] = NORMAL;
        }
        return res;
    }

    private static int classify(String code) {
        String c = code.replaceAll("\"[^\"]*\"", "").replaceAll("\\[[^\\]]*\\]", "").toLowerCase(Locale.ROOT);
        boolean fecha = c.contains("d") || c.contains("y") || c.contains("a"); // "a" = año en formatos en español
        if (c.contains("general") || c.contains("0") || c.contains("#")) fecha = false;
        if (!fecha) return NORMAL;
        return c.contains("h") ? FECHA_HORA : FECHA;
    }

    private static String firstSheetPath(byte[] workbook, byte[] rels) throws IOException {
        final String[] rid = {null};
        parse(workbook, new DefaultHandler() {
            @Override public void startElement(String u, String l, String q, Attributes a) {
                if (rid[0] == null && local(q).equals("sheet")) {
                    for (int i = 0; i < a.getLength(); i++) {
                        String an = a.getQName(i);
                        if (an.endsWith(":id")) { rid[0] = a.getValue(i); break; }
                    }
                }
            }
        });
        if (rid[0] == null || rels == null) return null;
        final String[] target = {null};
        parse(rels, new DefaultHandler() {
            @Override public void startElement(String u, String l, String q, Attributes a) {
                if (local(q).equals("Relationship") && rid[0].equals(attr(a, "Id"))) target[0] = attr(a, "Target");
            }
        });
        String t = target[0];
        if (t == null) return null;
        if (t.startsWith("/")) return t.substring(1);
        if (t.startsWith("../")) return t.substring(3);
        return "xl/" + t;
    }

    private static List<List<String>> parseSheet(byte[] data, final List<String> shared, final int[] styles) throws IOException {
        final List<List<String>> rows = new ArrayList<>();
        parse(data, new DefaultHandler() {
            List<String> row;
            final StringBuilder val = new StringBuilder();
            String type;
            int style, col, lastCol;
            boolean inV, inIs, inT, inCell;

            @Override public void startElement(String u, String l, String q, Attributes a) {
                String n = local(q);
                switch (n) {
                    case "row":
                        row = new ArrayList<>();
                        lastCol = -1;
                        break;
                    case "c":
                        inCell = true;
                        val.setLength(0);
                        type = attr(a, "t");
                        String s = attr(a, "s");
                        style = 0;
                        if (s != null) try { style = Integer.parseInt(s); } catch (NumberFormatException ignored) { }
                        String ref = attr(a, "r");
                        col = ref != null ? columnIndex(ref) : lastCol + 1;
                        if (col < 0) col = lastCol + 1;
                        break;
                    case "v":
                        if (inCell) inV = true;
                        break;
                    case "is":
                        if (inCell) inIs = true;
                        break;
                    case "t":
                        if (inIs) inT = true;
                        break;
                }
            }

            @Override public void endElement(String u, String l, String q) {
                String n = local(q);
                switch (n) {
                    case "v": inV = false; break;
                    case "t": inT = false; break;
                    case "is": inIs = false; break;
                    case "c":
                        inCell = false;
                        if (row == null) row = new ArrayList<>();
                        while (row.size() < col) row.add("");
                        String v = convert(val.toString(), type, style, shared, styles);
                        if (row.size() == col) row.add(v); else row.set(col, v);
                        lastCol = col;
                        break;
                    case "row":
                        if (row != null) rows.add(row);
                        row = null;
                        break;
                }
            }

            @Override public void characters(char[] ch, int start, int len) {
                if (inV || inT) val.append(ch, start, len);
            }
        });
        return rows;
    }

    private static String convert(String raw, String type, int style, List<String> shared, int[] styles) {
        if (type == null) type = "n";
        switch (type) {
            case "s":
                try {
                    int i = Integer.parseInt(raw.trim());
                    return i >= 0 && i < shared.size() ? shared.get(i) : "";
                } catch (NumberFormatException e) { return raw; }
            case "inlineStr":
            case "str":
            case "e":
                return raw;
            case "b":
                return "1".equals(raw.trim()) ? "VERDADERO" : "FALSO";
            case "d":
                return raw;
            default:
                if (raw.trim().isEmpty()) return "";
                int kind = style >= 0 && style < styles.length ? styles[style] : NORMAL;
                if (kind != NORMAL) {
                    String f = formatDate(raw, kind == FECHA_HORA);
                    if (f != null) return f;
                }
                return formatNumber(raw);
        }
    }

    static String formatNumber(String raw) {
        try {
            BigDecimal bd = new BigDecimal(raw.trim()).round(new MathContext(15));
            String s = bd.stripTrailingZeros().toPlainString();
            return s.equals("-0") ? "0" : s;
        } catch (NumberFormatException e) {
            return raw.trim();
        }
    }

    private static String formatDate(String raw, boolean withTime) {
        try {
            double d = Double.parseDouble(raw.trim());
            if (d < 1 || d > 2958465) return null;
            TimeZone utc = TimeZone.getTimeZone("UTC");
            Calendar c = Calendar.getInstance(utc);
            c.clear();
            c.set(1899, Calendar.DECEMBER, 30, 0, 0, 0);
            long ms = c.getTimeInMillis() + Math.round(d * 86400.0) * 1000L;
            SimpleDateFormat f = new SimpleDateFormat(withTime ? "dd/MM/yyyy HH:mm" : "dd/MM/yyyy", java.util.Locale.ROOT);
            f.setTimeZone(utc);
            return f.format(new java.util.Date(ms));
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- utilidades ----------

    static int columnIndex(String ref) {
        int n = 0, i = 0;
        while (i < ref.length()) {
            char c = Character.toUpperCase(ref.charAt(i));
            if (c < 'A' || c > 'Z') break;
            n = n * 26 + (c - 'A' + 1);
            i++;
        }
        return n - 1;
    }

    private static String local(String q) {
        int i = q.indexOf(':');
        return i >= 0 ? q.substring(i + 1) : q;
    }

    private static String attr(Attributes a, String name) {
        for (int i = 0; i < a.getLength(); i++) {
            if (local(a.getQName(i)).equals(name)) return a.getValue(i);
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        long total = 0;
        int r;
        while ((r = in.read(buf)) > 0) {
            total += r;
            if (total > MAX_PARTE) throw new IOException("El archivo es demasiado grande.");
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    private static void parse(byte[] data, DefaultHandler h) throws IOException {
        try {
            SAXParserFactory f = SAXParserFactory.newInstance();
            f.setNamespaceAware(false);
            try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
            try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) { }
            try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) { }
            f.newSAXParser().parse(new ByteArrayInputStream(data), h);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("No se pudo leer el Excel: " + e.getMessage(), e);
        }
    }
}
