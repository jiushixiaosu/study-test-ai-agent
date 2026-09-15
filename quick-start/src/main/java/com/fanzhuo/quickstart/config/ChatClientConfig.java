package com.fanzhuo.quickstart.config;

import com.fanzhuo.quickstart.web.memory.MysqlChatMemory;
import com.fanzhuo.quickstart.web.tool.ImageRecognitionTool;
import com.fanzhuo.quickstart.web.tool.ImageSearchTool;
import com.fanzhuo.quickstart.web.tool.PoiSearchTool;
import com.fanzhuo.quickstart.web.tool.WeatherAskTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

@Configuration
public class ChatClientConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatClientConfig.class);

    @Bean
    public ChatMemory chatMemory(JdbcTemplate jdbcTemplate,
                                 ObjectMapper objectMapper,
                                 @Value("${chat.memory.window-size:20}") int windowSize) {
        // 路线 B：MySQL 持久化记忆，完整历史落库，读取按窗口返回
        return new MysqlChatMemory(jdbcTemplate, objectMapper, windowSize);
    }

    @Bean
    public ChatClient chatClient(@Qualifier("deepSeekChatModel") ChatModel deepSeekChatModel,
                                 ChatMemory chatMemory,
                                 ImageSearchTool imageSearchTool,
                                 WeatherAskTool weatherAskTool,
                                 ImageRecognitionTool imageRecognitionTool,
                                 PoiSearchTool poiSearchTool,
                                 ObjectProvider<VectorStore> vectorStoreProvider) {
        var builder = ChatClient.builder(deepSeekChatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).order(0).build())
                .defaultTools(imageSearchTool, weatherAskTool, imageRecognitionTool, poiSearchTool);

        // RAG：VectorStore 存在时（依赖阿里云百炼 Embedding）才挂载，避免强依赖导致启动失败
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore != null) {
            builder.defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
                    .searchRequest(SearchRequest.builder()
                            .topK(4)                  // 取最相似的 4 条文档
                            .similarityThreshold(0.5) // 低于该相似度的结果丢弃
                            .build())
                    .order(1)                         // 排在记忆之后：先加载历史，再基于问题检索
                    .build());
            log.info("RAG 已启用：QuestionAnswerAdvisor 挂载成功（topK=4, similarityThreshold=0.5）");
        } else {
            log.warn("未检测到 VectorStore Bean，RAG 未启用（需配置可用的 EmbeddingModel，如 dashscope.api-key）");
        }
        return builder.build();
    }
}
