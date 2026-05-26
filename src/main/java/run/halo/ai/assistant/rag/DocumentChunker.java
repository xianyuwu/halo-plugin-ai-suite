package run.halo.ai.assistant.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.ai.assistant.config.AIProperties.ChunkConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文章切片器 — 把一篇长文章切成适合检索的小段
 *
 * 切片策略：
 * 1. Markdown 标题感知模式（默认）：先按 H1-H6 标题分段，
 *    超长的段再按段落切分，标题会作为上下文加到子切片前
 * 2. 自定义模式：用指定分隔符切割，再按 chunkSize 裁剪
 *
 * 类比：像切蛋糕 — 标题是天然的"切痕"，沿着切痕切最自然；
 * 如果一块太大，就再切成更小的块
 */
@Slf4j
@Component
public class DocumentChunker {

    // Markdown 标题正则：匹配 # 到 ###### 开头的行
    private static final Pattern HEADING_PATTERN =
        Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    /**
     * 把一篇文章切成多个 TextChunk
     *
     * @param postId   文章 ID（Halo Post 的 metadata.name）
     * @param title    文章标题
     * @param content  文章 Markdown 原文
     * @param config   切片配置
     * @return 切片列表
     */
    public List<TextChunk> chunk(String postId, String title, String content, ChunkConfig config) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        // 清理空白
        if (config.isCleanWhitespace()) {
            content = cleanWhitespace(content);
        }

        // 去掉 Markdown 图片语法（图片对检索没用）
        content = content.replaceAll("!\\[.*?\\]\\(.*?\\)", "");

        List<String> segments;
        if ("auto".equals(config.getChunkMode()) && config.isMarkdownHeadingAware()) {
            segments = splitByHeadings(content, config);
        } else if ("custom".equals(config.getChunkMode())) {
            segments = splitBySeparator(content, config.getChunkSeparator());
        } else {
            // auto 模式但没开标题感知，按段落分
            segments = splitBySeparator(content, "\n\n");
        }

        // 按大小限制切片（带重叠）
        // 钳制 overlap < chunkSize：必须给每轮留出至少 1 字符的进展空间，
        // 否则 splitBySize 滑动窗口会原地踏步 → 死循环 → OOM。
        // 配置面板允许 chunkOverlap 最大到 500、chunkSize 最小 100，理论上能配出 overlap >= chunkSize 的坏组合。
        int safeOverlap = Math.max(0, Math.min(config.getChunkOverlap(), config.getChunkSize() - 1));
        if (safeOverlap != config.getChunkOverlap()) {
            log.warn("[DocumentChunker] chunkOverlap({}) >= chunkSize({})，已钳制为 {} 防止死循环",
                config.getChunkOverlap(), config.getChunkSize(), safeOverlap);
        }
        List<String> chunks = splitBySize(segments, config.getChunkSize(), safeOverlap);

        // 组装 TextChunk 列表
        List<TextChunk> result = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunkContent = chunks.get(i).trim();
            if (!chunkContent.isEmpty()) {
                result.add(new TextChunk(
                    postId + "_" + i,
                    postId,
                    title,
                    chunkContent,
                    i
                ));
            }
        }

        log.debug("[DocumentChunker] 文章 '{}' 切成 {} 个 chunk", title, result.size());
        return result;
    }

    /**
     * 按 Markdown 标题层级切分
     *
     * 策略：每个标题到下一个标题之间的内容作为一个 segment。
     * 标题本身会保留在 segment 前面，作为上下文。
     */
    private List<String> splitByHeadings(String content, ChunkConfig config) {
        List<String> segments = new ArrayList<>();
        Matcher matcher = HEADING_PATTERN.matcher(content);

        int lastEnd = 0;
        String currentHeading = "";

        while (matcher.find()) {
            // 标题前面的内容（上一个标题到这个标题之间）
            String beforeHeading = content.substring(lastEnd, matcher.start()).trim();
            if (!beforeHeading.isEmpty()) {
                // 加上当前标题作为上下文
                segments.add(currentHeading + "\n" + beforeHeading);
            }
            currentHeading = matcher.group(); // 完整标题行（如 "## 安装指南"）
            lastEnd = matcher.end();
        }

        // 最后一段（最后一个标题到结尾）
        String remaining = content.substring(lastEnd).trim();
        if (!remaining.isEmpty()) {
            segments.add(currentHeading + "\n" + remaining);
        }

        // 如果没有标题，整篇作为一段
        if (segments.isEmpty()) {
            segments.add(content);
        }

        return segments;
    }

    /**
     * 按分隔符切分
     */
    private List<String> splitBySeparator(String content, String separator) {
        String[] parts = content.split(Pattern.quote(separator));
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * 按大小切分（带重叠）
     *
     * 如果 segment 超过 chunkSize，就按字符边界切开。
     * 重叠部分确保不会正好在一个句子的中间断开。
     *
     * 类比：像翻书时手指压住上一页最后几行，确保不会漏看
     */
    private List<String> splitBySize(List<String> segments, int chunkSize, int overlap) {
        List<String> result = new ArrayList<>();

        for (String segment : segments) {
            if (segment.length() <= chunkSize) {
                result.add(segment);
                continue;
            }

            // 超长 segment：滑动窗口切割
            int start = 0;
            while (start < segment.length()) {
                int end = Math.min(start + chunkSize, segment.length());

                // 尝试在句子/段落边界切（不要把句子从中间切断）
                if (end < segment.length()) {
                    int lastNewline = segment.lastIndexOf('\n', end);
                    int lastPeriod = Math.max(
                        segment.lastIndexOf('。', end),
                        segment.lastIndexOf('.', end)
                    );
                    int boundary = Math.max(lastNewline, lastPeriod);

                    // 只在边界不太远时使用（不超过 chunkSize 的 20%）
                    if (boundary > start && (end - boundary) < chunkSize * 0.2) {
                        end = boundary + 1;
                    }
                }

                String chunk = segment.substring(start, end).trim();
                if (!chunk.isEmpty()) {
                    result.add(chunk);
                }

                // 下一块的起点 = 当前终点 - overlap
                // 硬保证 start 严格递增：哪怕 overlap 配置极端，也不允许停滞或倒退。
                // 否则会原地切同一段 → 死循环 → OOM（已经踩过坑）。
                int nextStart = end - overlap;
                if (nextStart <= start) {
                    nextStart = end;  // 退化为无重叠，但绝不死循环
                }
                start = nextStart;
            }
        }

        return result;
    }

    /**
     * 清理多余空白：多个连续空行变一行，连续空格变一个
     */
    private String cleanWhitespace(String text) {
        return text
            .replaceAll("\n{3,}", "\n\n")      // 多个空行 → 一个空行
            .replaceAll("[ \t]+", " ")          // 连续空格/制表符 → 一个空格
            .replaceAll(" *\n *", "\n")         // 行首行尾空格
            .trim();
    }
}
