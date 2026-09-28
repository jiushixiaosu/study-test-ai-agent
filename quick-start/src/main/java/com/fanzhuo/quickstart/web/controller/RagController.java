package com.fanzhuo.quickstart.web.controller;

import com.fanzhuo.quickstart.config.ChineseRecursiveTextSplitter;
import com.fanzhuo.quickstart.config.PersistentVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 知识库管理接口（运行时动态灌文档，与启动时自动加载互补）。
 * <p>
 * 启动加载负责「静态基线知识」（resources/knowledge/*.md），
 * 本接口负责「运行时补充」（临时资料、用户上传的文本/文件）。
 * <p>
 * 所有写入都会走 PersistentVectorStore 自动落盘，重启不丢。
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final PersistentVectorStore vectorStore;
    private final ChineseRecursiveTextSplitter splitter;

    public RagController(PersistentVectorStore vectorStore, ChineseRecursiveTextSplitter splitter) {
        this.vectorStore = vectorStore;
        this.splitter = splitter;
    }

    /**
     * 灌入文本（自动分块后向量化）。
     * 示例：POST /api/rag/documents
     * body: {"texts":["话术内容1","话术内容2"],"source":"自定义话术"}
     */
    @PostMapping(value = "/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> addDocuments(@RequestBody AddDocumentsRequest request) {
        if (request.texts() == null || request.texts().isEmpty()) {
            return Map.of("added", 0, "total", vectorStore.size(), "message", "texts 不能为空");
        }

        String source = (request.source() == null || request.source().isBlank())
                ? "api" : request.source();

        List<Document> documents = new ArrayList<>();
        for (String text : request.texts()) {
            if (text == null || text.isBlank()) {
                continue;
            }
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", source);
            metadata.put("type", "api");
            documents.add(new Document(text, metadata));
        }

        if (documents.isEmpty()) {
            return Map.of("added", 0, "total", vectorStore.size(), "message", "无有效文本");
        }

        List<Document> chunks = splitter.split(documents);
        vectorStore.add(chunks);
        log.info("RAG 接口灌入 {} 条文本 → {} 个分块，当前共 {} 个分块",
                documents.size(), chunks.size(), vectorStore.size());

        return Map.of("added", chunks.size(), "total", vectorStore.size());
    }

    /**
     * 上传 txt / md 文件灌入（读取 → 分块 → 向量化）。
     * 示例：POST /api/rag/documents/file  (multipart/form-data, 参数名 file)
     */
    @PostMapping(value = "/documents/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> addFromFile(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return Map.of("added", 0, "total", vectorStore.size(), "message", "文件为空");
        }

        String filename = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String text = new String(file.getBytes(), StandardCharsets.UTF_8);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", filename);
        metadata.put("type", "upload");

        List<Document> chunks = splitter.split(new Document(text, metadata));
        vectorStore.add(chunks);
        log.info("RAG 接口上传文件 {} → {} 个分块，当前共 {} 个分块", filename, chunks.size(), vectorStore.size());

        return Map.of("source", filename, "added", chunks.size(), "total", vectorStore.size());
    }

    /** 查看当前向量库分块数量。示例：GET /api/rag/count */
    @GetMapping("/count")
    public Map<String, Object> count() {
        return Map.of("count", vectorStore.size(), "empty", vectorStore.isEmpty());
    }

    /** 清空整个向量库（含快照文件）。示例：DELETE /api/rag/documents */
    @DeleteMapping("/documents")
    public Map<String, Object> clear() {
        int before = vectorStore.size();
        vectorStore.clearAll();
        log.warn("RAG 向量库已被清空，移除 {} 个分块", before);
        return Map.of("removed", before, "count", vectorStore.size());
    }

    /** 请求体：texts 为待灌入文本列表，source 用于标注来源（可选）。 */
    public record AddDocumentsRequest(List<String> texts, String source) {
    }
}
