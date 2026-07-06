package cn.rainwu.halo.ai.suite.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import reactor.core.publisher.Flux;
import cn.rainwu.halo.ai.suite.llm.LlmClient.StreamEvent;

/** Converts inline {@code <think>} / {@code <reasoning>} blocks into typed stream events. */
final class InlineReasoningParser {

    private static final List<String> OPEN = List.of("<think>", "<reasoning>");
    private static final List<String> CLOSE = List.of("</think>", "</reasoning>");
    private final StringBuilder pending = new StringBuilder();
    private boolean reasoning;

    static Flux<StreamEvent> parse(Flux<StreamEvent> source) {
        return Flux.defer(() -> {
            InlineReasoningParser parser = new InlineReasoningParser();
            return source.concatMap(event -> {
                    if (event == null || !event.isText()) {
                        return Flux.just(event);
                    }
                    return Flux.fromIterable(parser.accept(event.content()));
                })
                .concatWith(Flux.defer(() -> Flux.fromIterable(parser.finish())));
        });
    }

    List<StreamEvent> accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) return List.of();
        pending.append(chunk);
        List<StreamEvent> output = new ArrayList<>();
        while (!pending.isEmpty()) {
            List<String> tags = reasoning ? CLOSE : OPEN;
            Match match = firstMatch(pending, tags);
            if (match != null) {
                emit(output, pending.substring(0, match.index()));
                pending.delete(0, match.index() + match.tag().length());
                reasoning = !reasoning;
                output.add(new StreamEvent(reasoning
                    ? StreamEvent.REASONING_START : StreamEvent.REASONING_END, ""));
                continue;
            }
            int suffix = possiblePrefix(pending, tags);
            int safe = pending.length() - suffix;
            if (safe > 0) {
                emit(output, pending.substring(0, safe));
                pending.delete(0, safe);
            }
            break;
        }
        return output;
    }

    List<StreamEvent> finish() {
        List<StreamEvent> output = new ArrayList<>();
        if (!reasoning) emit(output, pending.toString());
        pending.setLength(0);
        if (reasoning) {
            reasoning = false;
            output.add(new StreamEvent(StreamEvent.REASONING_END, ""));
        }
        return output;
    }

    private void emit(List<StreamEvent> output, String value) {
        if (value == null || value.isEmpty()) return;
        output.add(reasoning
            ? new StreamEvent(StreamEvent.REASONING_DELTA, value)
            : StreamEvent.text(value));
    }

    private static Match firstMatch(CharSequence value, List<String> tags) {
        String lower = value.toString().toLowerCase(Locale.ROOT);
        Match first = null;
        for (String tag : tags) {
            int index = lower.indexOf(tag);
            if (index >= 0 && (first == null || index < first.index())) first = new Match(index, tag);
        }
        return first;
    }

    private static int possiblePrefix(CharSequence value, List<String> tags) {
        String lower = value.toString().toLowerCase(Locale.ROOT);
        int maximum = 0;
        for (String tag : tags) {
            for (int length = Math.min(lower.length(), tag.length() - 1); length > maximum; length--) {
                if (lower.regionMatches(lower.length() - length, tag, 0, length)) {
                    maximum = length;
                    break;
                }
            }
        }
        return maximum;
    }

    private record Match(int index, String tag) { }
}
