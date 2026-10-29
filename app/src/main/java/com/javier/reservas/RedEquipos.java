package com.javier.reservas;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Comunicación entre los equipos Sunmi que están cerca, por la misma red Wi-Fi (un router o la zona Wi-Fi de un equipo).
 * No necesita internet ni emparejar nada y funciona en segundo plano:
 *
 *  1. DESCUBRIMIENTO: cada equipo avisa cada pocos segundos «aquí estoy» por UDP (a toda la red y a las IP que se
 *     escriban a mano en Ajustes). Así los equipos se encuentran solos.
 *  2. CONEXIÓN: entre cada par de equipos queda una conexión TCP abierta (la inicia el de id menor). Si se cae, se reconecta.
 *  3. EVENTOS: cada validación / ingreso / anulación se envía al instante a todos. Los equipos que lo reciben lo
 *     reenvían a los demás, de modo que llega también a quien no está al alcance directo del que lo hizo.
 *  4. RESPALDO: cada 30 s (y al conectar) los equipos comparan un resumen de lo que saben; si algo no coincide
 *     se envían lo que falta. Así un equipo que estuvo apagado o sin señal se pone al día solo.
 *  5. SEGURIDAD: todo va cifrado y firmado con la «clave de la red» ({@link SelloRed}); lo que no la tenga se ignora.
 *
 * Sin nada de Android: se prueba en el computador con conexiones reales.
 */
public final class RedEquipos {

    public static final String VERSION = "1";
    public static final int PUERTO_UDP = 45870;
    public static final int PUERTO_TCP = 45871;
    /** Cuánto tiempo se conservan los eventos. */
    public static final long RETENCION_MS = 48L * 3_600_000L;
    private static final int MAX_LINEA = 4 * 1024 * 1024;

    public interface Oyente {
        /** Llegó un evento nuevo de otro equipo (se llama en un hilo de la red, no en el principal). */
        void alEventoRemoto(EventoRed e);

        /** Cambió la lista de equipos encontrados o conectados (hilo de la red). */
        void alCambiarEquipos();
    }

    /**
     * Permite a la app atar los sockets que SALEN a la red Wi-Fi (en Android, si el Wi-Fi no tiene internet y el 4G está
     * encendido, el sistema manda todo por el 4G y los equipos no se alcanzarían). Si no se da, se usa la ruta normal.
     */
    public interface Atador {
        void atar(Socket s) throws IOException;

        void atar(DatagramSocket s) throws IOException;
    }

    public static final class Config {
        public Atador atador = null;
        public String idEquipo = "";
        public String nombre = "";
        public String clave = "";
        public int puertoUdp = PUERTO_UDP;
        public int puertoTcp = PUERTO_TCP;
        public long cadaBeaconMs = 4_000;
        public long cadaPingMs = 10_000;
        public long cadaResumenMs = 30_000;
        public long sinNoticiasMs = 40_000;
        public long esperaIniciadorMs = 12_000;
        /** A dónde mandar el «aquí estoy»: normalmente {@link #destinosDifusion}. */
        public Supplier<List<InetSocketAddress>> destinosUdp = new Supplier<List<InetSocketAddress>>() {
            @Override
            public List<InetSocketAddress> get() {
                return Collections.emptyList();
            }
        };
    }

    /** Lo que ve la pantalla de cada equipo cercano. */
    public static final class Equipo {
        public final String id;
        public final String nombre;
        public final String ip;
        public final boolean conectado;
        public final long ultimaVez;
        public final int eventosRecibidos;

        Equipo(String id, String nombre, String ip, boolean conectado, long ultimaVez, int eventosRecibidos) {
            this.id = id;
            this.nombre = nombre;
            this.ip = ip;
            this.conectado = conectado;
            this.ultimaVez = ultimaVez;
            this.eventosRecibidos = eventosRecibidos;
        }
    }

