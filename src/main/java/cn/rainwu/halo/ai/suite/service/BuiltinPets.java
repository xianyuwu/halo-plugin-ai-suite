package cn.rainwu.halo.ai.suite.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置宠物皮肤注册表。
 *
 * <p>内置皮肤由 AI 生成管线在开发期产出，图片打包在 {@code static/pets/<name>/} 下，
 * 每个状态一张图片（idle 必有，其余可选），支持 SVG 与透明 PNG。
 * 预览与访客端统一通过 {@link BuiltinPet#imageUrl(String)} 获取资源地址。
 */
public final class BuiltinPets {

    private static final String ASSET_BASE = "/plugins/ai-suite/assets/res/pets/";

    private BuiltinPets() {
    }

    public record BuiltinPet(String name, String displayName, List<String> states,
                             String imageExtension) {
        public BuiltinPet(String name, String displayName, List<String> states) {
            this(name, displayName, states, "svg");
        }

        public String imageUrl(String state) {
            return ASSET_BASE + name + "/" + state + "." + imageExtension;
        }
    }

    /** 内置三款皮肤，states 中 idle 必须在第一位。 */
    private static final List<BuiltinPet> PETS = List.of(
        new BuiltinPet("mint-robot", "薄荷机器人",
            List.of("idle", "blink", "happy", "sad", "thinking"), "png"),
        new BuiltinPet("cream-cat", "奶油橘猫",
            List.of("idle", "blink", "happy", "sad", "thinking"), "png"),
        new BuiltinPet("star-sprite", "星云小精灵",
            List.of("idle", "blink", "happy", "sad", "thinking"), "png")
    );

    public static List<BuiltinPet> list() {
        return PETS;
    }

    public static BuiltinPet find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return PETS.stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
    }

    /** 组装成下发给 widget 的 manifest（与 AI 生成宠物的 manifest 结构一致） */
    public static Map<String, Object> manifest(BuiltinPet pet) {
        Map<String, String> images = new LinkedHashMap<>();
        for (String state : pet.states()) {
            images.put(state, pet.imageUrl(state));
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("name", pet.displayName());
        manifest.put("images", images);
        return manifest;
    }
}
