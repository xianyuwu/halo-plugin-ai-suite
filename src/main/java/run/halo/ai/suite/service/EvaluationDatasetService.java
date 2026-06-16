package run.halo.ai.suite.service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.ai.suite.extension.EvaluationDataset;
import run.halo.ai.suite.service.EvaluationService.EvaluationCase;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationDatasetService {

    private static final String DEFAULT_DATASET_ID = "blog-knowledge-basic";

    private final ReactiveExtensionClient client;

    public Mono<List<DatasetDto>> listDatasets() {
        return ensureDefaultDataset()
            .then(client.listAll(EvaluationDataset.class, ListOptions.builder().build(),
                    Sort.by(Sort.Order.desc("spec.defaultDataset"), Sort.Order.asc("metadata.name")))
                .map(this::toDto)
                .collectList());
    }

    public Mono<DatasetDto> getDataset(String id) {
        return client.fetch(EvaluationDataset.class, id).map(this::toDto);
    }

    public Mono<DatasetDto> saveDataset(SaveDatasetRequest request) {
        return Mono.fromCallable(() -> {
            String id = request.id() != null && !request.id().isBlank()
                ? request.id() : slugify(request.name());
            EvaluationDataset existing = client.fetch(EvaluationDataset.class, id).block();
            EvaluationDataset dataset = existing != null ? existing : new EvaluationDataset();
            if (existing == null) {
                Metadata md = new Metadata();
                md.setName(id);
                dataset.setMetadata(md);
            }
            dataset.setSpec(toSpec(request, existing == null && DEFAULT_DATASET_ID.equals(id)));
            EvaluationDataset saved = existing == null
                ? client.create(dataset).block()
                : client.update(dataset).block();
            return toDto(saved);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> deleteDataset(String id) {
        if (DEFAULT_DATASET_ID.equals(id)) {
            return Mono.error(new IllegalArgumentException("默认评测集不能删除"));
        }
        return client.fetch(EvaluationDataset.class, id)
            .flatMap(client::delete)
            .then();
    }

    public Mono<DatasetDto> defaultTemplate() {
        return ensureDefaultDataset().then(getDataset(DEFAULT_DATASET_ID));
    }

    private Mono<Void> ensureDefaultDataset() {
        return client.fetch(EvaluationDataset.class, DEFAULT_DATASET_ID)
            .switchIfEmpty(Mono.defer(() -> {
                EvaluationDataset dataset = new EvaluationDataset();
                Metadata md = new Metadata();
                md.setName(DEFAULT_DATASET_ID);
                dataset.setMetadata(md);
                dataset.setSpec(toSpec(new SaveDatasetRequest(
                    DEFAULT_DATASET_ID,
                    "博客知识库基础评测",
                    "覆盖 AI、Kubernetes、NAS、RAG、安全与旅行内容的基础回归评测集。",
                    builtInCases()
                ), true));
                return client.create(dataset)
                    .doOnError(e -> log.warn("[EvaluationDataset] 创建默认评测集失败: {}", e.getMessage()))
                    .onErrorResume(e -> client.fetch(EvaluationDataset.class, DEFAULT_DATASET_ID));
            }))
            .then();
    }

    private EvaluationDataset.Spec toSpec(SaveDatasetRequest request, boolean defaultDataset) {
        EvaluationDataset.Spec spec = new EvaluationDataset.Spec();
        spec.setDisplayName(request.name() != null && !request.name().isBlank()
            ? request.name() : "未命名评测集");
        spec.setDescription(request.description() != null ? request.description() : "");
        spec.setDefaultDataset(defaultDataset);
        spec.setUpdatedAt(Instant.now());
        spec.setCases(request.cases() != null ? request.cases().stream().map(this::toExtCase).toList() : List.of());
        return spec;
    }

    private EvaluationDataset.Case toExtCase(EvaluationCase c) {
        EvaluationDataset.Case ext = new EvaluationDataset.Case();
        ext.setId(c.id());
        ext.setQuestion(c.question());
        ext.setReferenceAnswer(c.referenceAnswer());
        ext.setExpectedSources(c.expectedSources() != null ? c.expectedSources() : List.of());
        ext.setTags(c.tags() != null ? c.tags() : List.of());
        return ext;
    }

    private EvaluationCase fromExtCase(EvaluationDataset.Case c) {
        return new EvaluationCase(c.getId(), c.getQuestion(), c.getReferenceAnswer(),
            c.getExpectedSources() != null ? c.getExpectedSources() : List.of(),
            c.getTags() != null ? c.getTags() : List.of());
    }

    private DatasetDto toDto(EvaluationDataset dataset) {
        EvaluationDataset.Spec spec = dataset.getSpec();
        List<EvaluationCase> cases = spec != null && spec.getCases() != null
            ? spec.getCases().stream().map(this::fromExtCase).collect(Collectors.toList())
            : List.of();
        return new DatasetDto(
            dataset.getMetadata() != null ? dataset.getMetadata().getName() : "",
            spec != null ? spec.getDisplayName() : "",
            spec != null ? spec.getDescription() : "",
            spec != null && Boolean.TRUE.equals(spec.getDefaultDataset()),
            spec != null && spec.getUpdatedAt() != null ? spec.getUpdatedAt().toString() : "",
            cases
        );
    }

    private String slugify(String name) {
        String base = name == null ? "" : name.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        if (base.isBlank()) {
            base = "eval-" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (base.length() > 60) {
            base = base.substring(0, 60).replaceAll("-+$", "");
        }
        return base;
    }

    public record SaveDatasetRequest(
        String id,
        String name,
        String description,
        List<EvaluationCase> cases
    ) {}

    public record DatasetDto(
        String id,
        String name,
        String description,
        boolean defaultDataset,
        String updatedAt,
        List<EvaluationCase> cases
    ) {}

    public static List<EvaluationCase> builtInCases() {
        return List.of(
            new EvaluationCase("case-1", "AI 智能体和传统大模型最大的区别是什么？", "应说明 AI 智能体相比传统大模型，核心区别在于自主性和行动能力，能够感知环境、规划步骤、调用工具并完成复杂任务。", List.of("AI 智能体：从 \"对话工具\" 到 \"自主行动者\" 的范式革命"), List.of("AI", "RAG", "事实问答")),
            new EvaluationCase("case-2", "一个完整的 AI 智能体通常包含哪些核心模块？", "应提到感知模块、规划模块、行动模块、反思模块，并简要说明各自作用。", List.of("AI 智能体：从 \"对话工具\" 到 \"自主行动者\" 的范式革命"), List.of("AI", "事实问答")),
            new EvaluationCase("case-3", "文章认为 AI 智能体当前面临哪些主要挑战？", "应提到幻觉问题、安全性和可控性，以及广泛应用带来的社会变革。", List.of("AI 智能体：从 \"对话工具\" 到 \"自主行动者\" 的范式革命"), List.of("AI", "风险", "忠实度")),
            new EvaluationCase("case-4", "文章里提到群晖 NAS 暴露到互联网主要有哪些方式？", "应提到 QuickConnect、DDNS、内网穿透三种方式，并说明作者最终选择了基于 frp 的内网穿透方案。", List.of("群晖 NAS 通过 frp 实现内网穿透"), List.of("NAS", "frp", "事实问答")),
            new EvaluationCase("case-5", "这篇文章里的 frp 内网穿透架构是怎么设计的？", "应说明统一使用 nas.domain.com，frps 部署在公有云云主机，frpc 部署在群晖 NAS Docker 容器里，内网通过本地 DNS 解析到 NAS，外网解析到云主机。", List.of("群晖 NAS 通过 frp 实现内网穿透"), List.of("NAS", "frp", "综合理解")),
            new EvaluationCase("case-6", "frp 配置里主要代理了哪些群晖相关端口？", "应提到 5001、6690、1194、1195，分别用于 DSM、Synology Drive、OpenVPN 等服务。", List.of("群晖 NAS 通过 frp 实现内网穿透"), List.of("NAS", "端口", "事实问答")),
            new EvaluationCase("case-7", "在 KubeSphere 上部署 Halo 时，文章采用了哪些核心组件？", "应说明采用 Halo + MySQL + Redis 组合，Halo 作为前端服务，MySQL 作为数据库，Redis 作为缓存。", List.of("使用KubeSphere部署Halo开源博客系统"), List.of("Halo", "KubeSphere", "架构")),
            new EvaluationCase("case-8", "Halo 服务连接 MySQL 和 Redis 的密码在 KubeSphere 里是怎么管理的？", "应说明通过创建 halo-secret 保存 SPRING_DATASOURCE_PASSWORD 和 SPRING_REDIS_PASSWORD，避免明文使用密码。", List.of("使用KubeSphere部署Halo开源博客系统"), List.of("Halo", "KubeSphere", "安全")),
            new EvaluationCase("case-9", "为什么大规模 Calico 集群建议使用 Route Reflectors？", "应说明 Full-mesh 在节点规模变大后 BGP 对等连接数量过多、效率下降，Route Reflectors 可以减少每个节点上的 BGP 对等体数量。", List.of("如何在Kubernetes中配置Calico路由反射器"), List.of("Kubernetes", "Calico", "BGP")),
            new EvaluationCase("case-10", "文章修正后认为 Calico RR 节点和 leaf 交换机之间应该建立什么 BGP 关系？", "应回答应建立 iBGP 连接，而不是 eBGP；Calico RR 主要作为同步路由的角色。", List.of("如何在Kubernetes中配置Calico路由反射器"), List.of("Calico", "iBGP", "事实问答")),
            new EvaluationCase("case-11", "DeepSeek-R1 私有化部署文章中，本地 RAG 知识库用了哪些主要工具？", "应提到 MacBook Pro、Ollama、DeepSeek-R1、Cherry Studio，以及 nomic-embed-text 作为嵌入模型。", List.of("DeepSeek-R1私有化部署实战：搭建RAG知识库"), List.of("DeepSeek", "RAG", "本地部署")),
            new EvaluationCase("case-12", "为什么基于 RAG 的知识库要使用文本嵌入模型？", "应说明嵌入模型可把文本转成低维向量，通过向量相似度快速匹配知识库内容，降低逐条遍历成本，并通过检索相关文档降低幻觉。", List.of("DeepSeek-R1私有化部署实战：搭建RAG知识库"), List.of("RAG", "Embedding", "原理")),
            new EvaluationCase("case-13", "文章中介绍了哪些主要国密算法？", "应提到 SM1、SM2、SM3、SM4、SM7、SM9，并能区分对称加密、非对称加密、哈希、基于身份加密等类型。", List.of("国密算法"), List.of("国密", "安全", "事实问答")),
            new EvaluationCase("case-14", "SM2、SM3、SM4 分别适合做什么？", "SM2 是基于椭圆曲线的非对称加密算法，支持加密、签名和密钥交换；SM3 是哈希算法，用于完整性校验和数字签名；SM4 是对称加密算法，适用于无线通信、物联网、金融数据加密等。", List.of("国密算法"), List.of("国密", "对比")),
            new EvaluationCase("case-15", "2022 年海南环岛自驾游一共持续了多久？为什么没有去三亚？", "应回答行程从 2022 年 3 月 5 日到 3 月 19 日，历时 15 天；因为当时三亚爆发疫情，为保证行程顺利避开了三亚。", List.of("2022年海南环岛自驾游（Day1）"), List.of("旅行", "海南", "事实问答")),
            new EvaluationCase("case-16", "博客里有没有提到作者用 frp 部署了 Kubernetes Ingress Controller？", "应回答当前资料没有明确提到“用 frp 部署 Kubernetes Ingress Controller”，不能编造。可以说明 frp 文章主要讲群晖 NAS 内网穿透，Kubernetes Ingress 是另一篇文章主题。", List.of("群晖 NAS 通过 frp 实现内网穿透", "在k8s里配置ingress应用路由强制跳转至https"), List.of("无答案", "防幻觉", "跨主题")),
            new EvaluationCase("case-17", "博客里关于 Halo 的部署文章，KubeSphere 部署和 yaml 原生 k8s 部署有什么主题差异？", "应区分两篇文章：KubeSphere 文章重点讲在 KubeSphere 平台上通过应用、服务、密钥、配置字典、存储卷部署 Halo + MySQL + Redis；yaml 原生 k8s 文章应是通过 YAML 在原生 Kubernetes 上部署 Halo。不能把两篇混成一篇。", List.of("使用KubeSphere部署Halo开源博客系统", "通过yaml在原生k8s上部署halo开源博客系统"), List.of("Halo", "多文章", "区分能力")),
            new EvaluationCase("case-18", "Harness Engineering 文章里为什么说 Prompt 不是人格，而是控制面？", "应说明对能读写文件、执行命令、跨轮工作的 Agent 来说，Prompt 需要定义运行时协议、权限边界、停止条件、记忆治理和工程纪律，而不是简单的人设文本。", List.of("Harness Engineering 实操教程"), List.of("Agent", "Harness", "综合理解"))
        );
    }
}
