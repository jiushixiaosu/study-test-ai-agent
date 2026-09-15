package com.fanzhuo.quickstart.web.demo;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.springframework.boot.CommandLineRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * 手动实现滑动分块策略
 */
//@Component
public class OverlapChunkDemoRunner implements CommandLineRunner {

    @Override
    public void run(String... args) {
        try {
            String content = """
                RAG滑动窗口分块依靠重叠机制解决语义截断问题。
                固定切分容易把完整句子、专业知识点拆分到两个块，检索丢失上下文。
                chunkOverlap 代表前后两块重复的token数量，窗口每次滑动距离 = chunkSize - chunkOverlap。
                通用文档重叠比例10%~20%，合同、技术文档建议15%~20%。
                Spring AI TokenTextSplitter会自动向标点、换行微调边界，避免句子中间切断。
                """;

            // 1. 使用 jtokkit 1.1.0 的正确 API 获取 CL100K_BASE 编码（GPT-4/GPT-3.5 共用）
            EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
            Encoding encoding = registry.getEncoding(EncodingType.CL100K_BASE);

            // 4. 手动实现滑动窗口重叠分块
            // 核心逻辑：将全文按 token 拆分，以滑动窗口方式生成重叠块
            int chunkSize = 100;
            int chunkOverlap = 20;
            int step = chunkSize - chunkOverlap; // 窗口每次滑动距离 = 80 tokens

            List<Integer> allTokens = encoding.encode(content).boxed();
            List<String> overlapChunks = new ArrayList<>();

            int start = 0;
            while (start < allTokens.size()) {
                int end = Math.min(start + chunkSize, allTokens.size());
                List<Integer> windowTokens = allTokens.subList(start, end);

                // 将窗口内的 tokens 解码回文本
                IntArrayList tokenArray = new IntArrayList(windowTokens.size());
                windowTokens.forEach(tokenArray::add);
                String chunkText = encoding.decode(tokenArray);

                if (!chunkText.trim().isEmpty()) {
                    overlapChunks.add(chunkText);
                }

                if (end >= allTokens.size()) {
                    break; // 已到达文本末尾
                }
                start += step; // 滑动 step 个 token
            }

            System.out.println("========== 滑动窗口重叠分块（chunkSize=" + chunkSize
                    + ", chunkOverlap=" + chunkOverlap + ", step=" + step + "，共 " + overlapChunks.size() + " 块）==========");
            for (int i = 0; i < overlapChunks.size(); i++) {
                String chunkText = overlapChunks.get(i);
                System.out.println("===== Chunk " + (i + 1) + " (tokens: " + encoding.countTokens(chunkText) + ") =====");
                System.out.println(chunkText);
                System.out.println();
            }

            // 展示重叠效果：打印相邻两块的首尾对比
            if (overlapChunks.size() >= 2) {
                System.out.println("========== 重叠效果验证 ==========");
                for (int i = 0; i < overlapChunks.size() - 1; i++) {
                    String currentTail = overlapChunks.get(i).substring(
                            Math.max(0, overlapChunks.get(i).length() - 30));
                    String nextHead = overlapChunks.get(i + 1).substring(
                            0, Math.min(30, overlapChunks.get(i + 1).length()));
                    System.out.println("Chunk " + (i + 1) + " 尾部: ..." + currentTail);
                    System.out.println("Chunk " + (i + 2) + " 头部: " + nextHead + "...");
                    System.out.println();
                }
            }

        } catch (Exception e) {
            System.err.println("滑动窗口分块演示执行失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
