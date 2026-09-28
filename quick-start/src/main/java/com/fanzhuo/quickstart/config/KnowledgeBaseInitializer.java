package com.fanzhuo.quickstart.config;

import cn.hutool.crypto.digest.DigestUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动时自动加载内置知识库（resources/knowledge/ 下的 md 文档）。
 * <p>
 * 流程：扫描 md → 内容指纹比对 → 变化才重新分块并向量化 → 落盘快照 + 写回指纹。
 * <p>
 * 关键设计（避免踩坑）：
 * 1. 幂等：库非空且指纹一致时直接跳过，实现「重启秒启动、零 Embedding API 调用」。
 * 2. 变更检测：对全部 md 的「文件名 + 内容」算 MD5。内容变了才重建，避免改了文档不生效。
 * 3. 重建前清空：指纹变化时先 clearAll()，否则旧分块残留会导致检索出重复/过期内容。
 * 4. 容错：全程 try-catch。Embedding API 不可用（网络/key/额度问题）时只打错误日志，
 *    不影响应用启动，只是 RAG 暂时无数据。
 */
@Component
public class KnowledgeBaseInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseInitializer.class);

    private final PersistentVectorStore vectorStore;
    private final ChineseRecursiveTextSplitter splitter;

    @Value("${rag.knowledge.enabled:true}")
    private boolean enabled;

    @Value("${rag.knowledge.location:classpath*:knowledge/*.md}")
    private String location;

    @Value("${rag.knowledge.fingerprint-file:${user.home}/.study-ai/knowledge.fingerprint}")
    private String fingerprintFilePath;

    public KnowledgeBaseInitializer(PersistentVectorStore vectorStore, ChineseRecursiveTextSplitter splitter) {
        this.vectorStore = vectorStore;
        this.splitter = splitter;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("知识库自动加载已关闭（rag.knowledge.enabled=false）");
            return;
        }
        try {
            loadKnowledgeBase();
        } catch (Exception e) {
            // 知识库加载失败不应拖垮整个应用
            log.error("知识库加载失败，RAG 将无数据（应用仍正常启动）", e);
        }
    }

    private void loadKnowledgeBase() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(location);
        if (resources.length == 0) {
            log.warn("未找到知识库文件，跳过加载。匹配规则：{}", location);
            return;
        }

        String currentHash = computeFingerprint(resources);
        String storedHash = readFingerprint();

        // 幂等：库里已有数据 且 文档指纹未变 → 直接用快照，零 API 调用
        if (!vectorStore.isEmpty() && currentHash.equals(storedHash)) {
            log.info("知识库未变化，跳过向量化（复用快照，当前 {} 个分块）", vectorStore.size());
            return;
        }

        log.info("开始加载知识库，共 {} 个文件...", resources.length);
        List<Document> allChunks = new ArrayList<>();
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            String text = resource.getContentAsString(StandardCharsets.UTF_8);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", filename);
            metadata.put("type", "knowledge");

            List<Document> chunks = splitter.split(new Document(text, metadata));
            allChunks.addAll(chunks);
            log.info("  - {} → {} 个分块", filename, chunks.size());
        }

        if (allChunks.isEmpty()) {
            log.warn("知识库分块结果为空（文档可能过短），跳过入库");
            return;
        }

        // 指纹变化时先清空，避免旧分块残留造成重复/过期内容
        if (!vectorStore.isEmpty()) {
            log.info("检测到知识库内容变化，清空旧数据后重建");
            vectorStore.clearAll();
        }

        vectorStore.add(allChunks);   // 触发持久化落盘
        writeFingerprint(currentHash);
        log.info("知识库加载完成：新增 {} 个分块，当前共 {} 个分块", allChunks.size(), vectorStore.size());
    }

    /** 对全部知识文件的「文件名 + 内容」计算 MD5，用于判断知识库是否变化。 */
    private String computeFingerprint(Resource[] resources) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Resource resource : resources) {
            sb.append(resource.getFilename())
              .append('|')
              .append(resource.getContentAsString(StandardCharsets.UTF_8))
              .append('\n');
        }
        return DigestUtil.md5Hex(sb.toString());
    }

    private String readFingerprint() {
        Path path = Paths.get(fingerprintFilePath);
        if (!Files.exists(path)) {
            return "";
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            log.warn("读取知识库指纹失败：{}", fingerprintFilePath, e);
            return "";
        }
    }

    private void writeFingerprint(String hash) {
        try {
            Path path = Paths.get(fingerprintFilePath);
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, hash, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入知识库指纹失败：" + fingerprintFilePath, e);
        }
    }
}
