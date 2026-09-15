package com.fanzhuo.quickstart.web.service;

import com.fanzhuo.quickstart.web.word.Word;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 单词源：调用大模型生成指定数量的英语单词，每个含音标/词性/释义/例句。
 * <p>
 * 使用 Spring AI 的 {@link BeanOutputConverter} 把模型输出直接反序列化成 {@code List<Word>}，
 * 比手写正则解析 JSON 稳定得多。为不干扰主对话的「工具/记忆」，这里独立构建了一个
 * 不带工具、不带记忆的 {@link ChatClient}，保证只返回结构化 JSON。
 */
@Service
public class AiWordSource {

    private final ChatClient chatClient;

    public AiWordSource(@Qualifier("deepSeekChatModel") ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    /**
     * 生成 count 个 level 水平的实用英语单词。
     *
     * @param count 单词数量
     * @param level 难度描述，如 "大学英语四级" / "日常口语"
     * @return 单词列表（每个含音标、词性、释义、例句）
     */
    public List<Word> generate(int count, String level) {
        var converter = new BeanOutputConverter<>(
                new ParameterizedTypeReference<List<Word>>() {});

        String prompt = """
                你是一名严谨的英语老师。
                你的任务：生成N个适合【指定英语水平】的实用英语单词。
                规则：
                1. 单词日常常见、实用，禁止生僻词；单词不可重复。
                2. 每个单词必须包含：单词、IPA音标、词性、中文释义。
                3. 每个单词附带一句简单地道英文例句，例句≤15个英文单词。
                4. 只输出纯JSON数组，禁止任何额外文字、解释、markdown、代码块、注释。
                5. 音标统一使用美式IPA音标；释义只保留最常用含义。
                6. 如果输入参数非法（数量≤0、水平不存在），返回空数组[]。
                输出 JSON 必须严格符合如下结构：
                %s
                """.formatted(count, level, converter.getFormat());

        return chatClient.prompt()
                .user(prompt)
                .call()
                .entity(converter);
    }
}
