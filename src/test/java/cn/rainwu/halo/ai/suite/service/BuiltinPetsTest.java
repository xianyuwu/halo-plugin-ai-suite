package cn.rainwu.halo.ai.suite.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BuiltinPetsTest {
    @Test
    void everyManifestImageResolvesToAPackagedResource() throws Exception {
        for (var pet : BuiltinPets.list()) {
            assertThat(pet.states()).startsWith("idle");
            Map<?, ?> images = (Map<?, ?>) BuiltinPets.manifest(pet).get("images");
            for (var state : pet.states()) {
                String url = (String) images.get(state);
                assertThat(url).isEqualTo(pet.imageUrl(state));
                String path = url.replace("/plugins/ai-suite/assets/res/", "/static/");
                try (var resource = getClass().getResourceAsStream(path)) {
                    assertThat(resource).as("%s %s", pet.name(), state).isNotNull();
                    if ("png".equals(pet.imageExtension())) {
                        var image = ImageIO.read(resource);
                        assertThat(image).isNotNull();
                        assertThat(image.getWidth()).isEqualTo(384);
                        assertThat(image.getHeight()).isEqualTo(384);
                        assertThat(image.getColorModel().hasAlpha()).isTrue();
                        assertThat(image.getRGB(0, 0) >>> 24).isZero();
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cream-cat", "star-sprite"})
    void expressionsKeepTheSameTransparentSilhouette(String skin) throws Exception {
        var cat = BuiltinPets.find(skin);
        assertThat(cat).isNotNull();
        assertThat(cat.states()).containsExactly("idle", "blink", "happy", "sad", "thinking");
        int[] idleAlpha = null;
        for (String state : cat.states()) {
            try (var resource = getClass().getResourceAsStream("/static/pets/" + skin + "/" + state + ".png")) {
                var image = ImageIO.read(resource);
                int[] alpha = new int[image.getWidth() * image.getHeight()];
                int transparent = 0;
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        int value = image.getRGB(x, y) >>> 24;
                        alpha[y * image.getWidth() + x] = value;
                        if (value == 0) {
                            transparent++;
                        }
                    }
                }
                assertThat(transparent).isBetween(alpha.length / 4, alpha.length * 3 / 4);
                if (idleAlpha == null) {
                    idleAlpha = alpha;
                } else {
                    assertThat(alpha).as("%s silhouette", state).containsExactly(idleAlpha);
                }
            }
        }
    }

    @Test
    void onlyThreeNewSkinsAreRegistered() {
        var robot = BuiltinPets.find("mint-robot");
        assertThat(robot).isNotNull();
        assertThat(robot.states()).containsExactly("idle", "blink", "happy", "sad", "thinking");
        assertThat(robot.imageUrl("idle"))
            .isEqualTo("/plugins/ai-suite/assets/res/pets/mint-robot/idle.png");
        assertThat(BuiltinPets.list()).extracting(BuiltinPets.BuiltinPet::name)
            .containsExactly("mint-robot", "cream-cat", "star-sprite");
        for (String name : List.of("beacon", "tofu", "marsh", "spark")) {
            assertThat(BuiltinPets.find(name)).isNull();
            assertThat(getClass().getResource("/static/pets/" + name + "/idle.svg")).isNull();
        }
    }
}
