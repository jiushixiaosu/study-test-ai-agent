package com.fanzhuo.quickstart.web.demo;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 固定分块策略
 */
//@Component
public class ChunkDemoRunner implements CommandLineRunner {

    @Override
    public void run(String... args) throws Exception {
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

            // 2. 使用 TokenTextSplitter 进行基础分块（Spring AI 1.1.7 的 Builder 仅支持这些参数）
            TokenTextSplitter splitter = TokenTextSplitter.builder()
                    .withChunkSize(100)             // 每块目标100 tokens
                    .withMinChunkSizeChars(20)      // 最小块字符数，避免碎片
                    .withKeepSeparator(true)        // 保留分隔符
                    .build();

            // 3. 先执行基础分块（无重叠）
            List<Document> rawChunks = splitter.split(new Document(content));

            System.out.println("========== 基础分块结果（无重叠，共 " + rawChunks.size() + " 块）==========");
            for (int i = 0; i < rawChunks.size(); i++) {
                Document chunk = rawChunks.get(i);
                System.out.println("--- Chunk " + (i + 1) + " (tokens: " + encoding.countTokens(chunk.getText()) + ") ---");
                System.out.println(chunk.getText());
                System.out.println();
            }
        }catch (Exception e){
            throw new RuntimeException("分块异常", e);
        }
    }
}
