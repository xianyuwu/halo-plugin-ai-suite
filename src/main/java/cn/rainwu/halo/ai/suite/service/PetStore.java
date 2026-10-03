package cn.rainwu.halo.ai.suite.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * AI 贴纸宠物清单的持久化。
 *
 * <p>宠物记录存在主 ConfigMap（{@code ai-suite-configmap}）的 {@code data.pets} 里，
 * 值是一个 JSON 数组。量级很小（管理员手工生成的几个宠物），不值得注册新 CRD——
 * 也规避了插件主类注释里提到的 Scheme classloader 坑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PetStore {

    private static final String CONFIG_MAP_NAME = "ai-suite-configmap";
    private static final String GROUP_KEY = "pets";
    /** 宠物数量上限：防止 ConfigMap 无节制膨胀（attachment  permalink 不长，但要有边界） */
    private static final int MAX_PETS = 20;

    private final ReactiveExtensionClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Data
    public static class PetRecord {
        private String id;
        private String name;
        /** 画风标识：soft-3d / chibi / flat / pixel */
        private String style;
        /** New pixel pipeline grid; absent on historical images. */
        private Integer pixelGridSize;
        private long createdAt;
        /** 状态 → 图片 permalink；状态名与 widget 端状态契约一致 */
        private Map<String, String> images = new LinkedHashMap<>();
        /** 管理员对母版的补充要求，系统质量约束不允许被覆盖 */
        private String customPrompt;
        /** 本次母版生成实际使用的完整提示词，用于复现与审计 */
        private String promptSnapshot;
        /** 四种表情共用的补充要求 */
        private String expressionPrompt;
        /** 状态 → 本次生成实际使用的完整提示词 */
        private Map<String, String> expressionPromptSnapshots = new LinkedHashMap<>();
        /** 手动清理背景残留的归一化橡皮擦笔画，可在后续重做表情时复现 */
        private List<EraseStroke> backgroundCleanupStrokes = new ArrayList<>();
        /** 初次生成的母版地址；手动清理后可恢复，旧记录可能为空 */
        private String originalMasterUrl;
        /** 当前经过背景清理的母版地址；未清理时与 idle 相同 */
        private String cleanedMasterUrl;
        /** pending / approved；旧记录为空时按 approved 兼容 */
        private String backgroundStatus;
        /** 每次清理或恢复递增，便于 Console 刷新图片缓存 */
        private int cleanupRevision;
        /** 背景审核通过时间（Unix 秒）；旧记录可能为空 */
        private Long backgroundApprovedAt;
        /** 表情合成区域；旧记录为空时由生成服务使用安全默认值 */
        private ExpressionRegion expressionRegion;
        private String expressionMode;
        /** Pending frames are never served by the public widget until explicitly approved. */
        private Map<String, ExpressionCandidate> expressionCandidates = new LinkedHashMap<>();
        /** Last approved public version; draft changes never replace it. */
        private PublishedVersion publishedVersion;

        public PublishedVersion publicVersion() {
            if (publishedVersion != null) return publishedVersion;
            return PetGeneratorService.isBackgroundApproved(this) ? snapshot() : null;
        }

        public PublishedVersion snapshot() {
            PublishedVersion version = new PublishedVersion();
            version.setName(name);
            version.setStyle(style);
            version.setPixelGridSize(pixelGridSize);
            version.setImages(new LinkedHashMap<>(images));
            if (expressionRegion != null) {
                ExpressionRegion region = new ExpressionRegion();
                region.setCenterX(expressionRegion.getCenterX());
                region.setCenterY(expressionRegion.getCenterY());
                region.setWidth(expressionRegion.getWidth());
                region.setHeight(expressionRegion.getHeight());
                region.setFeather(expressionRegion.getFeather());
                version.setExpressionRegion(region);
            }
            return version;
        }

        void preservePublishedVersion() {
            if (publishedVersion == null && PetGeneratorService.isBackgroundApproved(this)) {
                publishedVersion = snapshot();
            }
        }

        void publish() { publishedVersion = snapshot(); }
    }

    @Data
    public static class PublishedVersion {
        private String name;
        private String style;
        private Integer pixelGridSize;
        private Map<String, String> images = new LinkedHashMap<>();
        private ExpressionRegion expressionRegion;
    }

    @Data
    public static class ExpressionCandidate {
        private boolean editingExisting;
        private String imageUrl;
        private String originalUrl;
        private String masterUrl;
        private int masterRevision;
        private String mode;
        private String prompt;
    }

    @Data
    public static class ExpressionRegion {
        /** 以下数值均为相对画布的 0~1 比例 */
        private double centerX = 0.5;
        private double centerY = 0.35;
        private double width = 0.48;
        private double height = 0.38;
        private double feather = 0.12;
    }

    @Data
    public static class EraseStroke {
        /** 圆心和半径都是相对画布的 0~1 比例 */
        private double x;
        private double y;
        private double radius = 0.04;
    }

    public Mono<List<PetRecord>> list() {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .map(cm -> parse(cm.getData() == null ? null : cm.getData().get(GROUP_KEY)))
            .defaultIfEmpty(List.of());
    }

    public Mono<PetRecord> get(String id) {
        return list().flatMap(pets -> Mono.justOrEmpty(pets.stream()
            .filter(p -> java.util.Objects.equals(p.id, id)).findFirst()));
    }

    public Mono<PetRecord> add(PetRecord record) {
        return mutate(pets -> {
            if (pets.size() >= MAX_PETS) {
                return Mono.error(new IllegalStateException(
                    "宠物数量已达上限 " + MAX_PETS + " 个，请先删除不需要的宠物"));
            }
            pets.add(record);
            return Mono.just(record);
        });
    }

    /** 原子替换同 ID 记录，避免表情重生成使用 remove+add 暴露短暂空窗。 */
    public Mono<PetRecord> update(PetRecord record) {
        return mutate(pets -> {
            for (int i = 0; i < pets.size(); i++) {
                if (pets.get(i).id.equals(record.id)) {
                    pets.set(i, record);
                    return Mono.just(record);
                }
            }
            return Mono.error(new IllegalArgumentException("宠物不存在: " + record.id));
        });
    }

    public Mono<PetRecord> modify(String id, java.util.function.Consumer<PetRecord> change) {
        return mutate(pets -> {
            PetRecord record = pets.stream().filter(p -> id.equals(p.id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("宠物不存在: " + id));
            change.accept(record);
            return Mono.just(record);
        });
    }

    public Mono<Void> remove(String id) {
        return mutate(pets -> {
            pets.removeIf(p -> p.id.equals(id));
            return Mono.empty();
        }).then();
    }

    /**
     * 读-改-写整个 pets 数组。ConfigMap 更新带乐观锁，并发冲突时 refetch 重试。
     *
     * <p>注意 Reactor 陷阱：mutation 完成但<b>不发出值</b>时（如 remove 返回
     * {@code Mono.empty()}），后续 flatMap 不会执行——write 必须用 switchIfEmpty
     * 兜底，否则删除会静默失效（接口报成功、数据没动）。这里曾经就这么坏过。
     */
    private <T> Mono<T> mutate(PetsMutation<T> mutation) {
        return Mono.defer(() -> client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
                // Optional 包装让「ConfigMap 不存在」和「存在但 mutation 空完成」
                // 区分开：前者走 defaultIfEmpty，后者绝不能再落进外层 switchIfEmpty
                .map(java.util.Optional::ofNullable)
                .defaultIfEmpty(java.util.Optional.empty())
                .flatMap(cmOpt -> {
                    ConfigMap cm = cmOpt.orElseGet(() -> {
                        ConfigMap created = new ConfigMap();
                        created.setMetadata(new Metadata());
                        created.getMetadata().setName(CONFIG_MAP_NAME);
                        created.setData(new LinkedHashMap<>());
                        return created;
                    });
                    List<PetRecord> pets = new ArrayList<>(parse(
                        cm.getData() == null ? null : cm.getData().get(GROUP_KEY)));
                    return mutation.apply(pets)
                        .flatMap(result -> write(cm, pets).thenReturn(result))
                        .switchIfEmpty(Mono.defer(() ->
                            write(cm, pets).then(Mono.empty())));
                }))
            .retryWhen(Retry.maxInARow(3)
                .filter(OptimisticLockingFailureException.class::isInstance));
    }

    private Mono<?> write(ConfigMap cm, List<PetRecord> pets) {
        try {
            Map<String, String> data = cm.getData() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(cm.getData());
            data.put(GROUP_KEY, objectMapper.writeValueAsString(pets));
            cm.setData(data);
        } catch (Exception e) {
            return Mono.error(e);
        }
        boolean isNew = cm.getMetadata().getVersion() == null;
        return isNew ? client.create(cm) : client.update(cm);
    }

    private List<PetRecord> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<PetRecord> result = new ArrayList<>();
            JsonNode array = objectMapper.readTree(json);
            if (!array.isArray()) throw new IllegalStateException("宠物配置必须为数组");
            if (array.isArray()) {
                for (JsonNode node : array) {
                    result.add(objectMapper.treeToValue(node, PetRecord.class));
                }
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("宠物配置损坏，已停止读写以保留原始数据，请恢复配置后重试", e);
        }
    }

    @FunctionalInterface
    private interface PetsMutation<T> {
        Mono<T> apply(List<PetRecord> pets);
    }
}
