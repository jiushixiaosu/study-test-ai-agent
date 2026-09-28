package com.fanzhuo.quickstart.config;

import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;

/**
 * 中文友好的「递归分隔符 + 块间重叠」文档分块器。
 *
 * <h3>为什么不用 Spring AI 自带的 TokenTextSplitter</h3>
 * 实测其源码（spring-ai-commons 1.1.7）存在两个对中文不友好的硬伤：
 * <ol>
 *   <li><b>标点表不含中文标点</b>：{@code DEFAULT_PUNCTUATION_MARKS = ['.', '?', '!', '\n']}，
 *       中文的「。？！；」统统不在列内，导致标点回退逻辑对中文形同虚设，
 *       长段落只能在 token 边界硬切，句子被拦腰截断。</li>
 *   <li><b>不支持重叠</b>：没有 chunkOverlap 概念，相邻块零交集，
 *       跨块边界的语义在检索时容易丢失。</li>
 * </ol>
 * 同时 Spring AI 1.1.7 并未提供 {@code RecursiveCharacterTextSplitter}
 * （整个依赖树里只有 {@code TextSplitter} 与 {@code TokenTextSplitter}），故此处自行实现。
 *
 * <h3>算法（按字符数计量）</h3>
 * <ol>
 *   <li><b>递归切分</b>：按分隔符强度从高到低（段落 → 换行 → 中文句末 → 英文句末 →
 *       中文逗号 → 英文标点 → 空格）逐级降级切分，直到片段不超过 chunkSize；
 *       实在无分隔符可用才硬切。</li>
 *   <li><b>合并成块</b>：把相邻小片段贪心合并到接近 chunkSize。</li>
 *   <li><b>注入重叠</b>：每块收尾时，把「本块尾部 chunkOverlap 个字符」作为下一块的开头。</li>
 *   <li><b>过滤碎片</b>：长度不超过 minChunkSizeChars 的中间块丢弃；
 *       但被丢弃的内容仍会作为下一块的重叠前缀保留，末尾块只要非空一律保留，确保不丢信息。</li>
 * </ol>
 *
 * <h3>为什么按字符数而不是 token 数</h3>
 * 中文场景下「1 汉字 ≈ 1 token」，字符数与 token 数近似 1:1，但字符数更直观、
 * 无需引入 jtokkit 依赖，且估算 text-embedding-v3 的 8192 上限时更安全。
 */
public class ChineseRecursiveTextSplitter extends TextSplitter {

    /**
     * 分隔符按「语义强度」从高到低排列，递归时逐级降级使用。
     * 前两个是结构级（段落/换行），中间是句子级，最后是短语级与兜底。
     */
    private static final List<String> SEPARATORS = List.of(
            "\n\n", "\n",
            "。", "！", "？", "；", "…",
            ".", "!", "?", ";",
            "，", "、", ",", "：", ":",
            " ", "\t");

    /** 每块目标字符数 */
    private final int chunkSize;

    /** 块间重叠字符数 */
    private final int chunkOverlap;

    /** 中间块的最小字符数，低于此值丢弃 */
    private final int minChunkSizeChars;

    /** 分块时是否保留分隔符（换行、句号等） */
    private final boolean keepSeparator;

    public ChineseRecursiveTextSplitter(int chunkSize, int chunkOverlap,
            int minChunkSizeChars, boolean keepSeparator) {
        Assert.isTrue(chunkSize > 0, "chunkSize 必须大于 0");
        Assert.isTrue(chunkOverlap >= 0, "chunkOverlap 不能为负数");
        Assert.isTrue(chunkOverlap < chunkSize,
                "chunkOverlap(" + chunkOverlap + ") 必须小于 chunkSize(" + chunkSize + ")，否则分块会失去推进力");
        Assert.isTrue(minChunkSizeChars >= 0, "minChunkSizeChars 不能为负数");
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.minChunkSizeChars = minChunkSizeChars;
        this.keepSeparator = keepSeparator;
    }

