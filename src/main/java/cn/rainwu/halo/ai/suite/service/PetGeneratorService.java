package cn.rainwu.halo.ai.suite.service;

import cn.rainwu.halo.ai.suite.config.AIProperties;
import cn.rainwu.halo.ai.suite.llm.LlmClient;
import cn.rainwu.halo.ai.suite.llm.UsageScenario;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.media.DataContent;
import run.halo.aifoundation.media.GeneratedFile;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import org.springframework.data.domain.Sort;

/**
 * AI 贴纸宠物生成管线：照片 → Q 版贴纸主图 → 表情变体 → 附件库。
 *
 * <p>图生图和表情变体都走 AI Foundation 的 imageGenerationModel，厂商差异由它吸收。
 * 产物上传到 Halo 附件库拿 permalink，访客端可直接访问、随 Halo 备份。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PetGeneratorService {

    static final int MAX_CUSTOM_PROMPT_CODE_POINTS = 500;
    static final int MAX_EXPRESSION_PROMPT_CODE_POINTS = 300;
    public static final String BACKGROUND_PENDING = "pending";
    public static final String BACKGROUND_APPROVED = "approved";

    /** 状态契约里需要表情图的四个状态；idle 用主图 */
    private static final Map<String, String> EXPRESSION_PROMPTS = expressionPrompts();

    private static Map<String, String> expressionPrompts() {
        Map<String, String> prompts = new LinkedHashMap<>();
        prompts.put("blink", "严格保持画布、构图、身体姿态、头部角度、轮廓、材质、毛发、光线和背景不变，"
            + "只把双眼改成自然闭合的眨眼瞬间；不要移动五官，不要增加装饰或文字");
        prompts.put("happy", "严格保持画布、构图、身体姿态、头部角度、轮廓、材质、毛发、光线和背景不变，"
            + "只把眼睛和嘴巴改成开心微笑的表情；不要移动五官，不要增加装饰或文字");
        prompts.put("sad", "严格保持画布、构图、身体姿态、头部角度、轮廓、材质、毛发、光线和背景不变，"
            + "只把眼睛、眉毛和嘴巴改成轻微委屈的表情；不要歪头，不要增加眼泪、装饰或文字");
        prompts.put("thinking", "严格保持画布、构图、身体姿态、头部角度、轮廓、材质、毛发、光线和背景不变，"
            + "只把眼睛、眉毛和嘴巴改成若有所思的表情；不要歪头，不要增加问号、装饰或文字");
        return java.util.Collections.unmodifiableMap(prompts);
    }

    private static final Map<String, String> STYLE_PROMPTS = Map.of(
        "soft-3d", "把照片中的主体设计成精致、温暖、具有收藏玩偶质感的 3D 卡通宠物："
            + "保留原主体最有辨识度的颜色、纹理与特征，圆润自然的造型，细腻柔软的材质，"
            + "柔和棚拍光线，清晰但不过度锐化，完整身体，"
            + "角色居中且四周留出安全边距，纯白色背景，无阴影、无文字、无装饰、无边框，"
            + "适合作为网页角落 96 像素大小的高品质互动宠物",
        "chibi", "把照片中的主体变成一个可爱的 Q 版卡通贴纸角色：大头小身、圆润粗描边矢量插画风格，"
            + "纯白色背景（无阴影、无渐变、无杂物），角色居中完整呈现，色彩明快，适合作为网页小宠物挂件",
        "flat", "把照片中的主体变成一个扁平插画风格的贴纸角色：简洁几何色块、无描边或细描边，"
            + "纯白色背景（无阴影、无渐变、无杂物），角色居中完整呈现，现代设计感，适合作为网页小宠物挂件",
        "pixel", "把照片中的主体变成一个复古像素画风格的贴纸角色：统一方形像素网格、有限色板、清晰的阶梯状轮廓，简化五官和纹理，禁止渐变、抗锯齿和抖色噪点，"
            + "纯白色背景（无阴影、无渐变、无杂物），角色居中完整呈现，适合作为网页小宠物挂件"
    );

    private static final String MASTER_POSE_GUIDANCE =
        "姿态要求：管理员补充要求明确指定姿态时，优先采用指定姿态；"
            + "未指定时，保留参考图中清晰可辨的身体姿态、朝向及四肢关系，站姿保持站姿，坐姿保持坐姿；"
            + "参考图仅有头像或姿态不清晰时，补全符合主体结构的自然正面或轻微侧面姿态，"
            + "不要统一改成坐姿或蹲姿。";

    private static final String MASTER_QUALITY_CONSTRAINTS =
        "强制质量要求：画面中只能有一个完整角色，角色居中且四周留有安全边距；"
            + "角色脚下和周围必须完全空白，不得出现地面、平台、底座、接触阴影、投影、白斑或环境元素；"
            + "使用均匀纯白背景，无文字、无边框、无装饰；保持主体辨识特征，适合 96 像素网页挂件。";

    private static final String EXPRESSION_QUALITY_CONSTRAINTS =
        "强制质量要求：只允许改变眼睛、眉毛和嘴巴的表情；"
            + "严格保持画布尺寸、构图、身体姿态、头部角度、角色轮廓、材质、毛发、光线和背景不变；"
            + "不得增加地面、底座、阴影、道具、符号、文字或装饰。";

    static String buildMasterPrompt(String style, String customPrompt) {
        String normalized = normalizePrompt(customPrompt, MAX_CUSTOM_PROMPT_CODE_POINTS);
        StringBuilder prompt = new StringBuilder(
            STYLE_PROMPTS.getOrDefault(style, STYLE_PROMPTS.get("chibi")));
        if (!normalized.isBlank()) {
            prompt.append("。管理员补充要求：").append(normalized);
        }
        return prompt.append("。").append(MASTER_POSE_GUIDANCE)
            .append(MASTER_QUALITY_CONSTRAINTS).toString();
    }

    static Map<String, String> buildExpressionPrompts(String customPrompt) {
        String normalized = normalizePrompt(customPrompt, MAX_EXPRESSION_PROMPT_CODE_POINTS);
        Map<String, String> prompts = new LinkedHashMap<>();
        EXPRESSION_PROMPTS.forEach((state, base) -> {
            StringBuilder prompt = new StringBuilder(base);
            if (!normalized.isBlank()) {
                prompt.append("。管理员补充要求：").append(normalized);
            }
            prompts.put(state,
                prompt.append("。").append(EXPRESSION_QUALITY_CONSTRAINTS).toString());
        });
        return prompts;
    }

    static Map<String, String> buildExpressionPrompts(String customPrompt, boolean pixel) {
        Map<String, String> prompts = buildExpressionPrompts(customPrompt);
        if (pixel) {
            prompts.replaceAll((state, prompt) -> prompt
                + "保持复古像素画风、方形像素块和母版色板，只修改五官，不添加渐变或抗锯齿。");
        }
        return prompts;
    }

    static String normalizePrompt(String prompt, int maxCodePoints) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        String normalized = prompt.trim().replaceAll("\\s+", " ");
        int count = normalized.codePointCount(0, normalized.length());
        if (count <= maxCodePoints) {
            return normalized;
        }
        return normalized.substring(0, normalized.offsetByCodePoints(0, maxCodePoints));
    }

    public Map<String, Object> previewPrompts(
        String style, String customPrompt, String expressionPrompt) {
        return Map.of(
            "master", buildMasterPrompt(style, customPrompt),
            "expressions", buildExpressionPrompts(expressionPrompt, "pixel".equals(style)));
    }

    public Map<String, Object> previewPrompts(String style, String customPrompt,
        String expressionPrompt, String mode) {
        if ("motion".equals(mode) && !"soft-3d".equals(style)) {
            throw new IllegalArgumentException("轻动作目前支持精致立体风格");
        }
        return Map.of("master", buildMasterPrompt(style, customPrompt), "expressions",
            "motion".equals(mode) ? buildMotionPrompts(expressionPrompt)
                : buildExpressionPrompts(expressionPrompt, "pixel".equals(style)));
    }

    private final LlmClient llmClient;
    private final AIProperties aiProperties;
    private final PetStore petStore;
    private final ReactiveExtensionClient client;
    private final run.halo.app.infra.ExternalUrlSupplier externalUrlSupplier;
    private final ApplicationContext applicationContext;
    private final PetAttachmentService petAttachments;

    public Mono<cn.rainwu.halo.ai.suite.llm.AiFoundationClient.ImageModelStatus> imageModelStatus() {
        return aiProperties.getModelConfig()
            .flatMap(config -> llmClient.imageModelStatus(config.getEffectiveImageModel()));
    }

    // 生成的 1024x1024 PNG 约几百 KB～数 MB，WebClient 默认 256KB 缓冲会
    // DataBufferLimitException，放宽到 20MB（与上传照片上限同量级）
    private final WebClient webClient = WebClient.builder()
        .codecs(c -> c.defaultCodecs().maxInMemorySize(20 * 1024 * 1024))
        .build();

    /**
     * 生成一只宠物的待机母版。表情必须等背景审核通过后由独立接口生成。
     *
     * @param photoBytes      用户上传的照片
     * @param photoMediaType  照片 MIME（image/jpeg 等）
     * @param name            宠物名称（管理员起的）
     * @param style           画风：chibi / flat / pixel
     * @param withExpressions 兼容旧调用方保留，新的强制审核流程会忽略该参数
     */
    public Mono<PetStore.PetRecord> generatePet(byte[] photoBytes, String photoMediaType,
                                                String name, String style,
                                                boolean withExpressions) {
        return generatePet(photoBytes, photoMediaType, name, style, withExpressions, "");
    }

    public Mono<PetStore.PetRecord> generatePet(byte[] photoBytes, String photoMediaType,
                                                String name, String style,
                                                boolean withExpressions,
                                                String customPrompt) {
        String normalizedCustomPrompt = normalizePrompt(
            customPrompt, MAX_CUSTOM_PROMPT_CODE_POINTS);
        String masterPrompt = buildMasterPrompt(style, normalizedCustomPrompt);
        // id 提前生成：附件文件名带上它，避免第二只宠物的 idle.png 覆盖第一只的
        String petId = "pet-" + UUID.randomUUID().toString().substring(0, 8);
        return aiProperties.getModelConfig()
            .transform(source -> PetGenerationProgress.track(source, "preparing", "idle"))
            .flatMap(modelConfig -> {
                String model = modelConfig.getEffectiveImageModel();
                var reference = DataContent.data(photoBytes, photoMediaType, "photo");
                return llmClient.generateImage(model, masterPrompt, List.of(reference),
                        "1024x1024", UsageScenario.PET_GENERATE)
                    .transform(source -> PetGenerationProgress.track(source, "model", "idle"))
                    .flatMap(this::firstImageBytes)
                    .map(bytes -> "pixel".equals(style) ? PixelPetProcessor.prepareMaster(bytes) : bytes)
                    .flatMap(mainBytes -> uploadImage(mainBytes, petId + "-idle.png")
                        .transform(source -> PetGenerationProgress.track(source, "saving", "idle")))
                    .map(idleUrl -> {
                        Map<String, String> images = new LinkedHashMap<>();
                        images.put("idle", idleUrl);
                        return images;
                    });
            })
            .flatMap(images -> {
                PetStore.PetRecord record = new PetStore.PetRecord();
                record.setId(petId);
                record.setName(name == null || name.isBlank() ? "未命名宠物" : name.trim());
                record.setStyle(style);
                record.setPixelGridSize("pixel".equals(style) ? PixelPetProcessor.GRID : null);
                record.setCreatedAt(Instant.now().getEpochSecond());
                record.setImages(images);
                record.setCustomPrompt(normalizedCustomPrompt);
                record.setPromptSnapshot(masterPrompt);
                String idleUrl = images.get("idle");
                record.setOriginalMasterUrl(idleUrl);
                record.setCleanedMasterUrl(idleUrl);
                record.setBackgroundStatus(BACKGROUND_PENDING);
                record.setBackgroundApprovedAt(null);
                return petStore.add(record).flatMap(saved -> PetGenerationProgress.completed(saved, "idle"));
            });
    }

    /**
     * 以原照和新补充要求重新生成母版。新图完成上传前不修改现有记录；
     * 替换母版后清空旧表情，避免不同角色版本混用。
     */
    public Mono<PetStore.PetRecord> regenerateMaster(String petId, byte[] photoBytes, String photoMediaType, String name, String style, String customPrompt) {
        return exclusive(petId, () -> regenerateMasterUnlocked(petId, photoBytes, photoMediaType, name, style, customPrompt));
    }

    private Mono<PetStore.PetRecord> regenerateMasterUnlocked(
        String petId, byte[] photoBytes, String photoMediaType, String name,
        String style, String customPrompt) {
        String normalizedCustomPrompt = normalizePrompt(
            customPrompt, MAX_CUSTOM_PROMPT_CODE_POINTS);
        String masterPrompt = buildMasterPrompt(style, normalizedCustomPrompt);
        return petStore.get(petId)
            .transform(source -> PetGenerationProgress.track(source, "preparing", "idle"))
            .filter(record -> record != null)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("宠物不存在: " + petId)))
            .flatMap(record -> {
                String previousUrl = record.getImages().get("idle");
                int previousRevision = record.getCleanupRevision();
                return aiProperties.getModelConfig()
                .flatMap(modelConfig -> {
                    String model = modelConfig.getEffectiveImageModel();
                    var reference = DataContent.data(photoBytes, photoMediaType, "photo");
                    return llmClient.generateImage(model, masterPrompt, List.of(reference),
                            "1024x1024", UsageScenario.PET_GENERATE)
                        .transform(source -> PetGenerationProgress.track(source, "model", "idle"))
                        .flatMap(this::firstImageBytes)
                        .map(bytes -> "pixel".equals(style) ? PixelPetProcessor.prepareMaster(bytes) : bytes)
                        .flatMap(mainBytes -> uploadImage(mainBytes,
                            petId + "-idle.png")
                            .transform(source -> PetGenerationProgress.track(source, "saving", "idle")));
                })
                .flatMap(idleUrl -> petStore.modify(petId, latest -> {
                    requireMasterVersion(latest, previousUrl, previousRevision);
                    latest.preservePublishedVersion();
                    Map<String, String> images = new LinkedHashMap<>();
                    images.put("idle", idleUrl);
                    latest.setImages(images);
                    latest.setName(name == null || name.isBlank()
                        ? latest.getName() : name.trim());
                    latest.setStyle(style);
                    latest.setPixelGridSize("pixel".equals(style) ? PixelPetProcessor.GRID : null);
                    latest.setCustomPrompt(normalizedCustomPrompt);
                    latest.setPromptSnapshot(masterPrompt);
                    latest.setExpressionPrompt("");
                    latest.setExpressionPromptSnapshots(new LinkedHashMap<>());
                    latest.setBackgroundCleanupStrokes(new java.util.ArrayList<>());
                    latest.setOriginalMasterUrl(idleUrl);
                    latest.setCleanedMasterUrl(idleUrl);
                    latest.setBackgroundStatus(BACKGROUND_PENDING);
                    latest.setBackgroundApprovedAt(null);
                    latest.setCleanupRevision(latest.getCleanupRevision() + 1);
                    latest.setExpressionRegion(null);
                    latest.setExpressionCandidates(new LinkedHashMap<>());
                    latest.setExpressionMode(null);
                }).flatMap(saved -> PetGenerationProgress.completed(saved, "idle")));
            });
    }

    /** 重新生成某只宠物的表情变体（以已生成的主图为参考，主图本身保留） */
    public Mono<PetStore.PetRecord> regenerateExpressions(String petId) {
        return regenerateExpressions(petId, null, "");
    }

    public Mono<PetStore.PetRecord> regenerateExpressions(
        String petId, PetStore.ExpressionRegion requestedRegion) {
        return regenerateExpressions(petId, requestedRegion, "");
    }

    /**
     * 按管理员确认的脸部区域重生成表情。模型负责候选表情，本地合成锁定区域外像素和母版 Alpha。
     */
    public Mono<PetStore.PetRecord> regenerateExpressions(
        String petId, PetStore.ExpressionRegion requestedRegion, String customPrompt) {
        return exclusive(petId, () -> regenerateExpressionsUnlocked(petId, requestedRegion, customPrompt));
    }

    private Mono<PetStore.PetRecord> regenerateExpressionsUnlocked(
        String petId, PetStore.ExpressionRegion requestedRegion, String customPrompt) {
        return petStore.get(petId)
            .filter(record -> record != null)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("宠物不存在: " + petId)))
            .flatMap(record -> {
                if (!isBackgroundApproved(record)) {
                    return Mono.error(new IllegalStateException(
                        "请先检查并确认母版背景，再生成表情"));
                }
                String idleUrl = record.getImages().get("idle");
                if (idleUrl == null || idleUrl.isBlank()) {
                    return Mono.error(new IllegalStateException("该宠物缺少主图，无法重新生成表情"));
                }
                return downloadBytes(idleUrl)
                    .transform(source -> PetGenerationProgress.track(source, "preparing", ""))
                    .flatMap(mainBytes -> aiProperties.getModelConfig()
                        .flatMap(modelConfig -> {
                            String model = modelConfig.getEffectiveImageModel();
                            boolean pixel = usesPixelPipeline(record);
                            var reference = DataContent.data(pixel
                                ? PixelPetProcessor.modelReference(mainBytes) : mainBytes, "image/png", "sticker");
                            PetStore.ExpressionRegion region = normalizeRegion(requestedRegion);
                            String normalizedCustomPrompt = normalizePrompt(
                                customPrompt, MAX_EXPRESSION_PROMPT_CODE_POINTS);
                            Map<String, String> finalPrompts = buildExpressionPrompts(
                                normalizedCustomPrompt, "pixel".equals(record.getStyle()));
                            return Flux.fromIterable(finalPrompts.entrySet())
                                .concatMap(entry -> llmClient.generateImage(model,
                                        entry.getValue(), List.of(reference), "1024x1024",
                                        UsageScenario.PET_GENERATE)
                                    .transform(source -> PetGenerationProgress.track(source, "model", entry.getKey()))
                                    .flatMap(this::firstImageBytes)
                                    .map(bytes -> pixel ? PixelPetProcessor.compose(mainBytes, bytes, region)
                                        : composeExpression(mainBytes, bytes, region))
                                    .map(bytes -> Map.entry(entry.getKey(), bytes))
                                    .flatMap(candidate -> PetGenerationProgress.completed(candidate, entry.getKey()))
                                    .onErrorMap(error -> new IllegalStateException(
                                        "表情 " + entry.getKey() + " 生成失败："
                                            + error.getMessage(), error)))
                                .collectList()
                                .flatMap(candidates -> Flux.fromIterable(candidates)
                                    .concatMap(candidate -> uploadImage(candidate.getValue(),
                                            petId + "-" + candidate.getKey() + ".png")
                                        .transform(source -> PetGenerationProgress.track(source, "saving", candidate.getKey()))
                                        .map(url -> Map.entry(candidate.getKey(), url)))
                                    .collectMap(Map.Entry::getKey, Map.Entry::getValue,
                                        LinkedHashMap::new))
                                .flatMap(newImages -> Mono.defer(() -> {
                                    if (newImages.size() != EXPRESSION_PROMPTS.size()) {
                                        return Mono.error(new IllegalStateException(
                                            "表情生成不完整，未更新宠物记录"));
                                    }
                                    return petStore.modify(petId, latest -> {
                                        requireSameMaster(latest, idleUrl, record.getCleanupRevision());
                                        latest.getImages().putAll(newImages);
                                        latest.setExpressionRegion(region);
                                        latest.setExpressionCandidates(new LinkedHashMap<>());
                                        latest.setExpressionMode("face");
                                        latest.setExpressionPrompt(normalizedCustomPrompt);
                                        latest.setExpressionPromptSnapshots(new LinkedHashMap<>(finalPrompts));
                                        latest.publish();
                                    });
                                }));
                        }));
            });
    }

    static Map<String, String> buildMotionPrompts(String customPrompt) {
        String common = "以母版为唯一身份参考，保持同一角色的脸部身份、服装、颜色、材质、头身比例、光线、画布尺寸、角色大小和构图；"
            + "保持躯干朝向、双脚与鞋底位置不变，不转身、不跳跃；只允许指定的五官和小幅手臂动作，手指或前爪自然完整，不新增肢体；";
        Map<String, String> prompts = new LinkedHashMap<>();
        prompts.put("blink", "眨眼：闭眼，嘴巴保持轻微微笑，所有手臂和身体姿态严格保持母版不变。");
        prompts.put("happy", "开心：自然微笑，一只手或前爪抬至胸肩高度轻轻打招呼，另一只保持自然放松。");
        prompts.put("sad", "轻微委屈：眉毛内侧上抬、嘴角向下，双手或前爪轻轻收拢在身前，不哭泣，不低头改变头部位置。");
        prompts.put("thinking", "思考：视线轻微朝侧上方，嘴巴闭合，一只手或前爪轻托下巴，另一只自然放松，头部位置角度不变。");
        String supplement = normalizePrompt(customPrompt, MAX_EXPRESSION_PROMPT_CODE_POINTS);
        prompts.replaceAll((state, action) -> common + action
            + (supplement.isBlank() ? "" : "管理员补充要求：" + supplement + "。")
            + MASTER_QUALITY_CONSTRAINTS);
        return prompts;
    }

    private <T> Mono<T> exclusive(String petId, java.util.function.Supplier<Mono<T>> action) {
        return Mono.using(() -> {
            if (!candidateJobs.add(petId)) throw new IllegalStateException("该宠物正在生成或处理图片，请等待当前任务完成");
            return petId;
        }, ignored -> Mono.defer(action), candidateJobs::remove, true);
    }

    public Mono<Void> deletePet(String petId) {
        return exclusive(petId, () -> petStore.remove(petId));
    }

    public Mono<PetStore.PetRecord> renamePet(String petId, String requestedName) {
        return exclusive(petId, () -> {
            String name = requestedName == null ? "" : requestedName.strip();
            if (name.isBlank() || name.codePointCount(0, name.length()) > 20
                || name.codePoints().anyMatch(Character::isISOControl)) {
                return Mono.error(new IllegalArgumentException("宠物名称不能为空，最多20个字符，不能包含换行或控制字符"));
            }
            return petStore.modify(petId, record -> {
                record.setName(name);
                // Rename the public label without publishing unapproved draft images.
                if (record.getPublishedVersion() != null) record.getPublishedVersion().setName(name);
            });
        });
    }

    static void requireMasterVersion(PetStore.PetRecord record, String expectedUrl, Integer revision) {
        if (expectedUrl == null || revision == null
            || !java.util.Objects.equals(expectedUrl, record.getImages().get("idle"))
            || revision != record.getCleanupRevision()) {
            throw new IllegalStateException("母版已变化或页面版本过旧，请刷新后重新检查背景");
        }
    }

    private final java.util.Set<String> candidateJobs = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public Mono<PetStore.PetRecord> generateCandidateFrames(String petId, String state,
        String mode, PetStore.ExpressionRegion region, String customPrompt) {
        return Mono.defer(() -> {
            if (!"face".equals(mode) && !"motion".equals(mode)) {
                return Mono.error(new IllegalArgumentException("请选择仅变表情或表情加轻动作"));
            }
            if (state != null && !EXPRESSION_PROMPTS.containsKey(state)) {
                return Mono.error(new IllegalArgumentException("无效表情状态"));
            }
            List<String> states = state == null ? List.copyOf(EXPRESSION_PROMPTS.keySet()) : List.of(state);
            return exclusive(petId, () -> Flux.fromIterable(states)
                .concatMap(item -> generateCandidateFrame(petId, item, mode, region, customPrompt)).last());
        });
    }

    private Mono<PetStore.PetRecord> generateCandidateFrame(String petId, String state,
        String mode, PetStore.ExpressionRegion requestedRegion, String customPrompt) {
        return petStore.get(petId).switchIfEmpty(Mono.error(new IllegalArgumentException("宠物不存在")))
            .flatMap(record -> {
                if (!isBackgroundApproved(record)) return Mono.error(new IllegalStateException("请先确认母版背景"));
                if ("motion".equals(mode) && !"soft-3d".equals(record.getStyle())) {
                    return Mono.error(new IllegalArgumentException("轻动作目前支持精致立体风格"));
                }
                String masterUrl = record.getImages().get("idle");
                int revision = record.getCleanupRevision();
                PetStore.ExpressionRegion region = normalizeRegion(requestedRegion);
                String prompt = ("motion".equals(mode) ? buildMotionPrompts(customPrompt)
                    : buildExpressionPrompts(customPrompt, "pixel".equals(record.getStyle()))).get(state);
                return downloadBytes(masterUrl)
                    .transform(source -> PetGenerationProgress.track(source, "preparing", state))
                    .flatMap(master -> aiProperties.getModelConfig()
                    .flatMap(config -> {
                        boolean motion = "motion".equals(mode);
                        byte[] reference = motion ? resizeImage(master, 2048, 2048)
                            : usesPixelPipeline(record) ? PixelPetProcessor.modelReference(master) : master;
                        return llmClient.generateImage(config.getEffectiveImageModel(), prompt,
                                List.of(DataContent.data(reference, "image/png", "master")),
                                motion ? "2048x2048" : "1024x1024", UsageScenario.PET_GENERATE)
                            .transform(source -> PetGenerationProgress.track(source, "model", state))
                            .flatMap(this::firstImageBytes)
                            .map(bytes -> prepareCandidate(master, bytes, state, mode, region, usesPixelPipeline(record)))
                            .flatMap(bytes -> uploadImage(bytes, petId + "-" + state + "-candidate-" + UUID.randomUUID() + ".png")
                                .transform(source -> PetGenerationProgress.track(source, "saving", state)))
                            .flatMap(url -> petStore.modify(petId, latest -> {
                                requireSameMaster(latest, masterUrl, revision);
                                var candidate = new PetStore.ExpressionCandidate();
                                candidate.setImageUrl(url);
                                candidate.setOriginalUrl(url);
                                candidate.setMasterUrl(masterUrl);
                                candidate.setMasterRevision(revision);
                                candidate.setMode(mode);
                                candidate.setPrompt(prompt);
                                if (latest.getExpressionCandidates() == null) latest.setExpressionCandidates(new LinkedHashMap<>());
                                latest.getExpressionCandidates().put(state, candidate);
                                latest.setExpressionPrompt(normalizePrompt(customPrompt, MAX_EXPRESSION_PROMPT_CODE_POINTS));
                                latest.setExpressionRegion(region);
                            }).flatMap(saved -> PetGenerationProgress.completed(saved, state)));
                    }));
            });
    }

    static byte[] prepareCandidate(byte[] master, byte[] bytes, String state, String mode,
        PetStore.ExpressionRegion region, boolean pixel) {
        if ("face".equals(mode)) return pixel ? PixelPetProcessor.compose(master, bytes, region)
            : composeExpression(master, bytes, region);
        try {
            var image = javax.imageio.ImageIO.read(new ByteArrayInputStream(master));
            byte[] normalized = resizeImage(bytes, image.getWidth(), image.getHeight());
            return "blink".equals(state) ? composeExpression(master, normalized, region) : normalized;
        } catch (java.io.IOException error) { throw new IllegalArgumentException("候选图片无法读取", error); }
    }

    static byte[] resizeImage(byte[] bytes, int width, int height) {
        try {
            var source = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
            if (source == null || source.getWidth() != source.getHeight()) {
                throw new IllegalArgumentException("生成图片必须为有效方形画布，请重试该状态");
            }
            var target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            var graphics = target.createGraphics();
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, width, height, null);
            graphics.dispose();
            var output = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(target, "png", output);
            return output.toByteArray();
        } catch (java.io.IOException error) { throw new IllegalArgumentException("图片无法读取", error); }
    }

    static void requireSameMaster(PetStore.PetRecord record, String masterUrl, int revision) {
        if (!isBackgroundApproved(record) || !java.util.Objects.equals(masterUrl, record.getImages().get("idle"))
            || revision != record.getCleanupRevision()) {
            throw new IllegalStateException("母版已发生变化，请重新生成该表情");
        }
    }

    static PetStore.ExpressionCandidate requireCandidate(PetStore.PetRecord record, String state, String expectedUrl) {
        if (!EXPRESSION_PROMPTS.containsKey(state)) throw new IllegalArgumentException("无效表情状态");
        var candidate = record.getExpressionCandidates() == null ? null : record.getExpressionCandidates().get(state);
        if (candidate == null || expectedUrl == null || !expectedUrl.equals(candidate.getImageUrl())) {
            throw new IllegalStateException("候选图已变化或不存在，请刷新后重新检查");
        }
        requireSameMaster(record, candidate.getMasterUrl(), candidate.getMasterRevision());
        return candidate;
    }

    public Mono<PetStore.PetRecord> beginExpressionEdit(String petId, String state, String expectedUrl) {
        return exclusive(petId, () -> petStore.modify(petId, record -> {
            if (!EXPRESSION_PROMPTS.containsKey(state) || !isBackgroundApproved(record))
                throw new IllegalArgumentException("请先确认母版背景，并选择有效表情");
            if (expectedUrl == null || !expectedUrl.equals(record.getImages().get(state))
                || record.getExpressionCandidates().containsKey(state))
                throw new IllegalStateException("图片已变化或已有待确认图，请刷新后检查");
            record.preservePublishedVersion();
            var copy = new PetStore.ExpressionCandidate();
            copy.setEditingExisting(true);
            copy.setImageUrl(expectedUrl); copy.setOriginalUrl(expectedUrl);
            copy.setMasterUrl(record.getImages().get("idle")); copy.setMasterRevision(record.getCleanupRevision());
            copy.setMode(record.getExpressionMode());
            copy.setPrompt(record.getExpressionPromptSnapshots().get(state));
            record.getExpressionCandidates().put(state, copy);
        }));
    }

    public Mono<PetStore.PetRecord> cancelExpressionEdit(String petId, String state, String expectedUrl) {
        return exclusive(petId, () -> petStore.modify(petId, record -> {
            var candidate = requireCandidate(record, state, expectedUrl);
            if (!candidate.isEditingExisting()) throw new IllegalArgumentException("此图为生成候选，不能取消为当前版本");
            record.getExpressionCandidates().remove(state);
        }));
    }

    private static String edgeSource(PetStore.PetRecord record, String state, String expectedUrl, int revision) {
        if (state != null && !state.isBlank()) return requireCandidate(record, state, expectedUrl).getImageUrl();
        requireMasterVersion(record, expectedUrl, revision);
        return expectedUrl;
    }

    public Mono<byte[]> previewEdges(String petId, String state, String expectedUrl, int revision, int shrink, int dewhite) {
        return petStore.get(petId).switchIfEmpty(Mono.error(new IllegalArgumentException("宠物不存在")))
            .flatMap(record -> downloadBytes(edgeSource(record, state, expectedUrl, revision)))
            .map(bytes -> refineEdges(bytes, shrink, dewhite));
    }

    public Mono<PetStore.PetRecord> applyEdges(String petId, String state, String expectedUrl, int revision, int shrink, int dewhite) {
        return exclusive(petId, () -> petStore.get(petId).switchIfEmpty(Mono.error(new IllegalArgumentException("宠物不存在")))
            .flatMap(record -> downloadBytes(edgeSource(record, state, expectedUrl, revision)))
            .map(bytes -> refineEdges(bytes, shrink, dewhite))
            .flatMap(bytes -> uploadImage(bytes, petId + "-edge-" + UUID.randomUUID() + ".png"))
            .flatMap(url -> petStore.modify(petId, record -> {
                edgeSource(record, state, expectedUrl, revision);
                if (state != null && !state.isBlank()) requireCandidate(record, state, expectedUrl).setImageUrl(url);
                else {
                    record.preservePublishedVersion();
                    if (record.getOriginalMasterUrl() == null || record.getOriginalMasterUrl().isBlank()) record.setOriginalMasterUrl(expectedUrl);
                    invalidateMaster(record, url);
                }
            })));
    }

    static byte[] refineEdges(byte[] bytes, int shrink, int dewhite) {
        if (shrink < 0 || shrink > 2 || dewhite < 0 || dewhite > 100 || (shrink == 0 && dewhite == 0))
            throw new IllegalArgumentException("请选择0–2像素收边或1–100%去白边强度");
        try {
            var image = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || (long) image.getWidth() * image.getHeight() > 16_777_216)
                throw new IllegalArgumentException("图片无效或过大");
            int w = image.getWidth(), h = image.getHeight();
            int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
            int[] alpha = new int[pixels.length];
            for (int i = 0; i < pixels.length; i++) {
                int a = pixels[i] >>> 24; alpha[i] = a;
                if (a > 0 && a < 255 && dewhite > 0) {
                    int rgb = 0;
                    for (int shift : new int[]{16, 8, 0}) {
                        int c = (pixels[i] >>> shift) & 255;
                        double clean = Math.max(0, Math.min(255, (c * 255d - 255d * (255 - a)) / a));
                        int value = (int) Math.round(c + (clean - c) * dewhite / 100d);
                        rgb |= value << shift;
                    }
                    pixels[i] = (a << 24) | rgb;
                }
            }
            for (int pass = 0; pass < shrink; pass++) {
                int[] next = alpha.clone();
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    int min = alpha[y * w + x];
                    for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        min = Math.min(min, nx < 0 || nx >= w || ny < 0 || ny >= h ? 0 : alpha[ny * w + nx]);
                    }
                    next[y * w + x] = min;
                }
                alpha = next;
            }
            for (int i = 0; i < pixels.length; i++) pixels[i] = (alpha[i] << 24) | (pixels[i] & 0xffffff);
            var output = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            output.setRGB(0, 0, w, h, pixels, 0, w);
            var stream = new ByteArrayOutputStream(); javax.imageio.ImageIO.write(output, "png", stream);
            return stream.toByteArray();
        } catch (java.io.IOException error) { throw new IllegalArgumentException("图片处理失败", error); }
    }

    public Mono<PetStore.PetRecord> approveCandidate(String petId, String state, String expectedUrl) {
        return exclusive(petId, () -> approveCandidateUnlocked(petId, state, expectedUrl));
    }

    private Mono<PetStore.PetRecord> approveCandidateUnlocked(String petId, String state, String expectedUrl) {
        return petStore.modify(petId, record -> {
            var candidate = requireCandidate(record, state, expectedUrl);
            record.getImages().put(state, candidate.getImageUrl());
            record.getExpressionPromptSnapshots().put(state, candidate.getPrompt());
            record.setExpressionMode(candidate.getMode());
            record.getExpressionCandidates().remove(state);
            record.publish();
        });
    }

    public Mono<PetStore.PetRecord> cleanupCandidate(String petId, String state, String expectedUrl, List<PetStore.EraseStroke> requestedStrokes) {
        return cleanupCandidate(petId, state, expectedUrl, requestedStrokes, null);
    }

    public Mono<PetStore.PetRecord> cleanupCandidate(String petId, String state, String expectedUrl,
        List<PetStore.EraseStroke> requestedStrokes, ColorEraseMask mask) {
        return exclusive(petId, () -> cleanupCandidateUnlocked(petId, state, expectedUrl, requestedStrokes, mask));
    }

    private Mono<PetStore.PetRecord> cleanupCandidateUnlocked(String petId, String state, String expectedUrl,
        List<PetStore.EraseStroke> requestedStrokes, ColorEraseMask mask) {
        var strokes = normalizeEraseStrokes(requestedStrokes);
        if (strokes.isEmpty() && mask == null) return Mono.error(new IllegalArgumentException("请先选择或涂抹背景残留"));
        return petStore.get(petId).flatMap(record -> {
            var candidate = requireCandidate(record, state, expectedUrl);
            return downloadBytes(candidate.getImageUrl()).map(bytes -> erasePixels(bytes, strokes, mask))
                .flatMap(bytes -> uploadImage(bytes, petId + "-" + state + "-cleanup-" + UUID.randomUUID() + ".png"))
                .flatMap(url -> petStore.modify(petId, latest -> requireCandidate(latest, state, expectedUrl).setImageUrl(url)));
        });
    }

    public Mono<PetStore.PetRecord> resetCandidate(String petId, String state, String expectedUrl) {
        return exclusive(petId, () -> resetCandidateUnlocked(petId, state, expectedUrl));
    }

    private Mono<PetStore.PetRecord> resetCandidateUnlocked(String petId, String state, String expectedUrl) {
        return petStore.modify(petId, record -> {
            var candidate = requireCandidate(record, state, expectedUrl);
            candidate.setImageUrl(candidate.getOriginalUrl());
        });
    }

    /**
     * 把管理员在母版预览上涂抹的区域从待机母版 Alpha 中擦除。
     * RGB 保持原样，笔刷边缘柔化，因此不会损伤未涂抹的浅色机身或毛发。
     * 清理后旧表情失效并被移出清单，必须重新确认背景再生成表情。
     */
    public Mono<PetStore.PetRecord> cleanupBackground(String petId, List<PetStore.EraseStroke> strokes) {
        return cleanupBackground(petId, strokes, null, null);
    }

    public Mono<PetStore.PetRecord> cleanupBackground(String petId, List<PetStore.EraseStroke> requestedStrokes,
        String expectedUrl, Integer revision) {
        return cleanupBackground(petId, requestedStrokes, expectedUrl, revision, null);
    }

    public Mono<PetStore.PetRecord> cleanupBackground(String petId, List<PetStore.EraseStroke> requestedStrokes,
        String expectedUrl, Integer revision, ColorEraseMask mask) {
        return exclusive(petId, () -> {
            List<PetStore.EraseStroke> strokes = normalizeEraseStrokes(requestedStrokes);
            if (strokes.isEmpty() && mask == null) return Mono.error(new IllegalArgumentException("请先选择或涂抹背景残留"));
            return petStore.get(petId).switchIfEmpty(Mono.error(new IllegalArgumentException("宠物不存在")))
                .flatMap(record -> {
                    requireMasterVersion(record, expectedUrl, revision);
                    return downloadBytes(expectedUrl).map(bytes -> erasePixels(bytes, strokes, mask))
                        .map(bytes -> mask == null && usesPixelPipeline(record) ? PixelPetProcessor.cleanup(bytes) : bytes)
                        .flatMap(bytes -> uploadImage(bytes, petId + "-idle-clean-" + UUID.randomUUID() + ".png"))
                        .flatMap(url -> petStore.modify(petId, latest -> {
                            requireMasterVersion(latest, expectedUrl, revision);
                            latest.preservePublishedVersion();
                            if (latest.getOriginalMasterUrl() == null || latest.getOriginalMasterUrl().isBlank()) {
                                latest.setOriginalMasterUrl(expectedUrl);
                            }
                            invalidateMaster(latest, url);
                            var history = latest.getBackgroundCleanupStrokes() == null ? new java.util.ArrayList<PetStore.EraseStroke>()
                                : new java.util.ArrayList<>(latest.getBackgroundCleanupStrokes());
                            history.addAll(strokes);
                            latest.setBackgroundCleanupStrokes(history);
                        }));
                });
        });
    }

    public Mono<PetStore.PetRecord> approveBackground(String petId) {
        return approveBackground(petId, null, null);
    }

    public Mono<PetStore.PetRecord> approveBackground(String petId, String expectedUrl, Integer revision) {
        return exclusive(petId, () -> petStore.modify(petId, record -> {
            requireMasterVersion(record, expectedUrl, revision);
            if (expectedUrl.isBlank()) throw new IllegalStateException("该宠物缺少主图，无法确认背景");
            record.setBackgroundStatus(BACKGROUND_APPROVED);
            record.setBackgroundApprovedAt(Instant.now().getEpochSecond());
            record.setCleanedMasterUrl(expectedUrl);
            record.publish();
        }));
    }

    public Mono<PetStore.PetRecord> resetBackground(String petId) {
        return resetBackground(petId, null, null);
    }

    public Mono<PetStore.PetRecord> resetBackground(String petId, String expectedUrl, Integer revision) {
        return exclusive(petId, () -> petStore.modify(petId, record -> {
            requireMasterVersion(record, expectedUrl, revision);
            String original = record.getOriginalMasterUrl();
            if (original == null || original.isBlank()) throw new IllegalStateException("该宠物没有可恢复的原始母版");
            record.preservePublishedVersion();
            invalidateMaster(record, original);
            record.setBackgroundCleanupStrokes(new java.util.ArrayList<>());
        }));
    }

    private static void invalidateMaster(PetStore.PetRecord record, String url) {
        record.setImages(new LinkedHashMap<>(Map.of("idle", url)));
        record.setCleanedMasterUrl(url);
        record.setBackgroundStatus(BACKGROUND_PENDING);
        record.setBackgroundApprovedAt(null);
        record.setCleanupRevision(record.getCleanupRevision() + 1);
        record.setExpressionPrompt("");
        record.setExpressionPromptSnapshots(new LinkedHashMap<>());
        record.setExpressionRegion(null);
        record.setExpressionCandidates(new LinkedHashMap<>());
        record.setExpressionMode(null);
    }

    /** 新记录必须显式通过审核；旧记录没有该字段，按已审核兼容。 */
    static boolean isBackgroundApproved(PetStore.PetRecord record) {
        String status = record == null ? null : record.getBackgroundStatus();
        return status == null || status.isBlank() || BACKGROUND_APPROVED.equals(status);
    }

    static List<PetStore.EraseStroke> normalizeEraseStrokes(
        List<PetStore.EraseStroke> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        return requested.stream().limit(300).map(source -> {
            PetStore.EraseStroke stroke = new PetStore.EraseStroke();
            stroke.setX(clamp(source.getX(), 0d, 1d, 0.5));
            stroke.setY(clamp(source.getY(), 0d, 1d, 0.5));
            stroke.setRadius(clamp(source.getRadius(), 0.005, 0.2, 0.04));
            return stroke;
        }).toList();
    }

    static byte[] erasePixels(byte[] sourceBytes, List<PetStore.EraseStroke> strokes) {
        return erasePixels(sourceBytes, strokes, null);
    }

    static byte[] erasePixels(byte[] sourceBytes, List<PetStore.EraseStroke> strokes, ColorEraseMask mask) {
        try {
            BufferedImage source = javax.imageio.ImageIO.read(
                new ByteArrayInputStream(sourceBytes));
            if (source == null) {
                throw new IllegalArgumentException("无法读取待清理图片");
            }
            int width = source.getWidth();
            int height = source.getHeight();
            if ((long) width * height > 16_777_216) throw new IllegalArgumentException("待清理图片过大");
            double[] eraseWeights = new double[width * height];
            if (mask != null) mask.apply(eraseWeights, width, height);
            for (PetStore.EraseStroke stroke : normalizeEraseStrokes(strokes)) {
                double cx = stroke.getX() * width;
                double cy = stroke.getY() * height;
                double radius = stroke.getRadius() * Math.min(width, height);
                int minX = Math.max(0, (int) Math.floor(cx - radius));
                int maxX = Math.min(width - 1, (int) Math.ceil(cx + radius));
                int minY = Math.max(0, (int) Math.floor(cy - radius));
                int maxY = Math.min(height - 1, (int) Math.ceil(cy + radius));
                for (int y = minY; y <= maxY; y++) {
                    for (int x = minX; x <= maxX; x++) {
                        double distance = Math.hypot(x + 0.5 - cx, y + 0.5 - cy) / radius;
                        if (distance >= 1d) {
                            continue;
                        }
                        double weight;
                        if (distance <= 0.72d) {
                            weight = 1d;
                        } else {
                            double t = (1d - distance) / 0.28d;
                            weight = t * t * (3d - 2d * t);
                        }
                        int index = y * width + x;
                        eraseWeights[index] = Math.max(eraseWeights[index], weight);
                    }
                }
            }
            BufferedImage output = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int pixel = source.getRGB(x, y);
                    int alpha = (pixel >>> 24) & 0xFF;
                    int cleanedAlpha = (int) Math.round(alpha
                        * (1d - eraseWeights[y * width + x]));
                    output.setRGB(x, y, (cleanedAlpha << 24) | (pixel & 0x00FFFFFF));
                }
            }
            ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(output, "png", outputBytes);
            return outputBytes.toByteArray();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("背景残留清理失败: " + e.getMessage(), e);
        }
    }

    static boolean usesPixelPipeline(PetStore.PetRecord record) {
        return "pixel".equals(record.getStyle())
            && Integer.valueOf(PixelPetProcessor.GRID).equals(record.getPixelGridSize());
    }

    static PetStore.ExpressionRegion normalizeRegion(PetStore.ExpressionRegion requested) {
        PetStore.ExpressionRegion source = requested == null
            ? new PetStore.ExpressionRegion() : requested;
        PetStore.ExpressionRegion normalized = new PetStore.ExpressionRegion();
        normalized.setCenterX(clamp(source.getCenterX(), 0.05, 0.95, 0.5));
        normalized.setCenterY(clamp(source.getCenterY(), 0.05, 0.95, 0.35));
        normalized.setWidth(clamp(source.getWidth(), 0.1, 0.9, 0.48));
        normalized.setHeight(clamp(source.getHeight(), 0.1, 0.9, 0.38));
        normalized.setFeather(clamp(source.getFeather(), 0.02, 0.3, 0.12));
        return normalized;
    }

    private static double clamp(double value, double min, double max, double fallback) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 以柔边椭圆把候选表情合成回母版。区域外 RGB 与母版逐像素一致，输出 Alpha 始终取母版。
     */
    static byte[] composeExpression(byte[] masterBytes, byte[] editedBytes,
                                    PetStore.ExpressionRegion requestedRegion) {
        try {
            BufferedImage master = javax.imageio.ImageIO.read(new ByteArrayInputStream(masterBytes));
            BufferedImage edited = javax.imageio.ImageIO.read(new ByteArrayInputStream(editedBytes));
            if (master == null || edited == null) {
                throw new IllegalArgumentException("无法读取母版或表情候选图");
            }
            if (master.getWidth() != edited.getWidth() || master.getHeight() != edited.getHeight()) {
                throw new IllegalArgumentException("表情候选图尺寸与母版不一致");
            }
            PetStore.ExpressionRegion region = normalizeRegion(requestedRegion);
            int width = master.getWidth();
            int height = master.getHeight();
            double centerX = region.getCenterX() * width;
            double centerY = region.getCenterY() * height;
            double radiusX = region.getWidth() * width / 2d;
            double radiusY = region.getHeight() * height / 2d;
            double solidBoundary = 1d - region.getFeather();
            BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int masterPixel = master.getRGB(x, y);
                    int alpha = (masterPixel >>> 24) & 0xFF;
                    double dx = (x + 0.5 - centerX) / radiusX;
                    double dy = (y + 0.5 - centerY) / radiusY;
                    double distance = Math.sqrt(dx * dx + dy * dy);
                    double weight;
                    if (distance >= 1d || alpha == 0) {
                        weight = 0d;
                    } else if (distance <= solidBoundary) {
                        weight = 1d;
                    } else {
                        double t = (1d - distance) / Math.max(0.0001, region.getFeather());
                        // smoothstep 避免羽化边缘出现明显亮度折线
                        weight = t * t * (3d - 2d * t);
                    }
                    int editedPixel = edited.getRGB(x, y);
                    int red = blend((masterPixel >> 16) & 0xFF,
                        (editedPixel >> 16) & 0xFF, weight);
                    int green = blend((masterPixel >> 8) & 0xFF,
                        (editedPixel >> 8) & 0xFF, weight);
                    int blue = blend(masterPixel & 0xFF, editedPixel & 0xFF, weight);
                    output.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
                }
            }
            ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(output, "png", outputBytes);
            return outputBytes.toByteArray();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("表情区域合成失败: " + e.getMessage(), e);
        }
    }

    private static int blend(int base, int edited, double weight) {
        return Math.max(0, Math.min(255,
            (int) Math.round(base + (edited - base) * weight)));
    }

    /** 下载站内或外网图片字节（附件 permalink 是相对路径，用站点外部地址补全） */
    Mono<byte[]> downloadBytes(String url) {
        return Mono.fromSupplier(() -> resolveDownloadUri(url,
                URI.create(externalUrlSupplier.getRaw().toString()), runningServerPort()))
            .flatMap(resolved -> webClient.get()
                .uri(resolved).retrieve()
                .bodyToMono(byte[].class).timeout(java.time.Duration.ofSeconds(60)));
    }

    /** 插件子上下文不一定继承主服务的 server.port，因此沿父链读实际监听端口。 */
    private int runningServerPort() {
        ApplicationContext context = applicationContext;
        while (context != null) {
            if (context instanceof WebServerApplicationContext webContext
                && webContext.getWebServer() != null) {
                int port = webContext.getWebServer().getPort();
                if (port > 0) {
                    return port;
                }
            }
            Integer configured = context.getEnvironment()
                .getProperty("local.server.port", Integer.class);
            if (configured == null) {
                configured = context.getEnvironment().getProperty("server.port", Integer.class);
            }
            if (configured != null && configured > 0) {
                return configured;
            }
            context = context.getParent();
        }
        return -1;
    }

    /**
     * 将站内 permalink 解析为可下载地址。开发环境的系统外部地址可能是
     * {@code http://localhost}（省略端口），此时使用当前 Web Server 端口，避免误连 80。
     */
    static URI resolveDownloadUri(String path, URI externalBase, int serverPort) {
        URI candidate = URI.create(path);
        if (candidate.isAbsolute()) {
            return withLocalServerPort(candidate, serverPort);
        }
        return withLocalServerPort(externalBase, serverPort).resolve(candidate);
    }

    private static URI withLocalServerPort(URI uri, int serverPort) {
        String host = uri.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host)
            || "127.0.0.1".equals(host) || "::1".equals(host);
        if (loopback && uri.getPort() < 0 && serverPort > 0) {
            try {
                return new URI(uri.getScheme(), uri.getUserInfo(), host, serverPort,
                    uri.getPath(), uri.getQuery(), uri.getFragment());
            } catch (URISyntaxException e) {
                throw new IllegalStateException("无法解析站点内部地址", e);
            }
        }
        return uri;
    }

    /**
     * 上传字节到 Halo 附件库，返回访客可访问的 permalink。
     * 走官方 AttachmentService：由它调度 handler、创建附件记录（附件库可见、带 owner）。
     * 注意它要求调用链上有认证上下文（ConsolePetEndpoint 已注入）。
     * 存储策略两档语义：管理员在 chat 配置组指定了策略就严格用它（失败报错不回退，
     * 否则图悄悄落进别的存储，管理员无从知晓）；未指定则本地优先、失败轮其他策略。
     */
    Mono<String> uploadImage(byte[] bytes, String filename) {
        return aiProperties.getChatConfig()
            .flatMap(chat -> {
                String configured = chat.getWidgetPetStoragePolicy();
                if (configured != null && !configured.isBlank()) {
                    return uploadViaPolicy(configured.trim(), bytes, filename)
                        .onErrorResume(e -> Mono.error(new IllegalStateException(
                            "存储策略 " + configured.trim() + " 上传失败："
                                + e.getMessage(), e)));
                }
                return client.listAll(Policy.class, new ListOptions(), Sort.unsorted())
                    .sort(java.util.Comparator
                        .comparingInt(p -> isLocalPolicy(p) ? 0 : 1))
                    .concatMap(policy -> uploadViaPolicy(
                            policy.getMetadata().getName(), bytes, filename)
                        .onErrorResume(e -> {
                            log.warn("策略 {} 上传失败，尝试下一个: {}",
                                policy.getMetadata().getName(), e.getMessage(), e);
                            return Mono.empty();
                        }))
                    .next()
                    .switchIfEmpty(Mono.error(new IllegalStateException(
                        "没有存储策略能接收上传（检查附件插件是否启用）")));
            });
    }

    /** 经指定策略上传并返回 permalink */
    private Mono<String> uploadViaPolicy(String policyName, byte[] bytes, String filename) {
        return petAttachments.upload(policyName, bytes, filename);
    }

    /** 是否本地存储策略（判据与 Halo 核心 LocalAttachmentUploadHandler.shouldHandle 一致） */
    private boolean isLocalPolicy(Policy policy) {
        return policy.getSpec() != null
            && "local".equals(policy.getSpec().getTemplateName());
    }

    /** 统一把生成结果转成字节；请求的是 BASE64，但厂商可能仍回 URL，兜底下载 */
    private Mono<byte[]> firstImageBytes(run.halo.aifoundation.image.GenerateImageResult result) {
        List<GeneratedFile> files = result == null ? null : result.getImages();
        if (files == null || files.isEmpty() || files.getFirst() == null) {
            return Mono.error(new IllegalStateException("图像模型没有返回任何图片"));
        }
        GeneratedFile file = files.getFirst();
        if (file.isBase64() && file.getBase64() != null) {
            return Mono.just(java.util.Base64.getDecoder().decode(file.getBase64()))
                .transform(source -> PetGenerationProgress.track(source, "processing", ""))
                .map(this::stripWhiteBackground);
        }
        if (file.isUrl() && file.getUrl() != null) {
            // 走 downloadBytes，统一用 URI 重载避免预签名 URL 被二次编码
            return downloadBytes(file.getUrl())
                .transform(source -> PetGenerationProgress.track(source, "processing", ""))
                .map(this::stripWhiteBackground);
        }
        return Mono.error(new IllegalStateException("图像模型返回了无法识别的图片格式"));
    }

    /**
     * 把贴纸图的白色背景抠成透明。
     *
     * <p>扩散模型输出不了 alpha 通道（让它画"透明"只会得到棋盘格或纯白），所以 prompt
     * 固定要求纯白背景，生成后在这里程序化扣除。两步：
     * <ol>
     *   <li>从四条边泛洪填充近白像素（≥ 240）——只删「连通到边缘」的白色，
     *       角色内部的白色（白肚皮、高光）不受影响；</li>
     *   <li>边缘羽化：与背景相邻、白度在 205~240 之间的抗锯齿像素按白度给渐变 alpha，
     *       避免硬阈值在轮廓上留下白色毛边。</li>
     * </ol>
     * 任何异常都原样返回原图——透明是增强，不能让生成流程失败。
     */
    private byte[] stripWhiteBackground(byte[] src) {
        try {
            var img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(src));
            if (img == null) {
                return src;
            }
            int w = img.getWidth();
            int h = img.getHeight();
            // 统一转 ARGB（模型给的是无 alpha 的 RGB）
            var argb = new java.awt.image.BufferedImage(w, h,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
            var g = argb.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
            int[] px = argb.getRGB(0, 0, w, h, null, 0, w);
            final int bgThresh = 240;
            final int featherFloor = 205;

            // 第一步：BFS 泛洪，从边缘找连通近白区
            var visited = new java.util.BitSet(w * h);
            var queue = new java.util.ArrayDeque<Integer>();
            for (int x = 0; x < w; x++) {
                queue.add(x);
                queue.add((h - 1) * w + x);
            }
            for (int y = 0; y < h; y++) {
                queue.add(y * w);
                queue.add(y * w + w - 1);
            }
            var background = new java.util.BitSet(w * h);
            while (!queue.isEmpty()) {
                int idx = queue.poll();
                if (idx < 0 || idx >= w * h || visited.get(idx)) {
                    continue;
                }
                visited.set(idx);
                int p = px[idx];
                if (minChannel(p) < bgThresh) {
                    continue; // 非近白：角色本体，泛洪不穿过
                }
                background.set(idx);
                int x = idx % w;
                int y = idx / w;
                if (x > 0) queue.add(idx - 1);
                if (x < w - 1) queue.add(idx + 1);
                if (y > 0) queue.add(idx - w);
                if (y < h - 1) queue.add(idx + w);
            }

            // 第二步：羽化 + 写 alpha
            int[] out = px.clone();
            for (int i = 0; i < px.length; i++) {
                if (background.get(i)) {
                    out[i] = px[i] & 0x00FFFFFF; // 背景全透明
                    continue;
                }
                // 与背景 4 邻接的像素才羽化（不碰角色内部）
                int x = i % w;
                int y = i / w;
                boolean touchesBg = (x > 0 && background.get(i - 1))
                    || (x < w - 1 && background.get(i + 1))
                    || (y > 0 && background.get(i - w))
                    || (y < h - 1 && background.get(i + w));
                if (touchesBg) {
                    int min = minChannel(px[i]);
                    if (min >= featherFloor) {
                        // 205→255（保留），240→0（全透）
                        int alpha = Math.round(255f * (bgThresh - min)
                            / (bgThresh - featherFloor));
                        out[i] = (Math.max(0, Math.min(255, alpha)) << 24)
                            | (px[i] & 0x00FFFFFF);
                    }
                }
            }
            argb.setRGB(0, 0, w, h, out, 0, w);
            var baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(argb, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("白底抠除失败，保留原图: {}", e.getMessage());
            return src;
        }
    }

    private static int minChannel(int argbPixel) {
        int r = (argbPixel >> 16) & 0xFF;
        int g = (argbPixel >> 8) & 0xFF;
        int b = argbPixel & 0xFF;
        return Math.min(r, Math.min(g, b));
    }
}
