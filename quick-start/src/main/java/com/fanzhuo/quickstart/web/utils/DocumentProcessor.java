package com.fanzhuo.quickstart.web.utils;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

//@Component
public class DocumentProcessor {

    // 将文本分割成较小的块，便于向量化
    public List<Document> splitTextIntoDocuments(String text, String metadata) {
        List<Document> documents = new ArrayList<>();
        // 假设我们按段落分割
        String[] paragraphs = text.split("\n\n");

        for (int i = 0; i < paragraphs.length; i++) {
            if (paragraphs[i].trim().length() > 10) { // 忽略太短的段落
                Map<String, Object> meta = new HashMap<>();
                meta.put("index", i);
                meta.put("source", metadata);

                documents.add(new Document(
                        UUID.randomUUID().toString(),
                        paragraphs[i],
                        meta
                ));
            }
        }


        return documents;
    }

    // 从文件创建文档
    public List<Document> createDocumentsFromFile(File file) throws IOException {
        String content = new String(Files.readAllBytes(file.toPath()));
        return splitTextIntoDocuments(content, file.getName());
    }
}

