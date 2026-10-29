package com.javier.reservas;

/**
 * Recibe lo que manda el lector de códigos integrado (Sunmi) y evita que una misma lectura se procese dos veces
 * o a medias. El lector puede entregar el código por dos caminos a la vez:
 *  - como «teclado»: letra por letra, y al final Enter (se junta aquí), y
 *  - como aviso del sistema (broadcast) con el texto completo y exacto.
 * Reglas:
 *  1. El aviso del sistema manda: trae el texto exacto. La copia por teclado que llega junto con él se descarta
 *     (las teclas de esa copia no deben llegar al campo de búsqueda ni a los botones de una ventana).
 *  2. Lo escrito por teclado se junta hasta el Enter. Un campo de texto solo se considera «lector» si las teclas
 *     llegan casi pegadas (una persona no teclea tan rápido); lo que alcanzó a entrar al campo se le quita.
 *  3. La misma lectura (mismo texto) no se procesa dos veces seguidas.
 * No usa nada de Android para poder probarse aparte. Los tiempos son milisegundos en un mismo reloj.
 */
public final class CapturaLector {

    /** Entre una tecla y otra del lector pasan pocos milisegundos; una persona tarda más que esto. */
    public static final long GAP_RAFAGA_MS = 70;
    /** Si pasa más que esto entre dos teclas, lo anterior era otra cosa y se empieza de cero. */
    public static final long GAP_REINICIO_MS = 1_000;
    /** Tras un aviso del sistema, las teclas que lleguen son la copia de esa misma lectura. */
    public static final long IGNORAR_TRAS_AVISO_MS = 1_500;
    /** Mientras las teclas de la copia sigan llegando seguidas, se sigue ignorando. */
    public static final long COLA_RAFAGA_MS = 500;
    /** La misma lectura repetida antes de este tiempo se ignora. */
    public static final long DUPLICADO_MS = 1_500;
    /** Cuánto esperar una lectura completa por teclado por si llega el aviso exacto del sistema. */
    public static final long ESPERA_TECLADO_MS = 200;
    /** Una lectura por teclado necesita al menos estas letras. */
    public static final int MIN_LARGO = 3;
    /** Con tantas pausas muy cortas seguidas (4 teclas casi pegadas), lo que entra a un campo de texto es del lector. */
    private static final int RAPIDAS_PARA_CONFIRMAR = 3;
    /** Teclas «recientes» para saber si hay una lectura por teclado en curso. */
    private static final long RECIENTE_MS = 150;

    /** Qué hacer con una letra o número que llegó por teclado. */
    public static final int PASAR = 0;               // dejarla seguir su camino (todavía parece escritura a mano)
    public static final int ABSORBER = 1;            // es del lector: no debe llegar a ningún campo ni botón
    public static final int ABSORBER_Y_LIMPIAR = 2;  // es del lector y lo que ya entró al campo hay que quitarlo

    /** Qué hacer con Enter / Tab. */
    public static final class Fin {
        /** true: la tecla es del lector y no debe llegar al campo ni a los botones. */
        public final boolean absorber;
        /** Lectura completa por teclado (null si esta tecla no termina una lectura). */
        public final String texto;

        Fin(boolean absorber, String texto) {
            this.absorber = absorber;
            this.texto = texto;
        }
    }

    private static final Fin NADA = new Fin(false, null);
    private static final Fin ABSORBE = new Fin(true, null);
    private static final long NUNCA = Long.MIN_VALUE / 4;

    private final StringBuilder buf = new StringBuilder();
    private long ultimaTecla = NUNCA;
    private int rapidas = 0;
    private boolean confirmada = false;
    private long ignorarHasta = NUNCA;
    private long ultimaTeclaIgnorada = NUNCA;
    private String ultimaLectura = "";
    private long ultimaLecturaT = NUNCA;

    /** Cantidad de teclas juntadas hasta ahora. */
    public int largo() {
        return buf.length();
    }

    /** true si hay una lectura por teclado llegando en este momento. */
    public boolean hayTeclasRecientes(long t) {
        return buf.length() > 0 && t - ultimaTecla <= RECIENTE_MS;
    }

    private void reiniciar() {
        buf.setLength(0);
        rapidas = 0;
        confirmada = false;
    }

    /**
     * Una letra o número llegó por teclado (al presionar).
     *
     * @param cp      el carácter (punto de código Unicode)
     * @param t       hora del evento
     * @param enCampo true si hay un campo de texto recibiendo teclas; false si no hay (pantalla, ventana, botón)
     */
    public int caracter(int cp, long t, boolean enCampo) {
        if (t < ignorarHasta) {
            if (t - ultimaTeclaIgnorada <= GAP_RAFAGA_MS) ignorarHasta = Math.max(ignorarHasta, t + COLA_RAFAGA_MS);
            ultimaTeclaIgnorada = t;
            return ABSORBER;
        }
        if (t - ultimaTecla > GAP_REINICIO_MS) reiniciar();
        boolean rapida = buf.length() > 0 && t - ultimaTecla <= GAP_RAFAGA_MS;
        ultimaTecla = t;
        buf.appendCodePoint(cp);
        rapidas = rapida ? rapidas + 1 : 0;
        if (!enCampo || confirmada) return ABSORBER;
        if (rapidas >= RAPIDAS_PARA_CONFIRMAR) {
            confirmada = true;
            return ABSORBER_Y_LIMPIAR;
        }
        return PASAR;
    }

    /** La letra o número se soltó. @return true si hay que absorber también este evento. */
    public boolean absorberSoltar(long t, boolean enCampo) {
        if (t < ignorarHasta) return true;
        return buf.length() > 0 && (!enCampo || confirmada);
    }

    /** Enter (o Tab) presionado o soltado. Solo el soltar entrega el texto. */
    public Fin terminador(boolean presionado, long t, boolean enCampo) {
        if (t < ignorarHasta) {
            if (!presionado) {
                reiniciar();
                ignorarHasta = Math.min(ignorarHasta, t + 100);
            }
            return ABSORBE;
        }
        if (buf.length() == 0) return NADA;
        if (!enCampo || confirmada) {
            if (presionado) return ABSORBE;
            String texto = buf.length() >= MIN_LARGO ? buf.toString() : null;
            reiniciar();
            return texto == null ? ABSORBE : new Fin(true, texto);
        }
        // Lo escribió una persona en el campo: Enter sigue su camino normal (buscar)
        if (!presionado) reiniciar();
        return NADA;
    }

    /**
     * El sistema avisó una lectura completa y exacta. Todo lo que venga por teclado ahora es copia de ella.
     *
     * @return true si es una lectura nueva que hay que procesar; false si es la misma de hace un instante
     */
    public boolean alAviso(String texto, long t) {
        reiniciar();
        ignorarHasta = t + IGNORAR_TRAS_AVISO_MS;
        ultimaTeclaIgnorada = NUNCA;
        return aceptar(texto, t);
    }

    /** Anota que se va a procesar esta lectura. @return false si está vacía o es repetida. */
    public boolean aceptar(String texto, long t) {
        String x = texto == null ? "" : texto.trim();
        if (x.isEmpty()) return false;
        if (x.equals(ultimaLectura) && t - ultimaLecturaT < DUPLICADO_MS) return false;
        ultimaLectura = x;
        ultimaLecturaT = t;
        return true;
    }
}
