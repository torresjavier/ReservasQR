package com.javier.reservas;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Las cuentas detrás de las gráficas de la jornada: cuántas reservas hay en cada estado, a qué hora se valida y se ingresa,
 * cuánto se espera entre la vía y la entrada, qué equipo registra más, carros y motos, alertas… Java puro, sin Android, para
 * poder probarse aparte. La pantalla ({@code EstadisticasActivity}) solo dibuja lo que sale de aquí.
 */
public final class Estadisticas {

    private Estadisticas() {
    }

    /** Un registro (validación en la vía «V» o ingreso «I») de una reserva, con su hora y el equipo que lo hizo. */
    public static final class Reg {
        public final String tipo;
        public final int fila;
        public final long hora;
        public final String quien;

        public Reg(String tipo, int fila, long hora, String quien) {
            this.tipo = tipo;
            this.fila = fila;
            this.hora = hora;
            this.quien = quien == null ? "" : quien;
        }
    }

    public static final class Resultado {
        /** Reservas del listado. */
        public int reservas;
        public int ingresadas;
        /** Pasaron por la vía y todavía no ingresan. */
        public int validadasSinIngresar;
        /** No han pasado por la vía ni han ingresado. */
        public int pendientes;
        /** Ingresaron sin que nadie las validara en la vía. */
        public int ingresadasSinVia;

        public int vipIngresadas;
        public int normalIngresadas;

        /** Vehículos que ya pasaron por la vía o ingresaron, según la forma de su placa (ABC123 = carro, ABC12D = moto). */
        public int carros;
        public int motos;
        /** Pasaron o ingresaron y no tienen placa en el listado. */
        public int sinPlaca;

        /** Validaciones e ingresos por hora del día (0 a 23, hora local). */
        public final int[] validadasPorHora = new int[24];
        public final int[] ingresadasPorHora = new int[24];
        /** Primera y última hora del día con algo (-1 si no hay nada). */
        public int horaMin = -1;
        public int horaMax = -1;

        /** Ingresos en los últimos 60 minutos. */
        public int ingresosUltimaHora;

        /** Reservas que tienen vía e ingreso: cuánto pasó entre una y otro. */
        public int conEspera;
        public long esperaMediaMs;
        public long esperaMaximaMs;

        /** Registros (validaciones + ingresos) por equipo, de más a menos. */
        public final LinkedHashMap<String, Integer> porEquipo = new LinkedHashMap<>();
        /** Por evento: {reservas, ingresadas}, de más a menos reservas. Vacío si el listado no tiene eventos distintos. */
        public final LinkedHashMap<String, int[]> porEvento = new LinkedHashMap<>();
        /** Alertas por motivo, de más a menos. */
        public final LinkedHashMap<String, Integer> alertas = new LinkedHashMap<>();
        public int totalAlertas;

        public long primerRegistro;
        public long ultimoRegistro;
        public int totalRegistros;

        /** Qué parte de las reservas ya ingresó, de 0 a 1. */
        public double tasaIngreso() {
            return reservas == 0 ? 0.0 : (double) ingresadas / reservas;
        }

        /** Alcanza para dibujar algo. */
        public boolean hayDatos() {
            return reservas > 0;
        }
    }

