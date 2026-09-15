package com.fanzhuo.quickstart.web.service;

import com.fanzhuo.quickstart.web.prompt.SystemPrompts;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

/**
 * 对话编排层（AI 业务逻辑）。
 * <p>
 * 职责：持有 ChatClient、拼接 system/user 提示词、按 chatId 维度接入多轮记忆。
 * 后续若需引入 RAG 检索增强、工具调用开关、流式输出、限流等，都在此层扩展，
 * 不污染 Controller。
 * <p>
 * 系统提示词从 {@link SystemPrompts} 直接引用（代码内管理），不再走 @Value 配置文件注入。
 */
@Service
public class ChatService {

    private final ChatClient chatClient;
    private final String systemPrompt;
    private final ConversationLockService conversationLockService;

    public ChatService(ChatClient chatClient, ConversationLockService conversationLockService) {
        this.chatClient = chatClient;
        this.conversationLockService = conversationLockService;
        // 切换角色只需改这一行：SystemPrompts.EMOTION_ASSISTANT / GENERAL_ASSISTANT
        this.systemPrompt = SystemPrompts.EMOTION_ASSISTANT;
    }

    /**
     * 多轮对话。相同 chatId 复用记忆实现上下文连续。
     * <p>
     * 通过 Redis 分布式锁保证同一会话串行：上一条问答未结束时，新提问会收到
     * 「处理中」提示，而不是并发写入记忆造成 msg_order 冲突。
     *
     * @param chatId  会话标识
     * @param message 用户消息
     * @return AI 回复文本
     */
    public String chat(String chatId, String message) {
        return conversationLockService.executeWithLock(chatId,
                // 拿到锁：正常走模型调用
                () -> chatClient.prompt()
                        .system(systemPrompt)
                        .user(message)
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chatId))
                        .call()
                        .content(),
                // 未拿到锁：同一会话上一条还在处理
                () -> "上一条消息还在处理中，请稍候再提问~");
    }
}