    @Override
    protected List<String> splitText(String text) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }
        // 统一换行符：避免 Windows 的 \r\n 在按 \n 切分后残留 \r
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        return merge(splitRecursively(normalized, 0));
    }

    /**
     * ① 递归切分：用当前分隔符切分，切完仍超长的片段降级到下一级分隔符重切。
     * 返回的是「原子片段」，长度保证不超过 chunkSize。
     */
    private List<String> splitRecursively(String text, int separatorIndex) {
        List<String> pieces = new ArrayList<>();

        if (text.length() <= chunkSize) {
            addIfNotBlank(pieces, text);
            return pieces;
        }
        if (separatorIndex >= SEPARATORS.size()) {
            hardSplit(text, pieces);   // 无任何分隔符可用 → 硬切
            return pieces;
        }

        String separator = SEPARATORS.get(separatorIndex);
        int cursor = 0;
        while (cursor < text.length()) {
            int hit = text.indexOf(separator, cursor);
            if (hit < 0) {
                // 剩余部分不含该分隔符，整体作为一个片段处理
                appendPiece(pieces, text.substring(cursor), separatorIndex, false);
                break;
            }
            int end = hit + separator.length();
            // 分隔符归属前一个片段，避免切完丢失句读
            appendPiece(pieces, text.substring(cursor, end), separatorIndex, true);
            cursor = end;
        }
        return pieces;
    }

    /** 处理单个片段：去掉不需要的分隔符、过滤空白、超长则降级重切。 */
    private void appendPiece(List<String> pieces, String piece, int separatorIndex, boolean endsWithSeparator) {
        if (piece.isEmpty()) {
            return;
        }
        String effective = piece;
        if (!keepSeparator && endsWithSeparator) {
            effective = piece.substring(0, piece.length() - SEPARATORS.get(separatorIndex).length());
        }
        if (effective.isBlank()) {
            return;
        }
        if (effective.length() <= chunkSize) {
            pieces.add(effective);
        }
        else {
            pieces.addAll(splitRecursively(effective, separatorIndex + 1));
        }
    }

    /** 兜底硬切：没有任何可用分隔符时，按 chunkSize 定长切分。 */
    private void hardSplit(String text, List<String> pieces) {
        for (int i = 0; i < text.length(); i += chunkSize) {
            addIfNotBlank(pieces, text.substring(i, Math.min(i + chunkSize, text.length())));
        }
    }

    /** ② 合并成块 + ③ 注入重叠 + ④ 过滤碎片。 */
    private List<String> merge(List<String> pieces) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int injected = 0;   // current 开头来自上一块尾部的重叠字符数

        for (String piece : pieces) {
            // current 已有「本块自己的内容」且再加就超长 → 收尾并开新块
            boolean hasOwnContent = current.length() > injected;
            if (hasOwnContent && current.length() + piece.length() > chunkSize) {
                String finished = current.toString().trim();
                if (finished.length() > minChunkSizeChars) {
                    chunks.add(finished);
                }
                current.setLength(0);
                // 被丢弃的块内容也会通过这段重叠保留到下一块，不会凭空消失
                String overlapText = tail(finished, chunkOverlap);
                current.append(overlapText);
                injected = overlapText.length();
            }
            current.append(piece);
        }

        // 末尾块只要非空就保留：短文档整体就是一块，不能因为「不够 minChunkSizeChars」被丢掉
        String last = current.toString().trim();
        if (!last.isEmpty()) {
            chunks.add(last);
        }
        return chunks;
    }

    /** 取字符串末尾 n 个字符，作为下一块的重叠前缀。 */
    private String tail(String value, int n) {
        if (value.isEmpty() || n <= 0) {
            return "";
        }
        return value.substring(Math.max(0, value.length() - n));
    }

    private void addIfNotBlank(List<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value);
        }
    }

}
