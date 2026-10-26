package vn.nutrimom.analytics.domain;

public enum TrafficSource {
    TIKTOK("TikTok"),
    FACEBOOK("Facebook"),
    INSTAGRAM("Instagram"),
    GOOGLE("Google Search / Ads"),
    DIRECT("Trực tiếp"),
    OTHER("Nguồn khác");

    private final String displayName;

    TrafficSource(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static TrafficSource fromString(String raw) {
        if (raw == null || raw.isBlank()) return DIRECT;
        String normalized = raw.trim().toUpperCase();
        for (TrafficSource s : values()) {
            if (s.name().equals(normalized)) return s;
        }
        if (normalized.contains("TIKTOK")) return TIKTOK;
        if (normalized.contains("FACEBOOK") || normalized.contains("FB")) return FACEBOOK;
        if (normalized.contains("INSTAGRAM") || normalized.contains("INSTA")) return INSTAGRAM;
        if (normalized.contains("GOOGLE")) return GOOGLE;
        return OTHER;
    }
}
