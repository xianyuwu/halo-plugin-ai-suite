package run.halo.ai.suite.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.ai.suite.config.AIProperties.ChunkConfig;

import java.util.ArrayList;
import java.util.Collections;
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
        List<String> chunks = splitBySize(segments, config.getChunkSize(), safeOverlap,
            config.isSentenceAware());

        // 合并过短的切片：小于 minChunkSize 的片段合并到前一个切片
        int minSize = Math.max(0, config.getMinChunkSize());
        if (minSize > 0) {
            chunks = mergeShortChunks(chunks, minSize);
        }

        // 组装 TextChunk 列表，过滤掉无有效文字的分片（如只有空 HTML 标签）
        List<TextChunk> result = new ArrayList<>();
        int filtered = 0;
        for (int i = 0; i < chunks.size(); i++) {
            String chunkContent = chunks.get(i).trim();
            if (chunkContent.isEmpty() || !hasMeaningfulText(chunkContent)) {
                filtered++;
                continue;
            }
            result.add(new TextChunk(
                postId + "_" + i,
                postId,
                title,
                chunkContent,
                i,
                List.of()  // keywords extracted later in ReindexService
            ));
        }
        if (filtered > 0) {
            log.debug("[DocumentChunker] 文章 '{}' 过滤掉 {} 个无有效文字的分片", title, filtered);
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
    private List<String> splitBySize(List<String> segments, int chunkSize, int overlap,
                                      boolean sentenceAware) {
        List<String> result = new ArrayList<>();

        for (String segment : segments) {
            if (segment.length() <= chunkSize) {
                result.add(segment);
                continue;
            }

            int start = 0;
            while (start < segment.length()) {
                int end = Math.min(start + chunkSize, segment.length());

                // 句子感知：在 chunkSize 附近找句子边界，避免从句子中间切断
                if (sentenceAware && end < segment.length()) {
                    int boundary = findBestBoundary(segment, start, end, chunkSize);
                    if (boundary > start) {
                        end = boundary;
                    }
                }

                String chunk = segment.substring(start, end).trim();
                if (!chunk.isEmpty()) {
                    result.add(chunk);
                }

                int nextStart = end - overlap;
                if (nextStart <= start) {
                    nextStart = end;
                }
                start = nextStart;
            }
        }

        return result;
    }

    /**
     * 在 chunkSize 附近找最佳句子边界：优先换行、其次中文标点、再次英文标点
     */
    private int findBestBoundary(String segment, int start, int end, int chunkSize) {
        int lastNewline = segment.lastIndexOf('\n', end);
        // 中文标点优先级最高
        int[] from = new int[]{
            segment.lastIndexOf('。', end),
            segment.lastIndexOf('！', end),
            segment.lastIndexOf('？', end),
            segment.lastIndexOf('；', end),
            segment.lastIndexOf('，', end),
            segment.lastIndexOf('：', end),
        };
        // 英文标点
        int[] fromEn = new int[]{
            segment.lastIndexOf('.', end),
            segment.lastIndexOf('!', end),
            segment.lastIndexOf('?', end),
            segment.lastIndexOf(';', end),
        };

        int bestPunct = -1;
        for (int p : from) { bestPunct = Math.max(bestPunct, p); }
        for (int p : fromEn) { bestPunct = Math.max(bestPunct, p); }

        int boundary = Math.max(lastNewline, bestPunct);

        // 只在边界不太远时使用（不超过 chunkSize 的 30%）
        if (boundary > start && (end - boundary) < chunkSize * 0.3) {
            return boundary + 1;
        }
        return -1;
    }

    /**
     * 合并过短的切片到前一个切片
     */
    private List<String> mergeShortChunks(List<String> chunks, int minSize) {
        List<String> result = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk.length() < minSize && !result.isEmpty()) {
                // 合并到前一个切片，用两个换行分隔
                int lastIdx = result.size() - 1;
                result.set(lastIdx, result.get(lastIdx) + "\n\n" + chunk);
            } else {
                result.add(chunk);
            }
        }
        return result;
    }

    /**
     * 判断分片是否包含有意义的文字内容
     * 去掉 HTML 标签、属性残留、实体、空白后，剩余可读文字至少 10 个字符才算有效
     */
    private boolean hasMeaningfulText(String text) {
        String plain = text
            .replaceAll("<[^>]+>", "")           // 去掉 HTML 标签
            .replaceAll("[a-z-]+=\"[^\"]*\"", "") // 去掉残留属性（style="..." 等）
            .replaceAll("&[a-zA-Z]+;", " ")      // 去掉 HTML 实体（&nbsp; 等）
            .replaceAll("&#\\d+;", " ")           // 去掉数字实体
            .replaceAll("[\\s ​‌‍﻿]+", ""); // 去掉所有空白（含 Unicode）
        return plain.length() >= 10;
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
