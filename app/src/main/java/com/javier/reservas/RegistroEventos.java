package com.javier.reservas;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Memoria de todo lo que han hecho los equipos cercanos (propios y ajenos), guardada en un archivo de texto.
 * Sirve para (1) no procesar dos veces el mismo evento, (2) reenviarlo a quien le falte y
 * (3) calcular el estado de una reserva con todo lo que se sabe de ella. Es seguro usarla desde varios hilos.
 */
public final class RegistroEventos {

    private final File archivo;
    private final Map<String, TreeMap<Long, EventoRed>> porOrigen = new HashMap<>();
    private final Map<String, List<EventoRed>> porClave = new HashMap<>();
    private int total = 0;

    public RegistroEventos(File archivo) {
        this.archivo = archivo;
        cargar();
    }

    private void cargar() {
        if (archivo == null || !archivo.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(archivo), StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = r.readLine()) != null) {
                EventoRed e = EventoRed.decodificarLinea(linea);
                if (e != null) indexar(e);
            }
        } catch (IOException ignorada) {
        }
    }

    private boolean indexar(EventoRed e) {
        TreeMap<Long, EventoRed> m = porOrigen.get(e.origen);
        if (m == null) {
            m = new TreeMap<>();
            porOrigen.put(e.origen, m);
        }
        if (m.containsKey(e.seq)) return false;
        m.put(e.seq, e);
        String kc = EventoRed.clave(e.codigo);
        String kp = EventoRed.clave(e.placa);
        if (!kc.isEmpty()) lista("C" + kc).add(e);
        if (!kp.isEmpty()) lista("P" + kp).add(e);
        total++;
        return true;
    }

    private List<EventoRed> lista(String k) {
        List<EventoRed> l = porClave.get(k);
        if (l == null) {
            l = new ArrayList<>(2);
            porClave.put(k, l);
        }
        return l;
    }

    /** @return true si es un evento nuevo (si ya estaba, no hace nada) */
    public synchronized boolean agregar(EventoRed e) {
        if (!indexar(e)) return false;
        if (archivo != null) {
            try (FileOutputStream f = new FileOutputStream(archivo, true)) {
                f.write((e.codificar() + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignorada) {
            }
        }
        return true;
    }

    public synchronized boolean tiene(String origen, long seq) {
        TreeMap<Long, EventoRed> m = porOrigen.get(origen);
        return m != null && m.containsKey(seq);
    }

    public synchronized int cantidad() {
        return total;
    }

    /** Último número de secuencia que usó [origen] (0 si no hay ninguno). */
    public synchronized long ultimaSeq(String origen) {
        TreeMap<Long, EventoRed> m = porOrigen.get(origen);
        return m == null || m.isEmpty() ? 0L : m.lastKey();
    }

    /** Todo lo que se sabe de una reserva (por su código o por su placa), del más antiguo al más nuevo. */
    public synchronized List<EventoRed> deReserva(String codigo, String placa) {
        Map<String, EventoRed> r = new LinkedHashMap<>();
        String kc = EventoRed.clave(codigo);
        String kp = EventoRed.clave(placa);
        if (!kc.isEmpty()) {
            List<EventoRed> l = porClave.get("C" + kc);
            if (l != null) for (EventoRed e : l) r.put(e.id(), e);
        }
        if (!kp.isEmpty()) {
            List<EventoRed> l = porClave.get("P" + kp);
            if (l != null) for (EventoRed e : l) r.put(e.id(), e);
        }
        List<EventoRed> out = new ArrayList<>(r.values());
        Collections.sort(out, new Comparator<EventoRed>() {
            @Override
            public int compare(EventoRed a, EventoRed b) {
                int c = Long.compare(a.hora, b.hora);
                return c != 0 ? c : a.id().compareTo(b.id());
            }
        });
        return out;
    }

    /** Todos los eventos desde [desdeHora], del más antiguo al más nuevo. */
    public synchronized List<EventoRed> desde(long desdeHora) {
        List<EventoRed> out = new ArrayList<>();
        for (TreeMap<Long, EventoRed> m : porOrigen.values()) {
            for (EventoRed e : m.values()) if (e.hora >= desdeHora) out.add(e);
        }
        Collections.sort(out, new Comparator<EventoRed>() {
            @Override
            public int compare(EventoRed a, EventoRed b) {
                int c = Long.compare(a.hora, b.hora);
                return c != 0 ? c : a.id().compareTo(b.id());
            }
        });
        return out;
    }

    private static long mezcla(long seq) {
        long x = seq * 0x9E3779B97F4A7C15L;
        return x ^ (x >>> 29);
    }

    /** Por cada equipo de origen: {cantidad, suma de huellas} de los eventos desde [desdeHora]. Sirve para comparar rápido. */
    public synchronized Map<String, long[]> resumen(long desdeHora) {
        Map<String, long[]> r = new HashMap<>();
        for (Map.Entry<String, TreeMap<Long, EventoRed>> en : porOrigen.entrySet()) {
            long n = 0, suma = 0;
            for (EventoRed e : en.getValue().values()) {
                if (e.hora < desdeHora) continue;
                n++;
                suma += mezcla(e.seq);
            }
            if (n > 0) r.put(en.getKey(), new long[]{n, suma});
        }
        return r;
    }

    /** Números de secuencia de [origen] desde [desdeHora], de menor a mayor. */
    public synchronized List<Long> seqsDe(String origen, long desdeHora) {
        List<Long> r = new ArrayList<>();
        TreeMap<Long, EventoRed> m = porOrigen.get(origen);
        if (m == null) return r;
        for (EventoRed e : m.values()) if (e.hora >= desdeHora) r.add(e.seq);
        return r;
    }

    /** Eventos de [origen] desde [desdeHora] cuyo número NO está en [yaTiene]. */
    public synchronized List<EventoRed> faltantes(String origen, long desdeHora, Set<Long> yaTiene) {
        List<EventoRed> r = new ArrayList<>();
        TreeMap<Long, EventoRed> m = porOrigen.get(origen);
        if (m == null) return r;
        for (EventoRed e : m.values()) if (e.hora >= desdeHora && !yaTiene.contains(e.seq)) r.add(e);
        return r;
    }

    /** Borra lo anterior a [desdeHora] (y reescribe el archivo). @return cuántos borró */
    public synchronized int podar(long desdeHora) {
        List<EventoRed> quedan = new ArrayList<>();
        int borrados = 0;
        for (TreeMap<Long, EventoRed> m : porOrigen.values()) {
            for (EventoRed e : m.values()) {
                if (e.hora >= desdeHora) quedan.add(e); else borrados++;
            }
        }
        if (borrados == 0) return 0;
        porOrigen.clear();
        porClave.clear();
        total = 0;
        Collections.sort(quedan, new Comparator<EventoRed>() {
            @Override
            public int compare(EventoRed a, EventoRed b) {
                return Long.compare(a.hora, b.hora);
            }
        });
        for (EventoRed e : quedan) indexar(e);
        if (archivo != null) {
            File tmp = new File(archivo.getPath() + ".tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                for (EventoRed e : quedan) w.write(e.codificar() + "\n");
            } catch (IOException ex) {
                tmp.delete();
                return borrados;
            }
            if (!tmp.renameTo(archivo)) {
                archivo.delete();
                tmp.renameTo(archivo);
            }
        }
        return borrados;
    }

    /** Equipos de origen que aparecen en el registro. */
    public synchronized Set<String> origenes() {
        return new HashSet<>(porOrigen.keySet());
    }
}
