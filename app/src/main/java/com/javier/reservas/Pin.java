package com.javier.reservas;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** PIN de configuración: nunca se guarda el número, solo una huella con sal. */
public final class Pin {

    private Pin() {}

    public static final int MIN = 4;
    public static final int MAX = 8;

    public static boolean formatoValido(String pin) {
        if (pin == null || pin.length() < MIN || pin.length() > MAX) return false;
        for (int i = 0; i < pin.length(); i++) if (!Character.isDigit(pin.charAt(i))) return false;
        return true;
    }

    public static String nuevaSal() {
        byte[] b = new byte[12];
        new SecureRandom().nextBytes(b);
        return hex(b);
    }

    public static String huella(String sal, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((sal + ":" + pin).getBytes(StandardCharsets.UTF_8));
            // se repite para hacer más lento adivinar el PIN por fuerza bruta si alguien copia el archivo
            for (int i = 0; i < 2000; i++) h = md.digest(h);
            return hex(h);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean coincide(String sal, String huellaGuardada, String pin) {
        if (sal == null || huellaGuardada == null || pin == null) return false;
        String h = huella(sal, pin);
        // comparación en tiempo constante
        int d = h.length() ^ huellaGuardada.length();
        for (int i = 0; i < Math.min(h.length(), huellaGuardada.length()); i++) d |= h.charAt(i) ^ huellaGuardada.charAt(i);
        return d == 0;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
