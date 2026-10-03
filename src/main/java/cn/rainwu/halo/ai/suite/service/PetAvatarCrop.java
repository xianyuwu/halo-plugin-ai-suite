package cn.rainwu.halo.ai.suite.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** Square crop in normalized mother-image coordinates; no extra attachment is created. */
public record PetAvatarCrop(double centerX, double centerY, double size) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static PetAvatarCrop resolve(String raw, String key, PetStore.ExpressionRegion region) {
        double x = region == null ? 0.5 : region.getCenterX();
        double y = region == null ? 0.34 : region.getCenterY();
        double size = region == null ? 0.52 : Math.max(region.getWidth(), region.getHeight()) * 1.3;
        if ("preset:cream-cat".equals(key)) { y = 0.36; size = 0.64; }
        if ("preset:mint-robot".equals(key)) { y = 0.35; size = 0.56; }
        if ("preset:star-sprite".equals(key)) { y = 0.38; size = 0.64; }
        try {
            var crop = JSON.readTree(raw == null ? "{}" : raw).path(key);
            if (crop.isObject()) {
                x = crop.path("centerX").asDouble(x);
                y = crop.path("centerY").asDouble(y);
                size = crop.path("size").asDouble(size);
            }
        } catch (Exception ignored) { /* Malformed historical config must not break the widget. */ }
        return normalize(x, y, size);
    }

    public static PetAvatarCrop normalize(double x, double y, double size) {
        size = Double.isFinite(size) ? Math.clamp(size, 0.15, 0.85) : 0.52;
        x = Double.isFinite(x) ? x : 0.5;
        y = Double.isFinite(y) ? y : 0.34;
        return new PetAvatarCrop(Math.clamp(x, size / 2, 1 - size / 2),
            Math.clamp(y, size / 2, 1 - size / 2), size);
    }

    public Map<String, Double> toMap() {
        return Map.of("centerX", centerX, "centerY", centerY, "size", size);
    }
}