    /**
     * @param l       el listado de reservas con sus validaciones e ingresos
     * @param regs    todos los registros (validaciones e ingresos) con su equipo
     * @param alertas los motivos de las alertas de la jornada (uno por alerta)
     * @param ahora   el momento actual (para «la última hora»)
     * @param zona    la zona horaria para las horas del día
     */
    public static Resultado calcular(Listado l, List<Reg> regs, List<String> alertas, long ahora, TimeZone zona) {
        Resultado r = new Resultado();
        if (l == null) return r;
        if (zona == null) zona = TimeZone.getDefault();
        r.reservas = l.filas.size();

        // Estado de cada reserva
        for (int f = 0; f < r.reservas; f++) {
            boolean ing = l.ingresos.containsKey(f);
            boolean val = l.validaciones.containsKey(f);
            if (ing) {
                r.ingresadas++;
                if (!val) r.ingresadasSinVia++;
                if (l.esVip(f)) r.vipIngresadas++; else r.normalIngresadas++;
            } else if (val) {
                r.validadasSinIngresar++;
            } else {
                r.pendientes++;
            }
            if (ing || val) {
                String placa = l.valor(f, Listado.Campo.PLACA);
                if (Listado.normPlaca(placa).isEmpty()) r.sinPlaca++;
                else if (OcrPlaca.tipoDePlaca(placa) == OcrPlaca.Tipo.CARRO) r.carros++;
                else r.motos++;
            }
        }

        // Espera entre la vía y el ingreso
        long suma = 0;
        for (Map.Entry<Integer, Long> e : l.ingresos.entrySet()) {
            Long v = l.validaciones.get(e.getKey());
            if (v == null || e.getKey() >= r.reservas) continue;
            long dt = e.getValue() - v;
            if (dt < 0) continue;   // relojes distintos: no cuenta
            suma += dt;
            r.conEspera++;
            if (dt > r.esperaMaximaMs) r.esperaMaximaMs = dt;
        }
        if (r.conEspera > 0) r.esperaMediaMs = suma / r.conEspera;

        // Registros: por hora, por equipo y la última hora
        Map<String, Integer> equipos = new LinkedHashMap<>();
        Calendar cal = Calendar.getInstance(zona);
        if (regs != null) {
            for (Reg g : regs) {
                if (g == null || g.fila < 0 || g.fila >= r.reservas) continue;
                boolean ingreso = FotosRed.INGRESO.equals(g.tipo);
                if (!ingreso && !FotosRed.VALIDACION.equals(g.tipo)) continue;
                r.totalRegistros++;
                cal.setTimeInMillis(g.hora);
                int h = cal.get(Calendar.HOUR_OF_DAY);
                if (ingreso) r.ingresadasPorHora[h]++; else r.validadasPorHora[h]++;
                if (r.horaMin < 0 || h < r.horaMin) r.horaMin = h;
                if (h > r.horaMax) r.horaMax = h;
                if (r.primerRegistro == 0 || g.hora < r.primerRegistro) r.primerRegistro = g.hora;
                if (g.hora > r.ultimoRegistro) r.ultimoRegistro = g.hora;
                if (ingreso && g.hora <= ahora && ahora - g.hora < 3_600_000L) r.ingresosUltimaHora++;
                String q = g.quien.trim().isEmpty() ? "(sin nombre)" : g.quien.trim();
                Integer n = equipos.get(q);
                equipos.put(q, n == null ? 1 : n + 1);
            }
        }
        ordenar(equipos, r.porEquipo);

        // Por evento
        Map<String, int[]> eventos = new LinkedHashMap<>();
        for (int f = 0; f < r.reservas; f++) {
            String ev = l.valor(f, Listado.Campo.EVENTO).trim();
            if (ev.isEmpty()) continue;
            int[] c = eventos.get(ev);
            if (c == null) {
                c = new int[2];
                eventos.put(ev, c);
            }
            c[0]++;
            if (l.ingresos.containsKey(f)) c[1]++;
        }
        if (eventos.size() > 1) {
            List<Map.Entry<String, int[]>> orden = new ArrayList<>(eventos.entrySet());
            Collections.sort(orden, (a, b) -> b.getValue()[0] != a.getValue()[0] ? Integer.compare(b.getValue()[0], a.getValue()[0]) : a.getKey().compareTo(b.getKey()));
            for (Map.Entry<String, int[]> e : orden) r.porEvento.put(e.getKey(), e.getValue());
        }

        // Alertas por motivo
        if (alertas != null) {
            Map<String, Integer> a = new LinkedHashMap<>();
            for (String m : alertas) {
                if (m == null || m.isEmpty()) continue;
                Integer n = a.get(m);
                a.put(m, n == null ? 1 : n + 1);
                r.totalAlertas++;
            }
            ordenar(a, r.alertas);
        }
        return r;
    }

    /** De mayor a menor; a igual cantidad, por nombre (para que el orden no cambie al recalcular). */
    private static void ordenar(Map<String, Integer> origen, Map<String, Integer> destino) {
        List<Map.Entry<String, Integer>> orden = new ArrayList<>(origen.entrySet());
        Collections.sort(orden, (a, b) -> !a.getValue().equals(b.getValue()) ? Integer.compare(b.getValue(), a.getValue()) : a.getKey().compareTo(b.getKey()));
        for (Map.Entry<String, Integer> e : orden) destino.put(e.getKey(), e.getValue());
    }

    /** «3 min 20 s», «1 h 05 min»… para mostrar una espera. */
    public static String duracion(long ms) {
        if (ms < 0) ms = 0;
        long s = (ms + 500) / 1000;
        if (s < 60) return s + " s";
        long min = s / 60;
        if (min < 60) return min + " min" + (s % 60 != 0 && min < 10 ? " " + (s % 60) + " s" : "");
        long h = min / 60;
        return h + " h " + String.format(java.util.Locale.ROOT, "%02d", min % 60) + " min";
    }

    /** Porcentaje entero de [parte] sobre [total] (0 si el total es 0). */
    public static int porcentaje(int parte, int total) {
        return total <= 0 ? 0 : (int) Math.round(100.0 * parte / total);
    }

    /** Las horas del día que se muestran en la gráfica por hora: desde una antes de la primera con datos hasta una después de la última. */
    public static int[] rangoHoras(Resultado r) {
        if (r.horaMin < 0) return new int[]{8, 20};
        int desde = Math.max(0, r.horaMin - 1);
        int hasta = Math.min(23, r.horaMax + 1);
        while (hasta - desde < 5) {   // que nunca sea una gráfica de dos barras
            if (hasta < 23) hasta++;
            if (hasta - desde < 5 && desde > 0) desde--;
            if (desde == 0 && hasta == 23) break;
        }
        return new int[]{desde, hasta};
    }
}
