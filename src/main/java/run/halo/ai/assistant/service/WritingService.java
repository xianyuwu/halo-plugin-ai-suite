package run.halo.ai.assistant.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.ai.assistant.config.AIProperties;
import run.halo.ai.assistant.llm.LlmClient;

import java.util.List;
import java.util.Map;

/**
 * AI 写作辅助服务 — 续写、润色、翻译等
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WritingService {

    private final LlmClient llmClient;
    private final AIProperties aiProperties;

    /**
     * AI 写作辅助（非流式）— 根据操作类型返回结果
     */
    public Mono<String> assist(String text, String action, String instruction) {
        if (text == null || text.isBlank()) {
            return Mono.just("请输入需要处理的文本");
        }

        String prompt = buildActionPrompt(text, action, instruction);

        return aiProperties.getModelConfig()
            .flatMap(modelConfig ->
                aiProperties.getChatConfig()
                    .flatMap(chatConfig -> {
                        List<Map<String, String>> messages = List.of(
                            Map.of("role", "user", "content", prompt)
                        );
                        return llmClient.chat(
                            modelConfig.getChatBaseUrl(),
                            modelConfig.getChatApiKey(),
                            modelConfig.getChatModel(),
                            messages,
                            0.5f,
                            2048
                        );
                    })
            );
    }

    /**
     * AI 写作辅助（流式）— 返回 SSE token 流
     */
    public Flux<String> assistStream(String text, String action, String instruction) {
        if (text == null || text.isBlank()) {
            return Flux.just("请输入需要处理的文本");
        }

        String prompt = buildActionPrompt(text, action, instruction);

        return aiProperties.getModelConfig()
            .flatMapMany(modelConfig ->
                aiProperties.getChatConfig()
                    .flatMapMany(chatConfig -> {
                        List<Map<String, String>> messages = List.of(
                            Map.of("role", "user", "content", prompt)
                        );
                        return llmClient.chatStream(
                            modelConfig.getChatBaseUrl(),
                            modelConfig.getChatApiKey(),
                            modelConfig.getChatModel(),
                            messages,
                            0.5f,
                            2048
                        );
                    })
            );
    }

    /**
     * 根据操作类型构建 prompt
     */
    private String buildActionPrompt(String text, String action, String instruction) {
        // 自定义指令优先
        if (instruction != null && !instruction.isBlank()) {
            return instruction + "\n\n原文：\n" + text;
        }

        return switch (action != null ? action.trim() : "polish") {
            case "continue" -> "请续写以下文本，保持相同的风格和语气。直接输出续写内容，不要加前缀：\n\n" + text;
            case "polish" -> "请润色以下文本，使其更流畅、更有文采，但保持原意不变。直接输出润色后的全文，不要加前缀：\n\n" + text;
            case "translate-en" -> "请将以下文本翻译成英文。直接输出翻译结果，不要加前缀：\n\n" + text;
            case "summarize" -> "请为以下文本生成一段简洁的摘要。直接输出摘要，不要加前缀：\n\n" + text;
            case "expand" -> "请扩写以下文本，增加更多细节和论述。直接输出扩写后的全文：\n\n" + text;
            case "simplify" -> "请将以下文本简化，用更通俗易懂的语言表达。直接输出简化后的全文：\n\n" + text;
            default -> "请润色以下文本，使其更流畅。直接输出结果：\n\n" + text;
        };
    }
}
