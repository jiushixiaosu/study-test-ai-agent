package com.fanzhuo.quickstart.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云百炼（通义）Embedding 模型配置。
 * <p>
 * 背景：项目移除 Ollama 后本地不再有 EmbeddingModel 实现，RAG 能力失效。
 * 这里改用阿里云百炼的 text-embedding-v3，通过其「OpenAI 兼容接口」接入，
 * 复用已有的 spring-ai-openai 普通库，无需引入新依赖。
 * <p>
 * 关键坑一：百炼的 base-url 已自带 /compatible-mode/v1 后缀，而 Spring AI 的 OpenAiApi
 * 默认会再拼 "/v1/embeddings"，导致请求打到 ".../v1/v1/embeddings" 而 404。
 * 因此必须用 embeddingsPath("/embeddings") 覆盖默认路径。
 * <p>
 * 关键坑二：Spring AI 1.1.7 的 OpenAiEmbeddingModel **没有** builder() 静态方法
 * （只有 OpenAiChatModel 才有），必须用构造器创建。
 */
@Configuration
public class EmbeddingModelConfig {

    @Bean("dashscopeEmbeddingModel")
    public EmbeddingModel dashscopeEmbeddingModel(
            @Value("${dashscope.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${dashscope.api-key}") String apiKey,
            @Value("${dashscope.embedding.model:text-embedding-v3}") String model,
            @Value("${dashscope.embeddings-path:/embeddings}") String embeddingsPath) {

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .embeddingsPath(embeddingsPath)   // 覆盖默认的 /v1/embeddings
                .apiKey(apiKey)
                .build();

        // 用无参构造 + setter（比 builder 更稳妥，1.1.7 的 Builder 方法名未在文档中列出）
        OpenAiEmbeddingOptions options = new OpenAiEmbeddingOptions();
        options.setModel(model);

        // MetadataMode.NONE：只嵌入文档正文，不把 metadata 拼进 embedding，
        // 保证与查询文本的嵌入方式一致。
        return new OpenAiEmbeddingModel(api, MetadataMode.NONE, options);
    }
}
