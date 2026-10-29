package com.javier.reservas;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Lleva la tabla de reservas de un equipo a otro: la convierte en bytes comprimidos, la parte en trozos para que
 * quepa en los mensajes de la red y la vuelve a armar al otro lado. Sin nada de Android.
 */
public final class TablaRed {

    private TablaRed() {}

    /** Encabezados y filas de una tabla. */
    public static final class Tabla {
        public final List<String> encabezados;
        public final List<List<String>> filas;

        public Tabla(List<String> encabezados, List<List<String>> filas) {
            this.encabezados = encabezados;
            this.filas = filas;
        }
    }

    /** Primera línea: encabezados; las demás, una fila cada una. Celdas separadas por tabulador (con escape). Comprimido con gzip. */
    public static byte[] codificar(List<String> encabezados, List<List<String>> filas) {
        StringBuilder b = new StringBuilder(Math.max(1024, filas.size() * 64));
        linea(b, encabezados, encabezados.size());
        for (List<String> f : filas) linea(b, f, encabezados.size());
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(b.length() / 3 + 64);
            GZIPOutputStream gz = new GZIPOutputStream(bos);
            gz.write(b.toString().getBytes(StandardCharsets.UTF_8));
            gz.close();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void linea(StringBuilder b, List<String> celdas, int columnas) {
        for (int i = 0; i < columnas; i++) {
            if (i > 0) b.append('\t');
            b.append(EventoRed.escapar(i < celdas.size() ? celdas.get(i) : ""));
        }
        b.append('\n');
    }

    /** @return la tabla, o null si los bytes no son una tabla válida */
    public static Tabla decodificar(byte[] datos) {
        if (datos == null || datos.length == 0) return null;
        try {
            GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(datos));
            ByteArrayOutputStream bos = new ByteArrayOutputStream(datos.length * 4);
            byte[] buf = new byte[16 * 1024];
            int n;
            long total = 0;
            while ((n = gz.read(buf)) > 0) {
                total += n;
                if (total > 200L * 1024 * 1024) return null; // protección contra archivos comprimidos maliciosos
                bos.write(buf, 0, n);
            }
            String texto = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            String[] lineas = texto.split("\n", -1);
            if (lineas.length == 0 || lineas[0].isEmpty()) return null;
            List<String> enc = celdas(lineas[0], -1);
            if (enc.isEmpty()) return null;
            List<List<String>> filas = new ArrayList<>(lineas.length);
            for (int i = 1; i < lineas.length; i++) {
                if (lineas[i].isEmpty() && i == lineas.length - 1) break;
                filas.add(celdas(lineas[i], enc.size()));
            }
            return new Tabla(enc, filas);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static List<String> celdas(String linea, int columnas) {
        String[] x = linea.split("\t", -1);
        int n = columnas < 0 ? x.length : columnas;
        List<String> r = new ArrayList<>(n);
        for (int i = 0; i < n; i++) r.add(i < x.length ? EventoRed.desescapar(x[i]) : "");
        return r;
    }

    /** Huella corta de unos bytes (para saber si dos tablas son la misma sin compararlas). */
    public static String huella(byte[] datos) {
        CRC32 c = new CRC32();
        c.update(datos, 0, datos.length);
        return Long.toHexString(c.getValue()) + "-" + datos.length;
    }

    /** Parte [datos] en trozos de como mucho [max] bytes. */
    public static List<byte[]> trocear(byte[] datos, int max) {
        List<byte[]> r = new ArrayList<>();
        if (datos.length == 0) {
            r.add(new byte[0]);
            return r;
        }
        for (int i = 0; i < datos.length; i += max) {
            int fin = Math.min(datos.length, i + max);
            byte[] t = new byte[fin - i];
            System.arraycopy(datos, i, t, 0, t.length);
            r.add(t);
        }
        return r;
    }

    /** Junta los trozos que llegan (en cualquier orden) hasta tener el total. Es seguro usarlo desde varios hilos. */
    public static final class Ensamblador {
        private static final long CADUCA_MS = 5 * 60_000L;
        private static final int MAX_EN_CURSO = 6;

        private static final class Parcial {
            final byte[][] trozos;
            int llegados = 0;
            long total = 0;
            long ultima = System.currentTimeMillis();

            Parcial(int n) {
                trozos = new byte[n][];
            }
        }

        private final Map<String, Parcial> enCurso = new HashMap<>();

        /**
         * @param id     identifica la tabla que se está recibiendo
         * @param indice número de este trozo (desde 0)
         * @param total  cuántos trozos tiene la tabla
         * @return todos los bytes juntos cuando llegó el último trozo; si no, null
         */
        public synchronized byte[] agregar(String id, int indice, int total, byte[] trozo) {
            if (total < 1 || total > 512 || indice < 0 || indice >= total || trozo == null) return null;
            long ahora = System.currentTimeMillis();
            for (Iterator<Map.Entry<String, Parcial>> it = enCurso.entrySet().iterator(); it.hasNext(); ) {
                if (ahora - it.next().getValue().ultima > CADUCA_MS) it.remove();
            }
            Parcial p = enCurso.get(id);
            if (p == null || p.trozos.length != total) {
                if (enCurso.size() >= MAX_EN_CURSO) enCurso.clear();
                p = new Parcial(total);
                enCurso.put(id, p);
            }
            p.ultima = ahora;
            if (p.trozos[indice] == null) {
                p.trozos[indice] = trozo;
                p.llegados++;
                p.total += trozo.length;
            }
            if (p.llegados < total) return null;
            enCurso.remove(id);
            byte[] todo = new byte[(int) p.total];
            int o = 0;
            for (byte[] t : p.trozos) {
                System.arraycopy(t, 0, todo, o, t.length);
                o += t.length;
            }
            return todo;
        }
    }
}
