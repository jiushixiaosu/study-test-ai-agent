package com.fanzhuo.quickstart.config;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 视觉模型配置：智谱 GLM-4V-Flash（图片识别）。
 * <p>
 * DeepSeek 官方 API 不支持图片输入，这里新增一个独立的视觉模型 Bean
 * （OpenAI 兼容协议，指向智谱开放平台端点），专门用于分析聊天记录截图/图片。
 * <p>
 * 使用 spring-ai-openai 普通库 + 编程式构建（而非 starter 自动配置），
 * 避免自动配置再生成一个 openAiChatModel Bean 干扰现有 DeepSeek 主对话链路。
 */
@Configuration
public class VisionModelConfig {

    @Bean("glm4vChatModel")
    public OpenAiChatModel glm4vChatModel(
            @Value("${glm4v.base-url:https://open.bigmodel.cn/api/paas/v4}") String baseUrl,
            @Value("${glm4v.api-key}") String apiKey,
            @Value("${glm4v.model:glm-4v-flash}") String model,
            @Value("${glm4v.completions-path:/chat/completions}") String completionsPath) {
        // 关键：Spring AI 的 OpenAiApi 默认会在 base-url 后拼接 "/v1/chat/completions"，
        // 但智谱开放平台的真实端点是 "/api/paas/v4/chat/completions"（没有 /v1 段）。
        // 若不覆盖 completionsPath，请求会打到 ".../v4/v1/chat/completions" 从而 404。
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .completionsPath(completionsPath)
                .apiKey(apiKey)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                .build();
    }
}
