package com.fanzhuo.quickstart.web.controller;

import com.fanzhuo.quickstart.web.service.ConversationLockService;
import com.fanzhuo.quickstart.web.service.ImageAnalysisService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 图片识别接口。
 * <p>
 * 前端上传聊天记录截图（multipart）或传图片 URL，返回视觉模型的识别/分析文本。
 * 结果统一封装为 {"content": "..."}，与对话接口返回格式保持一致。
 * <p>
 * 注意：图片分析直连视觉模型，不经过 ChatClient/MessageChatMemoryAdvisor，
 * 因此这里显式调用 ChatMemory.add() 把「用户提问 + 图片标记」与「分析结果」
 * 一并持久化，保证刷新页面后能从历史中恢复这段对话。
 * 图片二进制本身不入库（base64 过大），仅以「[图片] 文件名」占位保留对话语义。
 */
@RestController
@RequestMapping("/api/image")
public class ImageAnalysisController {

    private final ImageAnalysisService imageAnalysisService;
    private final ChatMemory chatMemory;
    private final ConversationLockService conversationLockService;

    public ImageAnalysisController(ImageAnalysisService imageAnalysisService,
                                   ChatMemory chatMemory,
                                   ConversationLockService conversationLockService) {
        this.imageAnalysisService = imageAnalysisService;
        this.chatMemory = chatMemory;
        this.conversationLockService = conversationLockService;
    }

    /** 本地图片上传识别：POST /api/image/analyze（multipart/form-data，可选 chatId 落库） */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> analyzeUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "question", required = false) String question,
            @RequestParam(value = "chatId", required = false) String chatId) throws IOException {
        String mimeType = (file.getContentType() != null && !file.getContentType().isBlank())
                ? file.getContentType() : MediaType.IMAGE_PNG_VALUE;
        String base64 = Base64.getEncoder().encodeToString(file.getBytes());
        String content = imageAnalysisService.analyzeImageBase64(base64, mimeType, question);

        // 落库：用户提问（含图片占位标记）+ 模型分析结果
        String userText = (question == null || question.isBlank())
                ? "[图片] " + file.getOriginalFilename()
                : question + "\n[图片] " + file.getOriginalFilename();
        persistToMemory(chatId, userText, content);

        return Map.of("content", content);
    }

    /** 图片 URL 识别：POST /api/image/analyze-url（JSON） */
    @PostMapping(value = "/analyze-url", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> analyzeUrl(@RequestBody AnalyzeUrlRequest request) {
        String content = imageAnalysisService.analyzeImageUrl(request.url(), request.question());

        String userText = (request.question() == null || request.question().isBlank())
                ? "[图片] " + request.url()
                : request.question() + "\n[图片] " + request.url();
        persistToMemory(request.chatId(), userText, content);

        return Map.of("content", content);
    }

    /** 写入会话记忆（chat_memory 表）。chatId 为空则不落库，保持向后兼容。 */
    private void persistToMemory(String chatId, String userText, String aiText) {
        if (chatId == null || chatId.isBlank()) {
            return;
        }
        // 与对话共用同一把会话锁，避免并发写 chat_memory 造成 msg_order 唯一键冲突
        conversationLockService.executeWithLock(chatId, () -> {
            chatMemory.add(chatId, List.of(
                    new UserMessage(userText),
                    new AssistantMessage(aiText)));
            return null;
        }, () -> null);
    }

    /** 请求体（Java 17 record） */
    public record AnalyzeUrlRequest(String url, String question, String chatId) {
    }
}