    private static final class Par {
        final String id;
        volatile String nombre = "";
        volatile String ip = "";
        volatile int puertoTcp = PUERTO_TCP;
        volatile long ultimaVez = 0;
        final long primeraVez = System.currentTimeMillis();
        volatile Conexion conexion;
        volatile boolean conectando = false;
        volatile int fallos = 0;
        volatile long proximoIntento = 0;
        final AtomicInteger recibidos = new AtomicInteger();

        Par(String id) {
            this.id = id;
        }
    }

    private final Config cfg;
    private final RegistroEventos registro;
    private final Oyente oyente;
    private final SelloRed sello;
    private final Object lockSeq = new Object();
    private final Map<String, Par> pares = new ConcurrentHashMap<>();
    private final Set<Conexion> conexiones = Collections.newSetFromMap(new ConcurrentHashMap<Conexion, Boolean>());

    private volatile boolean activo = false;
    private ServerSocket servidor;
    private DatagramSocket udp;
    private ExecutorService pool;

    public RedEquipos(Config cfg, RegistroEventos registro, Oyente oyente) {
        this.cfg = cfg;
        this.registro = registro;
        this.oyente = oyente;
        this.sello = new SelloRed(cfg.clave);
    }

    public boolean activa() {
        return activo;
    }

    // ------------------------------------------------------------------------------------------ arranque y parada

