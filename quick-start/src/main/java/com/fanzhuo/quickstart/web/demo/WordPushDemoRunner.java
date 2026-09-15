package com.fanzhuo.quickstart.web.demo;

import com.fanzhuo.quickstart.web.push.WeChatPushService;
import com.fanzhuo.quickstart.web.service.AiWordSource;
import com.fanzhuo.quickstart.web.service.WordCache;
import com.fanzhuo.quickstart.web.word.Word;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 单词推送演示：AI 生成单词 → 文件缓存 → Server酱 推送到微信。
 */
//@Component
public class WordPushDemoRunner implements CommandLineRunner {

    private final AiWordSource aiWordSource;
    private final WordCache wordCache;
    private final WeChatPushService weChatPushService;

    public WordPushDemoRunner(AiWordSource aiWordSource,
                              WordCache wordCache,
                              WeChatPushService weChatPushService) {
        this.aiWordSource = aiWordSource;
        this.wordCache = wordCache;
        this.weChatPushService = weChatPushService;
    }

    @Override
    public void run(String... args) {
        System.out.println("===== 单词推送演示 =====");

        // 1. 先尝试从缓存加载
        List<Word> words = wordCache.load().orElseGet(() -> {
            // 2. 缓存没有，调 AI 生成
            System.out.println("缓存无数据，调用 AI 生成...");
            List<Word> generated = aiWordSource.generate(10, "大学英语四级");
            try {
                wordCache.save(generated);
                System.out.println("单词已缓存到: " + wordCache.path());
            } catch (Exception e) {
                System.err.println("缓存写入失败: " + e.getMessage());
            }
            return generated;
        });

        // 3. 推送到微信
        weChatPushService.pushWords(words);
        System.out.println("单词已推送到微信，共 " + words.size() + " 个");
    }
}