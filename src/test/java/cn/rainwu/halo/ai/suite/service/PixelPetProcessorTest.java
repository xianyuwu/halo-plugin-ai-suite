package cn.rainwu.halo.ai.suite.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class PixelPetProcessorTest {
    @Test
    void masterHasDeterministicPaletteBinaryAlphaAndUniformBlocks() throws Exception {
        byte[] source = png(source());
        byte[] bytes = PixelPetProcessor.prepareMaster(source);
        BufferedImage master = read(bytes);
        assertThat(bytes).isEqualTo(PixelPetProcessor.prepareMaster(source));
        assertThat(master.getWidth()).isEqualTo(384);
        assertThat(colors(master)).hasSizeLessThanOrEqualTo(24);
        assertGrid(master);
        assertThat(master.getRGB(0, 0) >>> 24).isZero();
        assertThat(master.getRGB(192, 192) >>> 24).isEqualTo(255);
        assertThat(read(PixelPetProcessor.modelReference(bytes)).getWidth()).isEqualTo(1024);
    }

    @Test
    void expressionsUseMasterPaletteAndLockGridRegionAndAlpha() throws Exception {
        byte[] bytes = PixelPetProcessor.prepareMaster(png(source()));
        BufferedImage master = read(bytes);
        BufferedImage candidate = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB);
        var g = candidate.createGraphics(); g.setColor(java.awt.Color.MAGENTA);
        g.fillRect(0, 0, 1024, 1024); g.dispose();
        PetStore.ExpressionRegion r = new PetStore.ExpressionRegion();
        r.setCenterX(.5); r.setCenterY(.5); r.setWidth(.5); r.setHeight(.5);
        BufferedImage result = read(PixelPetProcessor.compose(bytes, png(candidate), r));
        Set<Integer> palette = colors(master);
        assertThat(palette).containsAll(colors(result));
        assertGrid(result);
        int changed = 0;
        for (int y = 0; y < 384; y++) {
            for (int x = 0; x < 384; x++) {
                int a = master.getRGB(x, y), b = result.getRGB(x, y);
                assertThat(b >>> 24).isEqualTo(a >>> 24);
                double dx = (x / 4 + .5 - 48) / 24;
                double dy = (y / 4 + .5 - 48) / 24;
                if (dx * dx + dy * dy >= 1) assertThat(b).isEqualTo(a);
                if (a != b) changed++;
            }
        }
        assertThat(changed).isPositive();
    }

    @Test
    void cleanupRemainsGridAlignedAndRejectsEmptyOrWrongSizedImages() throws Exception {
        byte[] bytes = PixelPetProcessor.prepareMaster(png(source()));
        BufferedImage erased = read(bytes);
        for (int y = 100; y < 220; y++) {
            for (int x = 100; x < 220; x++) erased.setRGB(x, y, erased.getRGB(x, y) & 0x66FFFFFF);
        }
        BufferedImage cleaned = read(PixelPetProcessor.cleanup(png(erased)));
        assertGrid(cleaned);
        assertThat(cleaned.getRGB(152, 152) >>> 24).isZero();
        assertThat(colors(read(bytes))).containsAll(colors(cleaned));
        assertThatThrownBy(() -> PixelPetProcessor.prepareMaster(png(
            new BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB))))
            .hasMessageContaining("没有可见角色");
        assertThatThrownBy(() -> PixelPetProcessor.compose(bytes, bytes, new PetStore.ExpressionRegion()))
            .hasMessageContaining("尺寸");
    }

    @Test
    void historicalAndOtherStylesKeepLegacyPipeline() {
        PetStore.PetRecord record = new PetStore.PetRecord(); record.setStyle("pixel");
        assertThat(PetGeneratorService.usesPixelPipeline(record)).isFalse();
        record.setPixelGridSize(96);
        assertThat(PetGeneratorService.usesPixelPipeline(record)).isTrue();
        record.setStyle("soft-3d");
        assertThat(PetGeneratorService.usesPixelPipeline(record)).isFalse();
        assertThat(PetGeneratorService.buildExpressionPrompts("", true).get("blink"))
            .contains("母版色板", "不添加渐变");
    }

    private static BufferedImage source() {
        BufferedImage image = new BufferedImage(192, 192, BufferedImage.TYPE_INT_ARGB);
        for (int y = 24; y < 168; y++) {
            for (int x = 24; x < 168; x++) {
                image.setRGB(x, y, 0xFF000000 | ((x * 3 % 256) << 16) | ((y * 5 % 256) << 8) | (x + y) % 256);
            }
        }
        return image;
    }

    private static void assertGrid(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int p = image.getRGB(x, y);
                assertThat(p >>> 24).isIn(0, 255);
                assertThat(p).isEqualTo(image.getRGB(x / 4 * 4, y / 4 * 4));
            }
        }
    }

    private static Set<Integer> colors(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        for (int p : image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth())) {
            if ((p >>> 24) != 0) colors.add(p & 0xFFFFFF);
        }
        return colors;
    }

    private static byte[] png(BufferedImage image) throws Exception {
        var out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); return out.toByteArray();
    }

    private static BufferedImage read(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }
}
