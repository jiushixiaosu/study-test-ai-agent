package com.fanzhuo.quickstart.web.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class DocumentIndexService {

    private final VectorStore vectorStore;

    public DocumentIndexService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void indexDocument(String id, String content, Map<String, Object> metadata) {
        Document document = new Document(id, content, metadata);
        vectorStore.add(List.of(document));
    }

    public void batchIndexDocuments(List<Document> documents) {
        vectorStore.add(documents);
    }

    public void deleteDocument(String id) {
        vectorStore.delete(Collections.singletonList(id));
    }
}

