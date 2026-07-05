package run.halo.ai.suite.llm;

import java.util.List;
import java.util.Locale;
import reactor.core.publisher.Flux;

/**
 * Removes provider-specific inline reasoning blocks from model text.
 *
 * <p>The streaming parser deliberately keeps possible tag prefixes between chunks, so tags such
 * as {@code <think>} and {@code </think>} may be split at any character boundary. An unclosed
 * reasoning block is discarded at stream completion instead of being exposed to visitors.
 */
final class ThinkContentFilter {

    private static final List<String> OPEN_TAGS = List.of("<think>", "<reasoning>");
    private static final List<String> CLOSE_TAGS = List.of("</think>", "</reasoning>");

    private final StringBuilder pending = new StringBuilder();
    private boolean reasoning;

    static String strip(String text) {
        ThinkContentFilter filter = new ThinkContentFilter();
        return filter.accept(text) + filter.finish();
    }

    static Flux<String> filter(Flux<String> source) {
        return Flux.defer(() -> {
            ThinkContentFilter filter = new ThinkContentFilter();
            return source.concatMap(chunk -> {
                    String output = filter.accept(chunk);
                    return output.isEmpty() ? Flux.empty() : Flux.just(output);
                })
                .concatWith(Flux.defer(() -> {
                    String output = filter.finish();
                    return output.isEmpty() ? Flux.empty() : Flux.just(output);
                }));
        });
    }

    String accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }
        pending.append(chunk);
        StringBuilder output = new StringBuilder();
        while (!pending.isEmpty()) {
            List<String> tags = reasoning ? CLOSE_TAGS : OPEN_TAGS;
            Match match = firstMatch(pending, tags);
            if (match != null) {
                if (!reasoning && match.index() > 0) {
                    output.append(pending, 0, match.index());
                }
                pending.delete(0, match.index() + match.tag().length());
                reasoning = !reasoning;
                continue;
            }

            int suffixLength = possibleTagPrefixLength(pending, tags);
            int safeLength = pending.length() - suffixLength;
            if (!reasoning && safeLength > 0) {
                output.append(pending, 0, safeLength);
            }
            if (safeLength > 0) {
                pending.delete(0, safeLength);
            }
            break;
        }
        return output.toString();
    }

    String finish() {
        String output = reasoning ? "" : pending.toString();
        pending.setLength(0);
        return output;
    }

    private static Match firstMatch(CharSequence value, List<String> tags) {
        String lower = value.toString().toLowerCase(Locale.ROOT);
        Match first = null;
        for (String tag : tags) {
            int index = lower.indexOf(tag);
            if (index >= 0 && (first == null || index < first.index())) {
                first = new Match(index, tag);
            }
        }
        return first;
    }

    private static int possibleTagPrefixLength(CharSequence value, List<String> tags) {
        String lower = value.toString().toLowerCase(Locale.ROOT);
        int maximum = 0;
        for (String tag : tags) {
            int limit = Math.min(lower.length(), tag.length() - 1);
            for (int length = limit; length > maximum; length--) {
                if (lower.regionMatches(lower.length() - length, tag, 0, length)) {
                    maximum = length;
                    break;
                }
            }
        }
        return maximum;
    }

    private record Match(int index, String tag) {
    }
}