    /** Abre los puertos y empieza a buscar equipos. @throws IOException si los puertos están ocupados */
    public synchronized void iniciar() throws IOException {
        if (activo) return;
        ServerSocket s = new ServerSocket();
        DatagramSocket u = null;
        try {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(cfg.puertoTcp));
            u = new DatagramSocket(null);
            u.setReuseAddress(true);
            u.setBroadcast(true);
            u.setSoTimeout(1_000);
            u.bind(new InetSocketAddress(cfg.puertoUdp));
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignorada) { }
            if (u != null) u.close();
            throw e;
        }
        servidor = s;
        udp = u;
        pool = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "red-equipos");
                t.setDaemon(true);
                return t;
            }
        });
        activo = true;
        pool.execute(new Runnable() { @Override public void run() { cicloUdp(); } });
        pool.execute(new Runnable() { @Override public void run() { cicloAceptar(); } });
        pool.execute(new Runnable() { @Override public void run() { cicloMantener(); } });
    }

    public synchronized void detener() {
        if (!activo) return;
        activo = false;
        try { if (servidor != null) servidor.close(); } catch (IOException ignorada) { }
        if (udp != null) udp.close();
        for (Conexion c : new ArrayList<>(conexiones)) c.cerrar();
        conexiones.clear();
        pares.clear();
        if (pool != null) pool.shutdownNow();
        oyente.alCambiarEquipos();
    }

    // ------------------------------------------------------------------------------------------ lo que ve la pantalla

    public List<Equipo> equipos() {
        List<Equipo> r = new ArrayList<>();
        for (Par p : pares.values()) {
            Conexion c = p.conexion;
            boolean ok = c != null && !c.cerrada && c.saludado;
            r.add(new Equipo(p.id, p.nombre, p.ip, ok, p.ultimaVez, p.recibidos.get()));
        }
        Collections.sort(r, new java.util.Comparator<Equipo>() {
            @Override
            public int compare(Equipo a, Equipo b) {
                return a.nombre.compareToIgnoreCase(b.nombre);
            }
        });
        return r;
    }

    public int conectados() {
        int n = 0;
        for (Par p : pares.values()) {
            Conexion c = p.conexion;
            if (c != null && !c.cerrada && c.saludado) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------ publicar

    /** Anota un evento de este equipo y lo manda al instante a todos los conectados. */
    public EventoRed publicar(String tipo, String codigo, String placa, long hora, String operador) {
        EventoRed e;
        synchronized (lockSeq) {
            long seq = Math.max(registro.ultimaSeq(cfg.idEquipo) + 1, System.currentTimeMillis() / 1000L);
            e = new EventoRed(cfg.idEquipo, seq, tipo, EventoRed.jornadaDe(hora), codigo, placa, hora, cfg.nombre, operador);
            registro.agregar(e);
        }
        enviarATodos("E\t" + e.codificar(), null);
        return e;
    }

    /** Pide a todos los conectados que comparen lo que saben, ahora mismo. */
    public void sincronizarAhora() {
        for (Conexion c : conexiones) if (c.saludado) c.enviarResumen();
    }

    private void enviarATodos(String plano, Conexion salvo) {
        for (Conexion c : conexiones) {
            if (c != salvo && c.saludado && !c.cerrada) c.enviar(plano);
        }
    }

    // ------------------------------------------------------------------------------------------ descubrimiento (UDP)

    private void cicloUdp() {
        byte[] buf = new byte[4096];
        while (activo) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                udp.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                if (!activo) break;
                dormir(200);
                continue;
            }
            try {
                String plano = sello.abrir(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                if (plano == null) continue;
                String[] x = plano.split("\t", -1);
                if (x.length >= 5 && "B".equals(x[0]) && VERSION.equals(x[1])) {
                    alAviso(x[2], EventoRed.desescapar(x[3]), parsearPuerto(x[4]), p.getAddress().getHostAddress());
                }
            } catch (RuntimeException ignorada) {
            }
        }
    }

    private static int parsearPuerto(String s) {
        try {
            int n = Integer.parseInt(s);
            return n > 0 && n < 65536 ? n : PUERTO_TCP;
        } catch (NumberFormatException e) {
            return PUERTO_TCP;
        }
    }

    private void alAviso(String id, String nombre, int puertoTcp, String ip) {
        if (id.isEmpty() || id.equals(cfg.idEquipo)) return;
        Par par = pares.get(id);
        boolean nuevo = false;
        if (par == null) {
            par = new Par(id);
            Par previo = pares.putIfAbsent(id, par);
            if (previo != null) par = previo; else nuevo = true;
        }
        boolean cambio = nuevo || !nombre.equals(par.nombre) || !ip.equals(par.ip);
        par.nombre = nombre;
        par.ip = ip;
        par.puertoTcp = puertoTcp;
        par.ultimaVez = System.currentTimeMillis();
        if (cambio) oyente.alCambiarEquipos();
    }

    private void enviarAvisos() {
        byte[] datos = sello.sellar("B\t" + VERSION + "\t" + cfg.idEquipo + "\t" + EventoRed.escapar(cfg.nombre) + "\t" + cfg.puertoTcp)
                .getBytes(StandardCharsets.UTF_8);
        List<InetSocketAddress> destinos;
        try {
            destinos = cfg.destinosUdp.get();
        } catch (RuntimeException e) {
            return;
        }
        DatagramSocket propio = null;
        try {
            if (cfg.atador != null) {
                try {
                    propio = new DatagramSocket();
                    propio.setBroadcast(true);
                    cfg.atador.atar(propio);
                } catch (IOException | RuntimeException e) {
                    if (propio != null) propio.close();
                    propio = null;
                }
            }
            DatagramSocket salida = propio != null ? propio : udp;
            for (InetSocketAddress d : destinos) {
                try {
                    salida.send(new DatagramPacket(datos, datos.length, d));
                } catch (IOException | RuntimeException ignorada) {
                    // sin red hacia ese destino: se intenta de nuevo en el próximo aviso
                }
            }
        } finally {
            if (propio != null) propio.close();
        }
    }

    // ------------------------------------------------------------------------------------------ conexiones (TCP)

    private void cicloAceptar() {
        while (activo) {
            try {
                Socket s = servidor.accept();
                nuevaConexion(s, false, null);
            } catch (IOException e) {
                if (!activo) break;
                dormir(200);
            }
        }
    }

    private Conexion nuevaConexion(Socket s, boolean iniciadaPorMi, Par par) {
        try {
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.setSoTimeout(5_000);
        } catch (IOException ignorada) {
        }
        Conexion c = new Conexion(s, iniciadaPorMi);
        if (par != null) c.idPar = par.id;
        conexiones.add(c);
        c.iniciar();
        return c;
    }

    private void cicloMantener() {
        long ultAviso = 0, ultPing = System.currentTimeMillis(), ultResumen = System.currentTimeMillis();
        long paso = Math.max(50, Math.min(500, cfg.cadaBeaconMs / 2));
        while (activo) {
            long ahora = System.currentTimeMillis();
            try {
                if (ahora - ultAviso >= cfg.cadaBeaconMs) {
                    enviarAvisos();
                    ultAviso = ahora;
                }
                conectarFaltantes(ahora);
                if (ahora - ultPing >= cfg.cadaPingMs) {
                    enviarATodos("P", null);
                    ultPing = ahora;
                }
                if (ahora - ultResumen >= cfg.cadaResumenMs) {
                    for (Conexion c : conexiones) if (c.saludado) c.enviarResumen();
                    ultResumen = ahora;
                }
                for (Conexion c : conexiones) {
                    if (ahora - c.ultimoRecibido > cfg.sinNoticiasMs) c.cerrar();
                }
                for (Par p : pares.values()) {
                    Conexion c = p.conexion;
                    boolean viva = c != null && !c.cerrada;
                    if (!viva && ahora - p.ultimaVez > 5 * 60_000L) {
                        pares.remove(p.id);
                        oyente.alCambiarEquipos();
                    }
                }
            } catch (RuntimeException ignorada) {
            }
            dormir(paso);
        }
    }

    private void conectarFaltantes(long ahora) {
        for (final Par par : pares.values()) {
            Conexion c = par.conexion;
            if (c != null && !c.cerrada) continue;
            if (par.conectando) continue;
            if (ahora - par.ultimaVez > 3 * cfg.cadaBeaconMs + 2_000) continue;
            boolean meToca = cfg.idEquipo.compareTo(par.id) < 0 || ahora - par.primeraVez > cfg.esperaIniciadorMs;
            if (!meToca || ahora < par.proximoIntento) continue;
            par.conectando = true;
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    conectar(par);
                }
            });
        }
    }

    private void conectar(Par par) {
        Socket s = new Socket();
        try {
            if (cfg.atador != null) cfg.atador.atar(s);
            s.connect(new InetSocketAddress(par.ip, par.puertoTcp), 3_000);
            par.fallos = 0;
            Conexion c = nuevaConexion(s, true, par);
            synchronized (par) {
                Conexion vieja = par.conexion;
                if (vieja == null || vieja.cerrada) par.conexion = c;
            }
        } catch (IOException | RuntimeException e) {
            par.fallos++;
            par.proximoIntento = System.currentTimeMillis() + Math.min(30_000L, 1_000L << Math.min(par.fallos, 5));
            try { s.close(); } catch (IOException ignorada) { }
        } finally {
            par.conectando = false;
        }
    }

    /** Dos conexiones con el mismo equipo: se queda la que inició el equipo de id menor (los dos eligen igual). */
    private boolean resolver(Par par, Conexion nueva) {
        synchronized (par) {
            Conexion vieja = par.conexion;
            if (vieja == null || vieja.cerrada || vieja == nueva) {
                par.conexion = nueva;
                return true;
            }
            String iniciadorNueva = nueva.iniciadaPorMi ? cfg.idEquipo : par.id;
            String iniciadorVieja = vieja.iniciadaPorMi ? cfg.idEquipo : par.id;
            if (iniciadorNueva.compareTo(iniciadorVieja) > 0) return false;
            par.conexion = nueva;
            vieja.cerrar();
            return true;
        }
    }

    private final class Conexion {
        final Socket socket;
        final boolean iniciadaPorMi;
        final long creada = System.currentTimeMillis();
        final LinkedBlockingQueue<String> salida = new LinkedBlockingQueue<>();
        volatile String idPar;
        volatile boolean saludado = false;
        volatile boolean cerrada = false;
        volatile long ultimoRecibido = System.currentTimeMillis();
        private final String fin = new String("");

        Conexion(Socket socket, boolean iniciadaPorMi) {
            this.socket = socket;
            this.iniciadaPorMi = iniciadaPorMi;
        }

        void iniciar() {
            enviar("H\t" + VERSION + "\t" + cfg.idEquipo + "\t" + EventoRed.escapar(cfg.nombre) + "\t" + cfg.puertoTcp);
            pool.execute(new Runnable() { @Override public void run() { escribir(); } });
            pool.execute(new Runnable() { @Override public void run() { leer(); } });
        }

        void enviar(String plano) {
            if (cerrada) return;
            if (salida.size() > 50_000) {
                cerrar();
                return;
            }
            salida.offer(sello.sellar(plano));
        }

        void enviarResumen() {
            StringBuilder b = new StringBuilder("R");
            for (Map.Entry<String, long[]> en : registro.resumen(System.currentTimeMillis() - RETENCION_MS).entrySet()) {
                b.append('\t').append(EventoRed.escapar(en.getKey())).append(':').append(en.getValue()[0]).append(':').append(en.getValue()[1]);
            }
            enviar(b.toString());
        }

        void cerrar() {
            if (cerrada) return;
            cerrada = true;
            conexiones.remove(this);
            try { socket.close(); } catch (IOException ignorada) { }
            salida.offer(fin);
            String id = idPar;
            if (id != null) {
                Par par = pares.get(id);
                if (par != null) {
                    synchronized (par) {
                        if (par.conexion == this) {
                            par.conexion = null;
                            par.proximoIntento = System.currentTimeMillis() + 1_000;
                        }
                    }
                }
            }
            if (activo) oyente.alCambiarEquipos();
        }

        private void escribir() {
            try (BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024)) {
                while (!cerrada) {
                    String l = salida.poll(1, TimeUnit.SECONDS);
                    if (l == null) continue;
                    if (l == fin) break;
                    out.write(l.getBytes(StandardCharsets.UTF_8));
                    out.write('\n');
                    if (salida.isEmpty()) out.flush();
                }
            } catch (IOException | InterruptedException ignorada) {
            } finally {
                cerrar();
            }
        }

        private void leer() {
            try {
                InputStream in = socket.getInputStream();
                byte[] b = new byte[8192];
                int pos = 0, lim = 0;
                ByteArrayOutputStream parcial = new ByteArrayOutputStream();
                while (!cerrada && activo) {
                    if (pos >= lim) {
                        int n;
                        try {
                            n = in.read(b, 0, b.length);
                        } catch (SocketTimeoutException e) {
                            long ahora = System.currentTimeMillis();
                            if (!saludado && ahora - creada > 8_000) break;
                            if (ahora - ultimoRecibido > cfg.sinNoticiasMs) break;
                            continue;
                        }
                        if (n < 0) break;
                        pos = 0;
                        lim = n;
                    }
                    while (pos < lim) {
                        byte x = b[pos++];
                        if (x == '\n') {
                            String linea = new String(parcial.toByteArray(), StandardCharsets.UTF_8);
                            parcial.reset();
                            if (!procesarLinea(linea)) return;
                        } else {
                            if (parcial.size() >= MAX_LINEA) return;
                            parcial.write(x);
                        }
                    }
                }
            } catch (IOException | RuntimeException ignorada) {
            } finally {
                cerrar();
            }
        }

        /** @return false si hay que cortar la conexión */
        private boolean procesarLinea(String linea) {
            String plano = sello.abrir(linea);
            if (plano == null) return saludado; // de otra red o alterado: al que ni se presentó se le corta
            ultimoRecibido = System.currentTimeMillis();
            String[] x = plano.split("\t", -1);
            switch (x[0]) {
                case "H": return alHola(x);
                case "R": if (saludado) alResumen(x); return saludado;
                case "L": if (saludado) alLista(x); return saludado;
                case "E": if (saludado) alEvento(x); return saludado;
                default: return true; // «P» (sigo aquí) y lo desconocido
            }
        }

        private boolean alHola(String[] x) {
            if (x.length < 5 || !VERSION.equals(x[1])) return false;
            String id = x[2];
            if (id.isEmpty() || id.equals(cfg.idEquipo)) return false;
            Par par = pares.get(id);
            if (par == null) {
                par = new Par(id);
                Par previo = pares.putIfAbsent(id, par);
                if (previo != null) par = previo;
            }
            par.nombre = EventoRed.desescapar(x[3]);
            par.ultimaVez = System.currentTimeMillis();
            String ip = socket.getInetAddress() == null ? "" : socket.getInetAddress().getHostAddress();
            if (!ip.isEmpty()) par.ip = ip;
            if (!iniciadaPorMi) par.puertoTcp = parsearPuerto(x[4]);
            idPar = id;
            if (!resolver(par, this)) return false;
            saludado = true;
            oyente.alCambiarEquipos();
            enviarResumen();
            return true;
        }

        private void alResumen(String[] x) {
            Map<String, long[]> remoto = new HashMap<>();
            for (int i = 1; i < x.length; i++) {
                String t = x[i];
                int b = t.lastIndexOf(':');
                int a = b > 0 ? t.lastIndexOf(':', b - 1) : -1;
                if (a <= 0) continue;
                try {
                    remoto.put(EventoRed.desescapar(t.substring(0, a)),
                            new long[]{Long.parseLong(t.substring(a + 1, b)), Long.parseLong(t.substring(b + 1))});
                } catch (NumberFormatException ignorada) {
                }
            }
            long desde = System.currentTimeMillis() - RETENCION_MS;
            Map<String, long[]> local = registro.resumen(desde);
            Set<String> origenes = new HashSet<>(local.keySet());
            origenes.addAll(remoto.keySet());
            for (String o : origenes) {
                long[] l = local.get(o), r = remoto.get(o);
                boolean igual = l != null && r != null && l[0] == r[0] && l[1] == r[1];
                if (!igual) enviar("L\t" + EventoRed.escapar(o) + "\t" + RangosSeq.codificar(registro.seqsDe(o, desde)));
            }
        }

        private void alLista(String[] x) {
            if (x.length < 3) return;
            TreeSet<Long> tiene = RangosSeq.decodificar(x[2]);
            if (tiene == null) return;
            long desde = System.currentTimeMillis() - RETENCION_MS;
            for (EventoRed e : registro.faltantes(EventoRed.desescapar(x[1]), desde, tiene)) {
                enviar("E\t" + e.codificar());
            }
        }

        private void alEvento(String[] x) {
            EventoRed e = EventoRed.decodificar(x, 1);
            if (e == null || e.hora < System.currentTimeMillis() - RETENCION_MS) return;
            if (!registro.agregar(e)) return;
            Par par = idPar == null ? null : pares.get(idPar);
            if (par != null) par.recibidos.incrementAndGet();
            enviarATodos("E\t" + e.codificar(), this);
            try {
                oyente.alEventoRemoto(e);
            } catch (RuntimeException ignorada) {
            }
        }
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------------------------------ direcciones de difusión

    /**
     * Direcciones a las que se manda el «aquí estoy»: la difusión de cada red Wi-Fi/cable activa del equipo
     * (incluida la zona Wi-Fi si este equipo la ofrece), la difusión general y las IP escritas a mano.
     *
     * @param manuales IP de otros equipos separadas por coma o espacio (puede estar vacío)
     */
    public static List<InetSocketAddress> destinosDifusion(int puertoUdp, String manuales) {
        Set<String> vistos = new HashSet<>();
        List<InetSocketAddress> r = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b instanceof Inet4Address && vistos.add(b.getHostAddress())) r.add(new InetSocketAddress(b, puertoUdp));
                }
            }
        } catch (IOException | RuntimeException ignorada) {
        }
        try {
            if (vistos.add("255.255.255.255")) r.add(new InetSocketAddress(InetAddress.getByName("255.255.255.255"), puertoUdp));
        } catch (IOException ignorada) {
        }
        if (manuales != null) {
            for (String ip : manuales.split("[,;\\s]+")) {
                String t = ip.trim();
                if (t.isEmpty() || !t.matches("(\\d{1,3}\\.){3}\\d{1,3}") || !vistos.add(t)) continue;   // solo IP (un nombre obligaría a consultar DNS)
                InetSocketAddress d = new InetSocketAddress(t, puertoUdp);
                if (!d.isUnresolved()) r.add(d);
            }
        }
        return r;
    }
}
