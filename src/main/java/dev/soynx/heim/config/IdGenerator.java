package dev.soynx.heim.config;

import java.security.SecureRandom;

/** Short, prefixed random identifiers ({@code svc_k3x9a0qz}). */
public final class IdGenerator {

    public static final String SERVICE = "svc_";
    public static final String LINK = "lnk_";
    public static final String TAB = "tab_";
    public static final String GROUP = "grp_";

    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private IdGenerator() {
    }

    public static String newId(String prefix) {
        StringBuilder sb = new StringBuilder(prefix.length() + LENGTH).append(prefix);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    public static String serviceId() {
        return newId(SERVICE);
    }

    public static String linkId() {
        return newId(LINK);
    }

    public static String tabId() {
        return newId(TAB);
    }

    public static String groupId() {
        return newId(GROUP);
    }
}
