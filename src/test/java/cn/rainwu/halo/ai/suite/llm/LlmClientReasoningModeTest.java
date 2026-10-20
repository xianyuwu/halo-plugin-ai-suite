package cn.rainwu.halo.ai.suite.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rainwu.halo.ai.suite.endpoint.UsageLimit.LimitDecision;
import cn.rainwu.halo.ai.suite.state.LimitGuard;
import cn.rainwu.halo.ai.suite.state.UsageTracker;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class LlmClientReasoningModeTest {

    @Test
    void explicitReasoningModeStillChecksAndSettlesUsageLimit() {
        AiFoundationClient foundation = mock(AiFoundationClient.class);
        UsageTracker usage = mock(UsageTracker.class);
        LimitGuard guard = mock(LimitGuard.class);
        LlmClient client = new LlmClient(foundation, usage, guard);
        when(foundation.chatUsageModelName("chat-model")).thenReturn(Mono.just("chat-model"));
        when(guard.check("chat-model", "chat", "visitor-ip"))
            .thenReturn(Mono.just(LimitDecision.allowWithReservation(1000)));
        when(foundation.chat(eq("chat-model"), anyList(), eq(0.0f), eq(2048),
            isNull(), eq(UsageScenario.INTENT_PIPELINE), eq("disabled")))
            .thenReturn(Mono.just("{\"related\":[1]}"));

        String answer = client.chat("chat-model", List.of(Map.of("role", "user",
                "content", "topic")), 0.0f, 2048, null, "visitor-ip",
            UsageScenario.INTENT_PIPELINE, "disabled").block();

        assertThat(answer).isEqualTo("{\"related\":[1]}");
        verify(guard).check("chat-model", "chat", "visitor-ip");
        verify(usage).settle("chat-model", 1000);
    }
}
