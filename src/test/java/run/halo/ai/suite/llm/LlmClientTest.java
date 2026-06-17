package run.halo.ai.suite.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * LlmClient 纯逻辑单测 — normalizeBaseUrl 规范化 + estimateTokens 估算.
 * 不涉及 HTTP/网络, 只测参数→结果的映射.
 */
class LlmClientTest {

    // ===== normalizeBaseUrl =====

    @Test
    void appendsV1WhenMissing() {
        assertThat(LlmClient.normalizeBaseUrl("https://api.deepseek.com"))
            .isEqualTo("https://api.deepseek.com/v1");
    }

    @Test
    void keepsAsIsWhenV1AlreadyPresent() {
        assertThat(LlmClient.normalizeBaseUrl("https://api.deepseek.com/v1"))
            .isEqualTo("https://api.deepseek.com/v1");
        assertThat(LlmClient.normalizeBaseUrl("https://api.deepseek.com/v1/"))
            .isEqualTo("https://api.deepseek.com/v1/");
    }

    @Test
    void keepsAsIsWhenCustomVersionPath() {
        // dashscope 用 /api/v1 服务于 paas 路径, 不应重复追加
        assertThat(LlmClient.normalizeBaseUrl("https://dashscope.aliyuncs.com/api/v1"))
            .isEqualTo("https://dashscope.aliyuncs.com/api/v1");
    }

    @Test
    void stripsTrailingSlashBeforeAppend() {
        assertThat(LlmClient.normalizeBaseUrl("https://api.example.com/"))
            .isEqualTo("https://api.example.com/v1");
    }

    @Test
    void returnsAsIsForBlankOrNull() {
        assertThat(LlmClient.normalizeBaseUrl("")).isEmpty();
        assertThat(LlmClient.normalizeBaseUrl("   ")).isEqualTo("   ");
        assertThat(LlmClient.normalizeBaseUrl(null)).isNull();
    }

    @Test
    void ssrfRejectsLocalhost() {
        assertThatThrownBy(() -> LlmClient.normalizeBaseUrl("http://localhost:8080/v1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("内网");
    }

    @Test
    void ssrfRejectsMetadataAddress() {
        assertThatThrownBy(() -> LlmClient.normalizeBaseUrl("http://169.254.169.254/latest/meta-data"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("内网");
    }

    @Test
    void ssrfRejectsPrivateRange() {
        assertThatThrownBy(() -> LlmClient.normalizeBaseUrl("http://10.0.0.1/v1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LlmClient.normalizeBaseUrl("http://192.168.1.1/v1"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ssrfAllowsPublicDomain() {
        // 公网域名不应抛异常, 且缺 /v1 时追加
        assertThat(LlmClient.normalizeBaseUrl("https://api.openai.com"))
            .isEqualTo("https://api.openai.com/v1");
    }

    @Test
    void ssrfRejectsNonHttpScheme() {
        assertThatThrownBy(() -> LlmClient.normalizeBaseUrl("ftp://example.com/v1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("http/https");
    }

    // ===== estimateTokens =====

    @Test
    void emptyStringIsZero() {
        assertThat(new LlmClient(null, null, null).estimateTokens("")).isEqualTo(0L);
        assertThat(new LlmClient(null, null, null).estimateTokens(null)).isEqualTo(0L);
    }

    @Test
    void estimatesCjkTokens() {
        // 纯中文 6 字符, 1 token ≈ 1.5 chars → 6/1.5 = 4 tokens
        LlmClient client = new LlmClient(null, null, null);
        long tokens = client.estimateTokens("你好世界测试");
        assertThat(tokens).isEqualTo(4L);
    }

    @Test
    void estimatesAsciiTokens() {
        // 纯英文 8 字符, 1 token = 4 chars → 8/4 = 2 tokens
        LlmClient client = new LlmClient(null, null, null);
        long tokens = client.estimateTokens("abcdefgh");
        assertThat(tokens).isEqualTo(2L);
    }

    @Test
    void mixedContentEstimatedReasonably() {
        LlmClient client = new LlmClient(null, null, null);
        // 中文+英文混合, 估算应 > 0 且不过分偏离
        long tokens = client.estimateTokens("Hello世界ABC测试");
        assertThat(tokens).isGreaterThan(0L).isLessThan(20L);
    }

    @Test
    void estimateMessageTokensIncludesOverhead() {
        LlmClient client = new LlmClient(null, null, null);
        // 单条消息, content=8 ascii(2 token) + role=4 ascii(1 token) + overhead 4 = 7
        long tokens = client.estimateMessageTokens(List.of(
            Map.of("role", "user", "content", "abcdefgh")
        ));
        // role "user"(4字符=1) + content(2) + overhead(4) = 7
        assertThat(tokens).isEqualTo(7L);
    }

    @Test
    void estimateMessageTokensEmptyIsZero() {
        LlmClient client = new LlmClient(null, null, null);
        assertThat(client.estimateMessageTokens(null)).isEqualTo(0L);
        assertThat(client.estimateMessageTokens(List.of())).isEqualTo(0L);
    }
}
