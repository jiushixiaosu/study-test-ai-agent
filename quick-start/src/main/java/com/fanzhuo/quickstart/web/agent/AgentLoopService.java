package com.fanzhuo.quickstart.web.agent;

import com.fanzhuo.quickstart.web.agent.AgentResult.AgentStep;
import com.fanzhuo.quickstart.web.prompt.SystemPrompts;
import com.fanzhuo.quickstart.web.tool.ImageRecognitionTool;
import com.fanzhuo.quickstart.web.tool.ImageSearchTool;
import com.fanzhuo.quickstart.web.tool.PoiSearchTool;
import com.fanzhuo.quickstart.web.tool.WeatherAskTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 显式决策循环（ReAct Agent Loop）。
 * <p>
 * 与 {@code ChatClient} 的区别：ChatClient 的工具循环是**框架内置的隐式循环**，
 * 过程不可见、步数不可控、无法中断。这里改为自己驱动循环：
 * <pre>
 *   循环：
 *     ① 观察：把当前消息历史交给模型
 *     ② 决策：模型返回"调用工具"或"给出答案"
 *     ③ 行动：若是工具调用，则执行工具并记录步骤
 *     ④ 反馈：把工具结果并入历史，回到 ①
 *   （直到模型不再要求工具，或达到 maxSteps 上限）
 * </pre>
 * <p>
 * 由于不再走 ChatClient 的 advisor 链，记忆与 RAG 需在此手动实现：
 * <ul>
 *   <li>记忆：{@code chatMemory.get()} 载入历史，结束时写回最终问答。</li>
 *   <li>RAG：用 VectorStore 检索相关文档并作为参考资料注入（等价于 QuestionAnswerAdvisor 的动作）。</li>
 * </ul>
 * 关键实现点：
 * <ul>
 *   <li>{@code internalToolExecutionEnabled(false)}：关闭模型内部自动执行工具，
 *       否则框架会自己跑完循环，我们的循环就拿不到中间步骤。</li>
 *   <li>{@code maxSteps}：硬性护栏，防止模型反复调工具导致死循环。</li>
 *   <li>记忆只写最终的 user + assistant 两条，不写工具中间态，
 *       避免工具消息污染 chat_memory 表（该表对 TOOL 类型的重建能力有限）。</li>
 * </ul>
 */
@Service
public class AgentLoopService {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopService.class);

    private final ChatModel chatModel;
    private final ChatMemory chatMemory;
    private final ToolCallingManager toolCallingManager;
    private final ToolCallback[] toolCallbacks;
    private final String systemPrompt;
    private final int maxSteps;

    /** 向量库（未配置 Embedding 时为 null，此时跳过 RAG） */
    private final VectorStore vectorStore;
    private final int ragTopK;
    private final double ragThreshold;

    public AgentLoopService(@Qualifier("deepSeekChatModel") ChatModel chatModel,
                            ChatMemory chatMemory,
                            ObjectProvider<ToolCallingManager> toolCallingManagerProvider,
                            ObjectProvider<VectorStore> vectorStoreProvider,
                            ImageSearchTool imageSearchTool,
                            WeatherAskTool weatherAskTool,
                            ImageRecognitionTool imageRecognitionTool,
                            PoiSearchTool poiSearchTool,
                            @Value("${agent.max-steps:8}") int maxSteps,
                            @Value("${agent.rag.top-k:4}") int ragTopK,
                            @Value("${agent.rag.similarity-threshold:0.5}") double ragThreshold) {
        this.chatModel = chatModel;
        this.chatMemory = chatMemory;
        // 优先用框架自动配置的 ToolCallingManager，没有则用默认实现
        this.toolCallingManager = toolCallingManagerProvider
                .getIfAvailable(() -> ToolCallingManager.builder().build());
        this.vectorStore = vectorStoreProvider.getIfAvailable();
        // 把 @Tool 注解的 POJO 统一转成 ToolCallback
        this.toolCallbacks = ToolCallbacks.from(
                imageSearchTool, weatherAskTool, imageRecognitionTool, poiSearchTool);
        this.systemPrompt = SystemPrompts.EMOTION_ASSISTANT;
        this.maxSteps = maxSteps;
        this.ragTopK = ragTopK;
        this.ragThreshold = ragThreshold;
    }

    /**
     * 运行一次决策循环。
     *
     * @param conversationId 会话标识（用于取/存长期记忆）
     * @param userMessage    用户本轮输入
     * @return 含最终答案与决策步骤的结果
     */
    public AgentResult run(String conversationId, String userMessage) {
        // 初始消息：系统提示词 + RAG 参考资料 + 历史记忆 + 本轮输入
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        appendRagContext(messages, userMessage);
        messages.addAll(chatMemory.get(conversationId));
        messages.add(new UserMessage(userMessage));

        List<AgentStep> steps = new ArrayList<>();
        String answer = null;
        boolean completed = false;

        // 关闭内部工具执行：把"执行权"收回本循环
        var options = ToolCallingChatOptions.builder()
                .toolCallbacks(toolCallbacks)
                .internalToolExecutionEnabled(false)
                .build();

        for (int step = 1; step <= maxSteps; step++) {
            Prompt prompt = new Prompt(messages, options);
            ChatResponse response = chatModel.call(prompt);
            AssistantMessage output = response.getResult().getOutput();
            messages.add(output);

            // 模型不再要求工具 → 本轮任务结束
            if (!output.hasToolCalls()) {
                answer = output.getText();
                completed = true;
                log.info("Agent 完成, conversationId={}, 步数={}", conversationId, step);
                break;
            }

            // 记录本步的工具调用（过程可见）
            for (AssistantMessage.ToolCall toolCall : output.getToolCalls()) {
                log.info("Agent 第 {} 步调用工具: {} 参数: {}",
                        step, toolCall.name(), toolCall.arguments());
                steps.add(new AgentStep(step, toolCall.name(), toolCall.arguments()));
            }

            // 执行工具，并用返回的完整历史替换当前历史
            ToolExecutionResult execResult = toolCallingManager.executeToolCalls(prompt, response);
            messages = new ArrayList<>(execResult.conversationHistory());
        }

        if (completed && answer != null) {
            // 只持久化最终问答，不写工具中间态
            chatMemory.add(conversationId, List.of(
                    new UserMessage(userMessage),
                    new AssistantMessage(answer)));
            return AgentResult.done(answer, steps, maxSteps);
        }

        log.warn("Agent 达到步数上限仍未收敛, conversationId={}, maxSteps={}", conversationId, maxSteps);
        return AgentResult.limitReached(null, steps, maxSteps);
    }

    /**
     * 手动 RAG：检索相关文档并作为参考资料注入。
     * 等价于 ChatClient 链路上 QuestionAnswerAdvisor 的行为，
     * 因为这里绕过了 advisor 链，必须自己补上，否则 Agent 接口会丢失知识库能力。
     */
    private void appendRagContext(List<Message> messages, String userMessage) {
        if (vectorStore == null) {
            return;
        }
        try {
            List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(userMessage)
                    .topK(ragTopK)
                    .similarityThreshold(ragThreshold)
                    .build());
            if (docs == null || docs.isEmpty()) {
                return;
            }
            String context = docs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n---\n"));
            messages.add(new SystemMessage("以下是知识库中与用户问题相关的参考资料，回答时优先参考：\n" + context));
            log.debug("Agent RAG 命中 {} 条参考资料", docs.size());
        } catch (Exception e) {
            // RAG 失败不影响主流程
            log.warn("Agent RAG 检索失败，跳过参考资料注入", e);
        }
    }
}
