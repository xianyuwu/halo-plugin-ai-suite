package run.halo.ai.suite.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * LlmClient 安全相关单测 — 覆盖:
 * 1) sanitizeErrorBody 脱敏: 抹除错误信息里的 API Key 痕迹, 防 leak 到用量记录/日志
 * 2) isInternalHost SSRF 判断: 识别内网/本机/元数据地址
 */
class LlmClientSecurityTest {

    // ===== 脱敏测试 =====

    @Test
    void masksSkPrefixedKey() {
        String input = "Invalid API key: sk-abc123def456ghi789jkl";
        String out = LlmClient.sanitizeErrorBody(input);
        // 核心断言: 明文 key 不应出现在脱敏后的输出里
        assertThat(out).doesNotContain("sk-abc123def456ghi789jkl");
        assertThat(out).doesNotContain("abc123def456");
        assertThat(out).contains("***").as("应有脱敏标记");
    }

    @Test
    void masksBearerToken() {
        String input = "Authorization: Bearer eyJhbGciOiJIUzI1.keypart.sign";
        String out = LlmClient.sanitizeErrorBody(input);
        // 核心断言: token 明文不应出现
        assertThat(out).doesNotContain("eyJhbGciOiJIUzI1");
        assertThat(out).doesNotContain("keypart");
        assertThat(out).contains("Bearer ***").as("Bearer 后的 token 应被完全抹除");
    }

    @Test
    void masksKeyInJsonField() {
        String input = "{\"error\":\"auth failed\",\"api_key\":\"secretkey123456\"}";
        String out = LlmClient.sanitizeErrorBody(input);
        assertThat(out).doesNotContain("secretkey123456");
    }

    @Test
    void masksAuthorizationHeader() {
        String input = "authorization: Bearer sk-test1234567890";
        String out = LlmClient.sanitizeErrorBody(input);
        assertThat(out).doesNotContain("sk-test1234567890");
    }

    @Test
    void preservesNormalErrorMessage() {
        String input = "HTTP 429: Too Many Requests. Please retry later.";
        String out = LlmClient.sanitizeErrorBody(input);
        assertThat(out).isEqualTo(input).as("正常错误信息不应被误改");
    }

    @Test
    void handlesNullAndEmpty() {
        assertThat(LlmClient.sanitizeErrorBody(null)).isNull();
        assertThat(LlmClient.sanitizeErrorBody("")).isEmpty();
    }

    @Test
    void doesNotMaskShortStrings() {
        // 短于 8 字符的不像 key, 不应误伤
        String input = "model: gpt-4";
        String out = LlmClient.sanitizeErrorBody(input);
        assertThat(out).isEqualTo(input);
    }

    // ===== SSRF 判断测试 =====

    @Test
    void detectsLoopbackAddresses() {
        assertThat(LlmClient.isInternalHost("127.0.0.1")).isTrue();
        assertThat(LlmClient.isInternalHost("127.0.0.1")).isTrue();
        assertThat(LlmClient.isInternalHost("127.255.255.255")).isTrue();
    }

    @Test
    void detectsPrivateRanges() {
        // 10/8
        assertThat(LlmClient.isInternalHost("10.0.0.1")).isTrue();
        assertThat(LlmClient.isInternalHost("10.255.255.255")).isTrue();
        // 172.16/12
        assertThat(LlmClient.isInternalHost("172.16.0.1")).isTrue();
        assertThat(LlmClient.isInternalHost("172.31.255.255")).isTrue();
        // 192.168/16
        assertThat(LlmClient.isInternalHost("192.168.1.1")).isTrue();
        assertThat(LlmClient.isInternalHost("192.168.0.0")).isTrue();
    }

    @Test
    void detectsLinkLocalAndMetadata() {
        // 169.254/16, 含云元数据地址
        assertThat(LlmClient.isInternalHost("169.254.169.254")).isTrue();
        assertThat(LlmClient.isInternalHost("169.254.0.1")).isTrue();
    }

    @Test
    void detectsLocalhostDomain() {
        assertThat(LlmClient.isInternalHost("localhost")).isTrue();
        assertThat(LlmClient.isInternalHost("sub.localhost")).isTrue();
        assertThat(LlmClient.isInternalHost("foo.local")).isTrue();
        assertThat(LlmClient.isInternalHost("metadata.google.internal")).isTrue();
    }

    @Test
    void allowsPublicDomains() {
        assertThat(LlmClient.isInternalHost("api.openai.com")).isFalse();
        assertThat(LlmClient.isInternalHost("api.deepseek.com")).isFalse();
        assertThat(LlmClient.isInternalHost("dashscope.aliyuncs.com")).isFalse();
    }

    @Test
    void allowsPublicIps() {
        assertThat(LlmClient.isInternalHost("8.8.8.8")).isFalse();
        assertThat(LlmClient.isInternalHost("1.1.1.1")).isFalse();
    }

    @Test
    void detectsIpv6LoopbackAndPrivate() {
        assertThat(LlmClient.isInternalHost("::1")).isTrue();
        assertThat(LlmClient.isInternalHost("fc00::1")).isTrue();
        assertThat(LlmClient.isInternalHost("fd12:3456::1")).isTrue();
    }

    @Test
    void unresolvableDomainNotBlocked() {
        // 无法解析的域名不拦截(可能是有效公网域名, DNS 暂时不可达)
        assertThat(LlmClient.isInternalHost("nonexistent.invalid.tld.example")).isFalse();
    }
}
