package vn.nutrimom.assistant.service;

import java.text.Normalizer;
import java.util.*;

public final class AssistantText {
    private AssistantText() { }
    public static String fold(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
    }
    public static String clean(String text, int limit) {
        if (text == null) return "";
        String safe = text.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("<[^>]+>", " ").replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "")
                .replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "[email đã ẩn]")
                .replaceAll("(?<!\\d)(?:\\+84|0)[\\d .-]{8,14}\\d(?!\\d)", "[số điện thoại đã ẩn]")
                .replaceAll("(?i)(?:Bearer\\s+)?eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", "[token đã ẩn]")
                .replaceAll("[ \\t]+", " ").trim();
        return safe.length() <= limit ? safe : safe.substring(0, limit) + "…";
    }
    public static boolean contains(String text, String... words) {
        String folded = fold(text);
        return Arrays.stream(words).anyMatch(folded::contains);
    }
}
