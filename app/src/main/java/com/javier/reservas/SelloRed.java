package com.javier.reservas;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cifra y autentica lo que viaja entre los equipos (AES-256-GCM) con una clave que sale de la «clave de la red»
 * escrita en Ajustes. Un equipo que no tenga la misma clave no puede leer ni inventar mensajes: los descarta.
 * Salida en hexadecimal (solo caracteres ASCII, sin saltos de línea). Sin nada de Android.
 */
public final class SelloRed {

    private static final byte[] SAL = "ReservasQR/red-entre-equipos/v1".getBytes(StandardCharsets.UTF_8);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey llave;
    private final SecureRandom azar = new SecureRandom();

    public SelloRed(String clave) {
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            byte[] k = f.generateSecret(new PBEKeySpec(clave.toCharArray(), SAL, 4_000, 256)).getEncoded();
            llave = new SecretKeySpec(k, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo preparar la clave de la red", e);
        }
    }

    /** @return el texto cifrado y firmado, en hexadecimal */
    public String sellar(String texto) {
        try {
            byte[] iv = new byte[IV_BYTES];
            azar.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, llave, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cifrado = c.doFinal(texto.getBytes(StandardCharsets.UTF_8));
            return hex(iv) + hex(cifrado);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return el texto original, o null si no es de esta red (otra clave, alterado o ilegible) */
    public String abrir(String sellado) {
        if (sellado == null) return null;
        String s = sellado.trim();
        if (s.length() < (IV_BYTES + 16) * 2 || (s.length() & 1) != 0) return null;
        try {
            byte[] todo = deHex(s);
            if (todo == null) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, llave, new GCMParameterSpec(TAG_BITS, todo, 0, IV_BYTES));
            byte[] claro = c.doFinal(todo, IV_BYTES, todo.length - IV_BYTES);
            return new String(claro, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static String hex(byte[] b) {
        char[] o = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            o[i * 2] = HEX[(b[i] >> 4) & 15];
            o[i * 2 + 1] = HEX[b[i] & 15];
        }
        return new String(o);
    }

    private static byte[] deHex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int h = Character.digit(s.charAt(i * 2), 16);
            int l = Character.digit(s.charAt(i * 2 + 1), 16);
            if (h < 0 || l < 0) return null;
            b[i] = (byte) ((h << 4) | l);
        }
        return b;
    }
}
