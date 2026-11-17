package dev.mythicwardrobe.model;

import java.util.Locale;

public enum SkinModelVariant {
    CLASSIC("classic", "wide"),
    SLIM("slim", "slim");

    private final String apiName;
    private final String bodyTypeName;

    SkinModelVariant(String apiName, String bodyTypeName) {
        this.apiName = apiName;
        this.bodyTypeName = bodyTypeName;
    }

    /** Name expected by MineSkin API ("classic" / "slim"). */
    public String getApiName() {
        return apiName;
    }

    /** User-facing bodytype name ("wide" / "slim"). */
    public String getBodyTypeName() {
        return bodyTypeName;
    }

    public static SkinModelVariant parseOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "slim", "alex", "thin", "narrow", "3px", "узкий", "узкие" -> SLIM;
            case "wide", "classic", "steve", "default", "normal", "4px", "широкий", "широкие" -> CLASSIC;
            default -> null;
        };
    }

    public static SkinModelVariant fromString(String value, SkinModelVariant fallback) {
        SkinModelVariant parsed = parseOrNull(value);
        return parsed != null ? parsed : fallback;
    }
}
