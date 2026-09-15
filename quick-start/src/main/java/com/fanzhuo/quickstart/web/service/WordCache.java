package com.fanzhuo.quickstart.web.service;

import com.fanzhuo.quickstart.web.word.Word;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

/**
 * 单词文件缓存：无数据库/Redis 时，用 JSON 文件做持久化。
 * <p>
 * 生成结果写入 {@code daily-words.json}（原子写：先写 .tmp 再 rename），
 * 推送时读取；文件损坏/缺失则返回 empty，由调用方决定回退。
 */
@Service
public class WordCache {

    private final ObjectMapper objectMapper;
    private final Path dir;
    private final Path cacheFile;

    public WordCache(ObjectMapper objectMapper,
                     @Value("${word.cache-dir:${user.home}/.word-push/cache}") String cacheDir) {
        this.objectMapper = objectMapper;
        this.dir = Path.of(cacheDir);
        this.cacheFile = dir.resolve("daily-words.json");
    }

    /** 原子写：先写临时文件再 rename，避免写到一半崩溃损坏缓存 */
    public synchronized void save(List<Word> words) throws IOException {
        Files.createDirectories(dir);
        Path tmp = dir.resolve("daily-words.json.tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), words);
        Files.move(tmp, cacheFile,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    public Optional<List<Word>> load() {
        if (!Files.exists(cacheFile)) {
            return Optional.empty();
        }
        try {
            List<Word> words = objectMapper.readValue(
                    cacheFile.toFile(), new TypeReference<List<Word>>() {});
            return words.isEmpty() ? Optional.empty() : Optional.of(words);
        } catch (IOException e) {
            return Optional.empty();   // 损坏就当没有，交给回退逻辑
        }
    }

    public String path() {
        return cacheFile.toString();
    }
}
