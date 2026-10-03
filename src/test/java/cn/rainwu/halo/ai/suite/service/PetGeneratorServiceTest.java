package cn.rainwu.halo.ai.suite.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class PetGeneratorServiceTest {

    @Test
    void masterPromptKeepsUserRequestAndAppendsMandatoryQualityRules() {
        String prompt = PetGeneratorService.buildMasterPrompt("soft-3d",
            "  奶油白机器人  \n  蓝色屏幕  ");

        assertThat(prompt).contains("管理员补充要求：奶油白机器人 蓝色屏幕");
        assertThat(prompt).contains("不得出现地面、平台、底座、接触阴影");
        assertThat(prompt.indexOf("管理员补充要求"))
            .isLessThan(prompt.indexOf("强制质量要求"));
    }

    @Test
    void masterPoseGuidanceAppliesToBothStylesWithoutForcingSeatedPose() {
        for (String style : List.of("soft-3d", "pixel")) {
            String prompt = PetGeneratorService.buildMasterPrompt(style, "");
            assertThat(prompt).doesNotContain("固定正面或轻微侧面的坐姿");
            assertThat(prompt).contains("站姿保持站姿，坐姿保持坐姿", "参考图仅有头像或姿态不清晰时");
        }
    }

    @Test
    void explicitPoseRequestHasPriorityWhileExpressionPoseStaysLocked() {
        String prompt = PetGeneratorService.buildMasterPrompt("soft-3d", "直立站着，双手自然下垂");
        assertThat(prompt).contains("管理员补充要求：直立站着，双手自然下垂",
            "管理员补充要求明确指定姿态时，优先采用指定姿态");
        assertThat(prompt).endsWith("保持主体辨识特征，适合 96 像素网页挂件。");
        assertThat(PetGeneratorService.buildExpressionPrompts("").values())
            .allSatisfy(value -> assertThat(value).contains("身体姿态", "背景不变"));
    }

    @Test
    void expressionPromptPreservesFaceOnlyConstraint() {
        Map<String, String> prompts = PetGeneratorService.buildExpressionPrompts(
            "表情克制，不要露牙");

        assertThat(prompts.keySet()).containsExactly("blink", "happy", "sad", "thinking");
        assertThat(prompts.values()).allSatisfy(prompt -> {
            assertThat(prompt).contains("表情克制，不要露牙");
            assertThat(prompt).contains("只允许改变眼睛、眉毛和嘴巴");
        });
    }

    @Test
    void customPromptIsBoundedByUnicodeCodePoints() {
        String normalized = PetGeneratorService.normalizePrompt(
            "🐱".repeat(600), PetGeneratorService.MAX_CUSTOM_PROMPT_CODE_POINTS);

        assertThat(normalized.codePointCount(0, normalized.length()))
            .isEqualTo(PetGeneratorService.MAX_CUSTOM_PROMPT_CODE_POINTS);
        assertThat(normalized).doesNotEndWith("�");
    }

    @Test
    void expressionCompositionLocksOutsidePixelsAndMasterAlpha() throws Exception {
        BufferedImage master = solidImage(40, 40, 0x806496C8);
        BufferedImage edited = solidImage(40, 40, 0xFFFF3214);
        PetStore.ExpressionRegion region = new PetStore.ExpressionRegion();
        region.setCenterX(0.5);
        region.setCenterY(0.5);
        region.setWidth(0.5);
        region.setHeight(0.5);
        region.setFeather(0.2);

        byte[] resultBytes = PetGeneratorService.composeExpression(
            png(master), png(edited), region);
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(resultBytes));

        assertThat(result.getRGB(0, 0)).isEqualTo(master.getRGB(0, 0));
        assertThat(result.getRGB(20, 20) & 0x00FFFFFF).isEqualTo(0x00FF3214);
        for (int y = 0; y < result.getHeight(); y++) {
            for (int x = 0; x < result.getWidth(); x++) {
                assertThat(result.getRGB(x, y) >>> 24)
                    .as("alpha at %s,%s", x, y)
                    .isEqualTo(master.getRGB(x, y) >>> 24);
            }
        }
    }

    @Test
    void expressionCompositionRejectsMismatchedCanvas() throws Exception {
        assertThatThrownBy(() -> PetGeneratorService.composeExpression(
            png(solidImage(40, 40, 0xFFFFFFFF)),
            png(solidImage(32, 40, 0xFF000000)),
            new PetStore.ExpressionRegion()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("尺寸与母版不一致");
    }

    @Test
    void expressionRegionIsClampedToSafeBounds() {
        PetStore.ExpressionRegion region = new PetStore.ExpressionRegion();
        region.setCenterX(-10);
        region.setCenterY(10);
        region.setWidth(0);
        region.setHeight(2);
        region.setFeather(Double.NaN);

        PetStore.ExpressionRegion normalized = PetGeneratorService.normalizeRegion(region);

        assertThat(normalized.getCenterX()).isEqualTo(0.05);
        assertThat(normalized.getCenterY()).isEqualTo(0.95);
        assertThat(normalized.getWidth()).isEqualTo(0.1);
        assertThat(normalized.getHeight()).isEqualTo(0.9);
        assertThat(normalized.getFeather()).isEqualTo(0.12);
    }

    @Test
    void backgroundEraserOnlyReducesAlphaInsideSoftBrush() throws Exception {
        int sourcePixel = 0xC86496C8;
        BufferedImage source = solidImage(40, 40, sourcePixel);
        PetStore.EraseStroke stroke = new PetStore.EraseStroke();
        stroke.setX(0.5);
        stroke.setY(0.5);
        stroke.setRadius(0.2);

        BufferedImage result = ImageIO.read(new ByteArrayInputStream(
            PetGeneratorService.erasePixels(png(source), List.of(stroke))));

        assertThat(result.getRGB(20, 20) >>> 24).isZero();
        assertThat(result.getRGB(20, 20) & 0x00FFFFFF).isEqualTo(sourcePixel & 0x00FFFFFF);
        assertThat(result.getRGB(0, 0)).isEqualTo(sourcePixel);
        assertThat(result.getRGB(26, 20) >>> 24).isBetween(1, 199);
    }

    @Test
    void colorSelectionDeletesOnlyExactSelectedPixelsAndPreservesRgb() throws Exception {
        var source = solidImage(4, 4, 0xC86496C8);
        var mask = new ColorEraseMask(); mask.setWidth(4); mask.setHeight(4);
        mask.setRuns(List.of(List.of(2, 2), List.of(8, 1)));
        var result = ImageIO.read(new ByteArrayInputStream(PetGeneratorService.erasePixels(png(source), List.of(), mask)));
        for (int i = 0; i < 16; i++) {
            assertThat(result.getRGB(i % 4, i / 4) & 0xFFFFFF).isEqualTo(0x6496C8);
            assertThat(result.getRGB(i % 4, i / 4) >>> 24).isEqualTo(i == 2 || i == 3 || i == 8 ? 0 : 200);
        }
    }

    @Test
    void colorSelectionRejectsWrongSizeOverflowOverlapAndEmptyRanges() {
        var mask = new ColorEraseMask(); mask.setWidth(4); mask.setHeight(4);
        mask.setRuns(List.of(List.of(0, 1)));
        assertThatThrownBy(() -> mask.apply(new double[16], 8, 2)).isInstanceOf(IllegalArgumentException.class);
        for (var runs : List.of(List.of(List.of(15, Integer.MAX_VALUE)), List.of(List.of(-1, 2)),
            List.of(List.of(0, 2), List.of(1, 1)), List.of(List.of(0, 0)), List.of(List.of(1)))) {
            mask.setRuns(runs);
            assertThatThrownBy(() -> mask.apply(new double[16], 4, 4)).isInstanceOf(IllegalArgumentException.class);
        }
        mask.setRuns(List.of());
        assertThatThrownBy(() -> mask.apply(new double[16], 4, 4)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void backgroundEraserClampsInputAndLimitsStrokeCount() {
        PetStore.EraseStroke invalid = new PetStore.EraseStroke();
        invalid.setX(-2);
        invalid.setY(4);
        invalid.setRadius(1);

        var normalized = PetGeneratorService.normalizeEraseStrokes(
            java.util.Collections.nCopies(301, invalid));

        assertThat(normalized).hasSize(300);
        assertThat(normalized.getFirst().getX()).isZero();
        assertThat(normalized.getFirst().getY()).isEqualTo(1);
        assertThat(normalized.getFirst().getRadius()).isEqualTo(0.2);
    }

    @Test
    void legacyAndApprovedPetsCanGenerateExpressionsButPendingPetsCannot() {
        PetStore.PetRecord legacy = new PetStore.PetRecord();
        PetStore.PetRecord pending = new PetStore.PetRecord();
        pending.setBackgroundStatus(PetGeneratorService.BACKGROUND_PENDING);
        PetStore.PetRecord approved = new PetStore.PetRecord();
        approved.setBackgroundStatus(PetGeneratorService.BACKGROUND_APPROVED);

        assertThat(PetGeneratorService.isBackgroundApproved(legacy)).isTrue();
        assertThat(PetGeneratorService.isBackgroundApproved(pending)).isFalse();
        assertThat(PetGeneratorService.isBackgroundApproved(approved)).isTrue();
    }

    @Test
    void relativeAttachmentUsesRunningPortForPortlessLocalhost() {
        assertThat(PetGeneratorService.resolveDownloadUri(
            "/upload/pet-idle.png", URI.create("http://localhost"), 8090))
            .isEqualTo(URI.create("http://localhost:8090/upload/pet-idle.png"));
        assertThat(PetGeneratorService.resolveDownloadUri(
            "/upload/pet-idle.png", URI.create("https://blog.example.com"), 8090))
            .isEqualTo(URI.create("https://blog.example.com/upload/pet-idle.png"));
    }

    @Test
    void absoluteLocalAttachmentAlsoUsesRunningPort() {
        assertThat(PetGeneratorService.resolveDownloadUri(
            "http://localhost/upload/pet-idle.png",
            URI.create("https://blog.example.com"), 8090))
            .isEqualTo(URI.create("http://localhost:8090/upload/pet-idle.png"));
        assertThat(PetGeneratorService.resolveDownloadUri(
            "https://cdn.example.com/pet.png?signature=a%2Bb",
            URI.create("http://localhost"), 8090))
            .isEqualTo(URI.create("https://cdn.example.com/pet.png?signature=a%2Bb"));
    }

    private static BufferedImage solidImage(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
