package com.fanzhuo.quickstart.web.push;

import cn.hutool.http.HttpUtil;
import com.fanzhuo.quickstart.web.word.Word;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 微信推送服务：通过 Server酱 将消息推送到微信。
 */
@Service
public class WeChatPushService {

    private final String sendKey;

    public WeChatPushService(@Value("${wechat.push.server-chan-key}") String sendKey) {
        this.sendKey = sendKey;
    }

    /**
     * 推送单词列表到微信。
     *
     * @param words 单词列表
     */
    public void pushWords(List<Word> words) {
        String content = buildWordContent(words);
        push("每日单词", content);
    }

    /**
     * 推送任意标题+内容到微信。
     *
     * @param title   消息标题
     * @param content 消息内容（支持 Markdown）
     */
    public void push(String title, String content) {
        String url = "https://sctapi.ftqq.com/" + sendKey + ".send";

        HttpUtil.createPost(url)
                .form(Map.of("title", title, "desp", content))
                .execute();
    }

    private String buildWordContent(List<Word> words) {
        var sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            sb.append("**").append(i + 1).append(". ").append(w.word()).append("**")
                    .append("  ").append(w.phonetic()).append("\n")
                    .append("> ").append(w.partOfSpeech()).append("  ").append(w.meaning()).append("\n")
                    .append("> 例句: ").append(w.example()).append("\n\n");
        }
        return sb.toString();
    }
}