package com.fanzhuo.quickstart.web.service;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.net.URI;

/**
 * 图片理解服务：基于智谱 GLM-4V-Flash 多模态模型。
 * <p>
 * 职责：接收图片（公网 URL 或 base64 两种形态），构造带 Media 的 UserMessage，
 * 调用视觉模型，返回识别/分析文本。不关心图片来源（前端上传/对话中贴 URL 均可）。
 * <p>
 * 典型场景：分析聊天记录截图——提取参与者、话题、关键信息点、待办事项。
 */
@Service
public class ImageAnalysisService {

    private final ChatModel glm4vChatModel;

    public ImageAnalysisService(@Qualifier("glm4vChatModel") ChatModel glm4vChatModel) {
        this.glm4vChatModel = glm4vChatModel;
    }

    /** 默认分析指令：针对聊天记录截图做了结构化要求；非截图则退化为通用图片描述。 */
    private static final String DEFAULT_QUESTION =
            "这是一张聊天记录截图。请仔细阅读并整理输出：\n"
            + "1) 参与者（从昵称判断对话双方/多方）；\n"
            + "2) 讨论话题；\n"
            + "3) 关键信息点（按对话顺序列出）；\n"
            + "4) 需要关注或回复的事项。\n"
            + "若图片不是聊天记录截图，直接客观描述图片内容即可。";

    /**
     * 分析图片（公网 URL 形式）。
     *
     * @param imageUrl 图片 http/https 地址
     * @param question 可选的自定义分析指令；为空则使用聊天记录截图专用指令
     * @return 模型识别/分析文本
     */
    public String analyzeImageUrl(String imageUrl, String question) {
        Media media = Media.builder()
                .mimeType(MediaType.IMAGE_PNG)
                .data(URI.create(imageUrl))
                .build();
        return callVisionModel(question, media);
    }

    /**
     * 分析图片（base64 形式，用于本地文件上传）。
     *
     * @param base64   图片内容的 Base64 编码（不含 data: 前缀）
     * @param mimeType 图片 MIME 类型（image/png、image/jpeg 等）
     * @param question 可选的自定义分析指令；为空则使用聊天记录截图专用指令
     * @return 模型识别/分析文本
     */
    public String analyzeImageBase64(String base64, String mimeType, String question) {
        // data URI 是 OpenAI 兼容多模态接口的标准传图方式，Spring AI 的 Media 原生支持
        String dataUri = "data:" + mimeType + ";base64," + base64;
        Media media = Media.builder()
                .mimeType(MediaType.valueOf(mimeType))
                .data(URI.create(dataUri))
                .build();
        return callVisionModel(question, media);
    }

    private String callVisionModel(String question, Media media) {
        String text = (question == null || question.isBlank()) ? DEFAULT_QUESTION : question;
        UserMessage userMessage = UserMessage.builder()
                .text(text)
                .media(media)
                .build();
        ChatResponse response = glm4vChatModel.call(new Prompt(userMessage));
        String content = response.getResult().getOutput().getText();
        return content == null ? "" : content.trim();
    }
}
