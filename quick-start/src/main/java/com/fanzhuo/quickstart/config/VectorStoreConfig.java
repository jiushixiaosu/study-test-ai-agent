package com.fanzhuo.quickstart.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/**
 * RAG 基础设施配置。
 * <p>
 * 1. VectorStore：底层 SimpleVectorStore（内存 ConcurrentHashMap）+ JSON 文件持久化，
 *    EmbeddingModel 由 {@link EmbeddingModelConfig} 提供（阿里云百炼 text-embedding-v3）。
 * 2. TokenTextSplitter：文档分块器，供知识库初始化与 /api/rag 接口共用。
 * <p>
 * 注意：SimpleVectorStore 官方定位是「测试/演示用」，检索为全量暴力余弦计算。
 * 文档量上万或需生产级能力时，应替换为 PgVectorStore / RedisVectorStore。
 */
@Configuration
public class VectorStoreConfig {

    @Bean
    @ConditionalOnBean(EmbeddingModel.class)
    public PersistentVectorStore vectorStore(
            EmbeddingModel embeddingModel,
            @Value("${rag.store-file:${user.home}/.study-ai/vector-store.json}") String storeFilePath) {
        PersistentVectorStore store = PersistentVectorStore.create(embeddingModel, new File(storeFilePath));
        store.loadIfExists();   // 启动时恢复已有文档，省去重新向量化
        return store;
    }

    /**
     * 文档分块器（token 级，自动向标点/换行微调边界，避免句子被切断）。
     * chunkSize 中文建议 300~800；minChunkSizeChars 用于过滤过短的碎片。
     */
    @Bean
    public TokenTextSplitter tokenTextSplitter(
            @Value("${rag.chunk.size:500}") int chunkSize,
            @Value("${rag.chunk.min-size-chars:100}") int minChunkSizeChars,
            @Value("${rag.chunk.keep-separator:true}") boolean keepSeparator) {
        return TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkSizeChars(minChunkSizeChars)
                .withKeepSeparator(keepSeparator)
                .build();
    }
}
