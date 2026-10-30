package com.javier.reservas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reparto de las fotos que toma la cámara al registrar (validar o ingresar) entre todos los equipos por el Wi-Fi.
 *
 * Una foto pertenece a un EVENTO: «la validación (V) o el ingreso (I) de tal reserva, a tal hora». Todos los equipos saben
 * ese evento (se avisan entre sí), así que la foto se identifica con lo mismo: tipo + hora + código + placa. De ese
 * identificador sale el nombre del archivo y la clave con la que se lleva la cuenta de qué equipo ya recibió cuál.
 *
 * Java puro, sin Android, para poder probarse aparte.
 */
public final class FotosRed {

    private FotosRed() {
    }

    public static final String VALIDACION = "V";
    public static final String INGRESO = "I";

    /** Tamaño máximo de una foto que se acepta de otro equipo. */
    public static final int MAX_BYTES = 1_200_000;
    /** Carpeta (dentro de los archivos de la app) y prefijo de las fotos que se identifican por evento. */
    public static final String CARPETA = "fotos/";

    /** Una foto repartida: de qué evento es. */
    public static final class Meta {
        public final String tipo;
        public final long hora;
        public final String codigo;
        public final String placa;

        Meta(String tipo, long hora, String codigo, String placa) {
            this.tipo = tipo;
            this.hora = hora;
            this.codigo = codigo;
            this.placa = placa;
        }

        public String clave() {
            return FotosRed.clave(tipo, hora, codigo, placa);
        }

        public String nombre() {
            return FotosRed.nombre(tipo, hora, codigo, placa);
        }
    }

    public static boolean tipoValido(String t) {
        return VALIDACION.equals(t) || INGRESO.equals(t);
    }

