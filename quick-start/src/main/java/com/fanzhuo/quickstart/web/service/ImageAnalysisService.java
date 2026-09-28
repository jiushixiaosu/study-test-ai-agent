package com.fanzhuo.quickstart.web.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 图片理解服务：基于智谱 GLM-4V-Flash 多模态模型。
 * <p>
 * 职责：接收图片（公网 URL 或 base64 两种形态），构造带 Media 的 UserMessage，
 * 调用视觉模型，返回识别/分析文本。
 *
 * <h3>为什么要显式带上对话历史</h3>
 * 视觉模型默认「失忆」：只丢一张图进去，它既不知道用户是谁，也不知道当前在聊什么，
 * 只能套用通用模板（"这是一张聊天记录截图…"）作答，结果与主对话完全脱节
 * —— 用户感受到的「图片分析与后续提问很割裂」即源于此。
 * <p>
 * 因此这里把「角色人设 + 最近若干轮对话背景 + 输出要求」组装成 SystemMessage 一起发送，
 * 让视觉模型在【当前对话语境】下解读图片，输出语气也与主助手保持一致。
 * 分析结果随后由 {@code ImageAnalysisController} 写回 ChatMemory，
 * 使后续主对话（DeepSeek）能无缝承接。
 */
@Service
public class ImageAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ImageAnalysisService.class);

    private final ChatModel glm4vChatModel;

    /** 传给视觉模型的历史最多取最近几条：多模态上下文成本高，过久的历史与当前图片也无关 */
    private static final int MAX_HISTORY_MESSAGES = 10;

    /** 单条历史消息保留的最大字符数，防止一条长回复挤占全部上下文 */
    private static final int MAX_HISTORY_CHARS_PER_MSG = 300;

    /** 与主助手保持一致的人设，避免图片分析的语气与后续对话「两张皮」 */
    private static final String ROLE_PROMPT =
            "你是「情感助手」，一位专业、温暖的情感陪伴与分析助手，正在与用户进行多轮对话。"
            + "现在用户发来一张图片，请结合已有的对话背景来解读它。";

    public ImageAnalysisService(@Qualifier("glm4vChatModel") ChatModel glm4vChatModel) {
        this.glm4vChatModel = glm4vChatModel;
    }

    // ==================== 对外方法 ====================

    /** 分析图片（公网 URL），不带对话背景。 */
    public String analyzeImageUrl(String imageUrl, String question) {
        return analyzeImageUrl(imageUrl, question, List.of());
    }

    /** 分析图片（公网 URL），带对话背景。 */
    public String analyzeImageUrl(String imageUrl, String question, List<Message> history) {
        Media media = Media.builder()
                .mimeType(MediaType.IMAGE_PNG)
                .data(URI.create(imageUrl))
                .build();
        return callVisionModel(question, media, history);
    }

    /** 分析图片（base64，本地文件上传），不带对话背景。 */
    public String analyzeImageBase64(String base64, String mimeType, String question) {
        return analyzeImageBase64(base64, mimeType, question, List.of());
    }

    /** 分析图片（base64，本地文件上传），带对话背景。 */
    public String analyzeImageBase64(String base64, String mimeType, String question, List<Message> history) {
        // data URI 是 OpenAI 兼容多模态接口的标准传图方式，Spring AI 的 Media 原生支持
        String dataUri = "data:" + mimeType + ";base64," + base64;
        Media media = Media.builder()
                .mimeType(MediaType.valueOf(mimeType))
                .data(URI.create(dataUri))
                .build();
        return callVisionModel(question, media, history);
    }

    // ==================== 内部实现 ====================

    private String callVisionModel(String question, Media media, List<Message> history) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemPrompt(history)));
        messages.add(UserMessage.builder()
                .text((question == null || question.isBlank()) ? "请分析这张图片。" : question)
                .media(media)
                .build());

        ChatResponse response = glm4vChatModel.call(new Prompt(messages));
        String content = response.getResult().getOutput().getText();
        return content == null ? "" : content.trim();
    }

    /** 组装 SystemMessage：人设 + 对话背景 + 输出要求。 */
    private String buildSystemPrompt(List<Message> history) {
        StringBuilder sb = new StringBuilder(ROLE_PROMPT);

        String context = formatHistory(history);
        if (context.isBlank()) {
            sb.append("\n\n【对话背景】这是本次对话的第一条消息，暂无历史上下文，"
                    + "请就图论图，不要凭空编造对话背景。");
        }
        else {
            // 明确标注时间顺序，避免模型把背景当成"另一段无关的对话"
            sb.append("\n\n【对话背景（按时间先后排列，最近的在最后；当前图片是用户刚刚发来的）】\n")
              .append(context);
        }

        sb.append("\n\n【输出要求】\n")
          .append("1) 用自然、口语化的中文表达，像朋友在聊天，不要用「图片中显示…」这类机械开头；\n")
          .append("2) 若图片是聊天记录截图：提炼参与者、讨论话题、关键信息点、需要关注或回复的事项；\n")
          .append("3) 若图片是其他类型：客观描述内容，并说明它与当前对话话题的关联；\n")
          .append("4) 严格基于图片实际内容与已知背景，看不清或没提到的信息不要编造。");
        return sb.toString();
    }

    /** 把历史消息压成「角色：内容」纯文本，供视觉模型理解语境。 */
    private String formatHistory(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        int from = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        StringBuilder sb = new StringBuilder();
        for (Message message : history.subList(from, history.size())) {
            // 工具中间态对理解语境没有帮助，且容易误导模型
            if (message.getMessageType() == MessageType.TOOL) {
                continue;
            }
            String text = message.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            sb.append(roleLabel(message.getMessageType()))
              .append('：')
              .append(truncate(text, MAX_HISTORY_CHARS_PER_MSG))
              .append('\n');
        }
        String result = sb.toString().trim();
        if (result.isBlank()) {
            return "";
        }
        log.info("图片分析携带 {} 条历史消息作为语境", history.size());
        return result;
    }

    private String roleLabel(MessageType type) {
        return switch (type) {
            case USER -> "用户";
            case ASSISTANT -> "助手";
            case SYSTEM -> "系统";
            case TOOL -> "工具";
        };
    }

    /** 压平换行并按上限截断，避免单条长消息撑爆上下文。 */
    private String truncate(String text, int max) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
