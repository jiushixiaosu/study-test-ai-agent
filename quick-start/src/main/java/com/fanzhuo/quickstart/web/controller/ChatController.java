package com.fanzhuo.quickstart.web.controller;

import com.fanzhuo.quickstart.web.memory.MysqlChatMemory;
import com.fanzhuo.quickstart.web.memory.MysqlChatMemory.ConversationSummary;
import com.fanzhuo.quickstart.web.service.ChatService;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对话 HTTP 接口层。
 * <p>
 * 仅负责请求的接收与响应返回，AI 编排逻辑（提示词、记忆、模型调用）下沉到 {@link ChatService}。
 * 通过 HTTP 触发对话，支持按 chatId 维度保留多轮记忆。
 * 同时直接结合 {@link MysqlChatMemory} 暴露会话的查询/列表/清空能力。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final MysqlChatMemory mysqlChatMemory;

    public ChatController(ChatService chatService, MysqlChatMemory mysqlChatMemory) {
        this.chatService = chatService;
        this.mysqlChatMemory = mysqlChatMemory;
    }

    /**
     * POST 对话
     * 示例：POST /api/chat  body: {"message":"厦门今天天气怎么样","chatId":"u-1001"}
     */
    @PostMapping
    public String chat(@RequestBody ChatRequest request) {
        String chatId = (request.chatId() == null || request.chatId().isBlank())
                ? "default" : request.chatId();
        return chatService.chat(chatId, request.message());
    }

    /**
     * GET 对话（方便浏览器/命令行快速试）
     * 示例：GET /api/chat?message=厦门今天天气怎么样&chatId=u-1001
     */
    @GetMapping
    public String chatGet(@RequestParam String message,
                          @RequestParam(required = false, defaultValue = "default") String chatId) {
        return chatService.chat(chatId, message);
    }

    /**
     * 列出所有会话（按最近活跃时间倒序）。
     * 示例：GET /api/chat/conversations
     */
    @GetMapping("/conversations")
    public List<ConversationSummary> listConversations() {
        return mysqlChatMemory.listConversations();
    }

    /**
     * 获取某个会话的对话历史（按窗口裁剪后的上下文，与喂给模型的一致）。
     * 示例：GET /api/chat/history?chatId=u-1001
     */
    @GetMapping("/history")
    public List<Message> history(@RequestParam String chatId) {
        return mysqlChatMemory.get(chatId);
    }

    /**
     * 清空某个会话的全部记忆（从 MySQL 中删除）。
     * 示例：DELETE /api/chat?chatId=u-1001
     */
    @DeleteMapping
    public String clear(@RequestParam String chatId) {
        mysqlChatMemory.clear(chatId);
        return "cleared: " + chatId;
    }

    /**
     * 请求体
     * @param message 用户消息
     * @param chatId  会话标识，相同 chatId 复用记忆实现多轮对话
     */
    public record ChatRequest(String message, String chatId) {
    }
}
