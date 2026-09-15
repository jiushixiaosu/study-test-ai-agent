package com.fanzhuo.quickstart.web.word;

/**
 * 单词模型：AI 生成后由 BeanOutputConverter 按此结构反序列化。
 */
public record Word(
        /** 单词 */
        String word,
        /** 音标（IPA），如 /ˈæp.əl/ */
        String phonetic,
        /** 词性，如 n. / v. / adj. */
        String partOfSpeech,
        /** 中文释义 */
        String meaning,
        /** 一句简单英文例句 */
        String example
) {
}
