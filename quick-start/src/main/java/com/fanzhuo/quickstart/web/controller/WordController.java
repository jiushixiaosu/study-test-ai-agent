package com.fanzhuo.quickstart.web.controller;

import com.fanzhuo.quickstart.web.push.WeChatPushService;
import com.fanzhuo.quickstart.web.service.AiWordSource;
import com.fanzhuo.quickstart.web.service.WordCache;
import com.fanzhuo.quickstart.web.word.Word;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单词生成 API：通过 HTTP 触发 AI 生成单词，验证整条链路可行性。
 * <p>
 * 仅做请求接收与响应返回，AI 编排逻辑在 {@link AiWordSource}，文件缓存在 {@link WordCache}。
 */
@RestController
@RequestMapping("/api/words")
public class WordController {

    private static final Logger log = LoggerFactory.getLogger(WordController.class);

    private final AiWordSource aiWordSource;
    private final WordCache wordCache;
    private final WeChatPushService weChatPushService;

    public WordController(AiWordSource aiWordSource, WordCache wordCache, WeChatPushService weChatPushService) {
        this.aiWordSource = aiWordSource;
        this.wordCache = wordCache;
        this.weChatPushService = weChatPushService;
    }

    /**
     * POST 生成单词
     * 示例：POST /api/words/generate?count=20&level=大学英语四级
     */
    @PostMapping("/generate")
    public WordGenerateResponse generate(
            @RequestParam(defaultValue = "20") int count,
            @RequestParam(defaultValue = "大学英语四级") String level) {
        return doGenerate(count, level);
    }

    /**
     * GET 生成单词（方便浏览器/命令行快速验证）
     * 示例：GET /api/words/generate?count=5&level=日常口语
     */
    @GetMapping("/generate")
    public WordGenerateResponse generateGet(
            @RequestParam(defaultValue = "20") int count,
            @RequestParam(defaultValue = "大学英语四级") String level) {
        WordGenerateResponse result = doGenerate(count, level);

        weChatPushService.pushWords(result.words());
        return result;
    }

    private WordGenerateResponse doGenerate(int count, String level) {
        List<Word> words = aiWordSource.generate(count, level);

        String cachedPath = null;
        try {
            wordCache.save(words);
            cachedPath = wordCache.path();
        } catch (Exception e) {
            // 缓存失败不影响本次返回，仅记录
            log.warn("单词缓存写入失败（不影响本次返回）: {}", e.getMessage());
        }

        return new WordGenerateResponse(
                count, level, words.size(), words, cachedPath, LocalDateTime.now().toString());
    }

    public record WordGenerateResponse(
            int requestedCount,
            String level,
            int actualCount,
            List<Word> words,
            String cachedPath,
            String generatedAt
    ) {
    }
}
