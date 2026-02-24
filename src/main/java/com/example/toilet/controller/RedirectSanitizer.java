package com.example.toilet.controller;

import java.util.Collection;

public final class RedirectSanitizer {

    private RedirectSanitizer() {
    }

    public static String toSafePath(String raw, String defaultPath) {
        return toSafePath(raw, defaultPath, null);
    }

    public static String toSafePath(String raw, String defaultPath, Collection<String> allowPrefixes) {
        String fallback = normalizeDefault(defaultPath);
        if (raw == null) {
            return fallback;
        }
        String candidate = raw.trim();
        if (candidate.isEmpty()) {
            return fallback;
        }
        if (!candidate.startsWith("/") || candidate.startsWith("//")) {
            return fallback;
        }
        if (candidate.contains("\r") || candidate.contains("\n")) {
            return fallback;
        }
        if (!isAllowedPrefix(candidate, allowPrefixes)) {
            return fallback;
        }
        return candidate;
    }

    private static String normalizeDefault(String defaultPath) {
        if (defaultPath == null || defaultPath.isBlank()) {
            return "/";
        }
        String normalized = defaultPath.trim();
        if (!normalized.startsWith("/") || normalized.startsWith("//")) {
            return "/";
        }
        return normalized;
    }

    private static boolean isAllowedPrefix(String candidate, Collection<String> allowPrefixes) {
        if (allowPrefixes == null || allowPrefixes.isEmpty()) {
            return true;
        }
        for (String prefix : allowPrefixes) {
            if (prefix == null || prefix.isBlank()) {
                continue;
            }
            if (candidate.equals(prefix) || candidate.startsWith(prefix + "/") || candidate.startsWith(prefix + "?")) {
                return true;
            }
        }
        return false;
    }
}
