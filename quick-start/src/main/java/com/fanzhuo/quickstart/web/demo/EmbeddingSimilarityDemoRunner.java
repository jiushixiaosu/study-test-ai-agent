package com.fanzhuo.quickstart.web.demo;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

//@Component
public class EmbeddingSimilarityDemoRunner implements CommandLineRunner {

    private final EmbeddingModel embeddingModel;

    public EmbeddingSimilarityDemoRunner(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void run(String... args) {
        float[] vec1 = embeddingModel.embed("文本1");
        float[] vec2 = embeddingModel.embed("文本2");
        double score = cosineSimilarity(vec1, vec2);
        System.out.println("文本1 与 文本2 的相似度: " + score);
    }

    static double cosineSimilarity(float[] left, float[] right) {
        double dotProduct = 0.0D;
        double leftNorm = 0.0D;
        double rightNorm = 0.0D;

        for (int i = 0; i < left.length; i++) {
            dotProduct += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }

        return dotProduct / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
