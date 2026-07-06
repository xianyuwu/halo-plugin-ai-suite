package cn.rainwu.halo.ai.suite.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ThinkContentFilterTest {

    @Test
    void stripsCompleteReasoningBlocksFromText() {
        assertThat(ThinkContentFilter.strip(
            "before<THINK>secret</THINK>answer<reasoning>hidden</reasoning>end"))
            .isEqualTo("beforeanswerend");
    }

    @Test
    void stripsTagsSplitAcrossArbitraryChunks() {
        List<String> output = ThinkContentFilter.filter(Flux.just(
                "<th", "ink>private", " chain</thi", "nk>", "final ", "answer"))
            .collectList()
            .block();

        assertThat(String.join("", output)).isEqualTo("final answer");
    }

    @Test
    void discardsUnclosedReasoningAtCompletion() {
        assertThat(ThinkContentFilter.strip("visible<think>never expose this"))
            .isEqualTo("visible");
    }

    @Test
    void preservesOrdinaryAngleBracketTextAndPartialTag() {
        assertThat(ThinkContentFilter.strip("Use <strong>HTML</strong> and <thi"))
            .isEqualTo("Use <strong>HTML</strong> and <thi");
    }
}