    /** Solo letras (sin tildes) y números en mayúsculas: seguro para un nombre de archivo y para comparar. */
    private static String limpio(String s) {
        String k = EventoRed.clave(s);
        StringBuilder b = new StringBuilder(k.length());
        for (int i = 0; i < k.length() && b.length() < 30; i++) {
            char c = k.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) b.append(c);
        }
        return b.toString();
    }

    /** Identificador del evento al que pertenece una foto (igual en todos los equipos). */
    public static String clave(String tipo, long hora, String codigo, String placa) {
        return tipo + hora + "_" + limpio(codigo) + "_" + limpio(placa);
    }

    /** Nombre del archivo de la foto de un evento, dentro de la carpeta de archivos de la app. */
    public static String nombre(String tipo, long hora, String codigo, String placa) {
        return CARPETA + "e" + clave(tipo, hora, codigo, placa) + ".jpg";
    }

    /** Hora que lleva dentro una clave (0 si no se entiende). */
    public static long horaDeClave(String clave) {
        if (clave == null || clave.length() < 2) return 0L;
        int fin = clave.indexOf('_');
        if (fin < 2) return 0L;
        try {
            return Long.parseLong(clave.substring(1, fin));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------ lo que viaja con la foto

    public static String meta(String tipo, long hora, String codigo, String placa) {
        return EventoRed.escapar(tipo) + "\t" + hora + "\t" + EventoRed.escapar(codigo) + "\t" + EventoRed.escapar(placa);
    }

    /** @return null si no es una descripción válida de foto */
    public static Meta leerMeta(String texto) {
        if (texto == null) return null;
        String[] x = texto.split("\t", -1);
        if (x.length < 4) return null;
        try {
            String tipo = EventoRed.desescapar(x[0]);
            long hora = Long.parseLong(x[1]);
            String codigo = EventoRed.desescapar(x[2]).trim();
            String placa = EventoRed.desescapar(x[3]).trim();
            if (!tipoValido(tipo) || hora <= 0) return null;
            if (limpio(codigo).isEmpty() && limpio(placa).isEmpty()) return null;
            return new Meta(tipo, hora, codigo, placa);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** ¿Parece una imagen JPEG (o PNG)? Para no guardar basura que mande otro equipo. */
    public static boolean pareceImagen(byte[] b) {
        if (b == null || b.length < 16 || b.length > MAX_BYTES) return false;
        boolean jpeg = (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8;
        boolean png = (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        return jpeg || png;
    }

    // ------------------------------------------------------------------ qué equipo ya tiene qué foto

    /** Fotos que cada equipo ya confirmó tener. Se guarda en un archivo para no repetir envíos al reiniciar la app. */
    public static final class Acuses {
        private final Map<String, Set<String>> porEquipo = new HashMap<>();

        public synchronized boolean tiene(String equipo, String clave) {
            Set<String> s = porEquipo.get(equipo);
            return s != null && s.contains(clave);
        }

        /** @return true si era nueva */
        public synchronized boolean agregar(String equipo, String clave) {
            if (equipo == null || equipo.isEmpty() || clave == null || clave.isEmpty()) return false;
            Set<String> s = porEquipo.get(equipo);
            if (s == null) {
                s = new HashSet<>();
                porEquipo.put(equipo, s);
            }
            return s.add(clave);
        }

        public synchronized int total() {
            int n = 0;
            for (Set<String> s : porEquipo.values()) n += s.size();
            return n;
        }

        public synchronized void vaciar() {
            porEquipo.clear();
        }

        /** Olvida lo anterior a [horaMinima]. @return cuántas quitó */
        public synchronized int podar(long horaMinima) {
            int n = 0;
            for (Set<String> s : porEquipo.values()) {
                List<String> quitar = new ArrayList<>();
                for (String k : s) if (horaDeClave(k) < horaMinima) quitar.add(k);
                s.removeAll(quitar);
                n += quitar.size();
            }
            return n;
        }

        public synchronized String guardar() {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, Set<String>> e : porEquipo.entrySet()) {
                for (String k : e.getValue()) b.append(EventoRed.escapar(e.getKey())).append('\t').append(k).append('\n');
            }
            return b.toString();
        }

        public static Acuses cargar(String texto) {
            Acuses a = new Acuses();
            if (texto == null) return a;
            for (String linea : texto.split("\n")) {
                String[] x = linea.split("\t", -1);
                if (x.length == 2 && !x[1].isEmpty()) a.agregar(EventoRed.desescapar(x[0]), x[1]);
            }
            return a;
        }
    }

    // ------------------------------------------------------------------ cuántas fotos van «en camino» a cada equipo

    /**
     * Controla el envío de a pocas: cada equipo recibe como mucho [ventana] fotos sin confirmar; una foto sin respuesta se
     * da por perdida pasado [acuseMs]; una que el otro equipo rechazó no se repite hasta pasado [rechazoMs].
     */
    public static final class Envios {
        private final Map<String, Map<String, Long>> enVuelo = new HashMap<>();
        private final Map<String, Long> rechazadas = new HashMap<>();

        public synchronized int enCamino(String equipo, long ahora, long acuseMs) {
            Map<String, Long> v = enVuelo.get(equipo);
            if (v == null) return 0;
            v.values().removeIf(t -> ahora - t > acuseMs);
            return v.size();
        }

        /** ¿Se puede mandar ahora esta foto a este equipo? */
        public synchronized boolean puedeEnviar(String equipo, String clave, long ahora, int ventana, long acuseMs, long rechazoMs) {
            if (enCamino(equipo, ahora, acuseMs) >= ventana) return false;
            Map<String, Long> v = enVuelo.get(equipo);
            if (v != null && v.containsKey(clave)) return false;
            Long r = rechazadas.get(equipo + "|" + clave);
            return r == null || ahora - r >= rechazoMs;
        }

        public synchronized void enviada(String equipo, String clave, long ahora) {
            Map<String, Long> v = enVuelo.get(equipo);
            if (v == null) {
                v = new HashMap<>();
                enVuelo.put(equipo, v);
            }
            v.put(clave, ahora);
        }

        /** El otro equipo respondió (entregado = la guardó). */
        public synchronized void respondida(String equipo, String clave, boolean entregado, long ahora) {
            Map<String, Long> v = enVuelo.get(equipo);
            if (v != null) v.remove(clave);
            if (!entregado) rechazadas.put(equipo + "|" + clave, ahora);
            else rechazadas.remove(equipo + "|" + clave);
        }

        public synchronized void vaciar() {
            enVuelo.clear();
            rechazadas.clear();
        }
    }
}
