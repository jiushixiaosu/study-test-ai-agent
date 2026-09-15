package com.fanzhuo.quickstart.web.tool;

import com.fanzhuo.quickstart.web.service.ImageAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * 图片识别工具：注册为 AI 对话可调用的 Tool。
 * <p>
 * DeepSeek 本身不能看图片，但通过这个工具，对话中用户给出图片 URL 时，
 * 模型会调用本工具把图片交给视觉模型（GLM-4V-Flash）识别，再把结果组织进回复。
 */
@Service
public class ImageRecognitionTool {

    private static final Logger log = LoggerFactory.getLogger(ImageRecognitionTool.class);

    private final ImageAnalysisService imageAnalysisService;

    public ImageRecognitionTool(ImageAnalysisService imageAnalysisService) {
        this.imageAnalysisService = imageAnalysisService;
    }

    @Tool(description = "识别并分析一张图片（传入图片 URL）。适合分析聊天记录截图、验证码、图表、照片等，返回图片内容描述")
    public String recognizeImage(
            @ToolParam(description = "图片的完整 URL 地址（http/https）") String imageUrl,
            @ToolParam(description = "可选：希望重点了解的内容，如'这是一张聊天记录截图，请提取对话内容'；留空则自动按聊天记录截图分析", required = false) String question) {
        try {
            return imageAnalysisService.analyzeImageUrl(imageUrl, question);
        } catch (Exception e) {
            // 脱敏：不向模型泄露原始异常（可能含 key 或堆栈）
            log.error("图片识别失败, url={}", imageUrl, e);
            return "图片识别失败，请检查图片 URL 是否可访问";
        }
    }
}
