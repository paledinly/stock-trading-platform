package com.sunmo.stockplatform.marketwide.domain;

import java.util.Locale;

public final class StructuredProductFilter {
    private StructuredProductFilter() { }
    public static boolean matches(String name) {
        if (name == null) return false;
        String upper = name.toUpperCase(Locale.ROOT);
        return java.util.List.of("인버스", "레버리지", "선물", "ETN", "ETF", "2X").stream()
                .anyMatch(upper::contains);
    }
}
