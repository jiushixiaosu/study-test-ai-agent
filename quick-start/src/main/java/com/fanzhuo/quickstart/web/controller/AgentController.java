package com.fanzhuo.quickstart.web.controller;

import com.fanzhuo.quickstart.web.agent.AgentLoopService;
import com.fanzhuo.quickstart.web.agent.AgentResult;
import com.fanzhuo.quickstart.web.service.ConversationLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 接口：走「显式决策循环」，返回最终答案 + 完整决策步骤。
 * <p>
 * 与 {@code /api/chat}（ChatClient 隐式工具循环）相比，本接口的过程是可见的：
 * 响应体中 steps 数组会列出每一步调用了哪个工具、传了什么参数，
 * 前端可据此展示 AI 的「思考过程」，也便于排查问题。
 * <p>
 * 同样通过 Redis 分布式锁保证同一会话串行。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentLoopService agentLoopService;
    private final ConversationLockService conversationLockService;
    private final int maxSteps;

    public AgentController(AgentLoopService agentLoopService,
                           ConversationLockService conversationLockService,
                           @Value("${agent.max-steps:8}") int maxSteps) {
        this.agentLoopService = agentLoopService;
        this.conversationLockService = conversationLockService;
        this.maxSteps = maxSteps;
    }

    /**
     * POST 运行 Agent
     * 示例：POST /api/agent/chat  body: {"message":"帮我查厦门天气并推荐一个适合约会的咖啡馆","chatId":"u-1001"}
     */
    @PostMapping("/chat")
    public AgentResult chat(@RequestBody AgentChatRequest request) {
        String chatId = (request.chatId() == null || request.chatId().isBlank())
                ? "default" : request.chatId();
        return runWithLock(chatId, request.message());
    }

    /**
     * GET 运行 Agent（便于浏览器/命令行调试，可直接看到 steps）
     * 示例：GET /api/agent/chat?message=厦门今天天气怎么样&chatId=u-1001
     */
    @GetMapping("/chat")
    public AgentResult chatGet(@RequestParam String message,
                               @RequestParam(required = false, defaultValue = "default") String chatId) {
        return runWithLock(chatId, message);
    }

    private AgentResult runWithLock(String chatId, String message) {
        return conversationLockService.executeWithLock(chatId,
                () -> {
                    try {
                        return agentLoopService.run(chatId, message);
                    } catch (Exception e) {
                        log.error("Agent 执行异常, chatId={}", chatId, e);
                        return AgentResult.failed(e.getMessage(), List.of(), maxSteps);
                    }
                },
                () -> AgentResult.busy(maxSteps));
    }

    /** 请求体 */
    public record AgentChatRequest(String message, String chatId) {
    }
}
