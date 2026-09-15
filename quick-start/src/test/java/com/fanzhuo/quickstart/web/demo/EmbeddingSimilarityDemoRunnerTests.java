package com.fanzhuo.quickstart.web.demo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingSimilarityDemoRunnerTests {

    @Test
    void cosineSimilarityReturnsOneForSameDirection() {
        double score = EmbeddingSimilarityDemoRunner.cosineSimilarity(
                new float[]{1.0F, 2.0F, 3.0F},
                new float[]{2.0F, 4.0F, 6.0F}
        );

        assertThat(score).isEqualTo(1.0D);
    }
}
