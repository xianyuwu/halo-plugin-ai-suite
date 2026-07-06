package cn.rainwu.halo.ai.suite.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import cn.rainwu.halo.ai.suite.llm.LlmClient.StreamEvent;

class InlineReasoningParserTest {

    @Test
    void convertsSplitThinkTagsToReasoningEvents() {
        List<StreamEvent> events = InlineReasoningParser.parse(Flux.just(
                StreamEvent.text("<th"), StreamEvent.text("ink>secret</thi"),
                StreamEvent.text("nk>answer")))
            .collectList().block();

        assertThat(events).extracting(StreamEvent::type).containsExactly(
            StreamEvent.REASONING_START, StreamEvent.REASONING_DELTA,
            StreamEvent.REASONING_END, StreamEvent.TEXT);
        assertThat(events).extracting(StreamEvent::content)
            .containsExactly("", "secret", "", "answer");
    }
}
