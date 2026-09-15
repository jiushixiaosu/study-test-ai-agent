package com.fanzhuo.quickstart.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * HttpClientService 实现类，基于 Spring 6 RestClient
 */
@Service
public class HttpClientServiceImpl implements HttpClientService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpClientServiceImpl() {
        this.restClient = RestClient.builder().build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public <T> T get(String url, Class<T> clazz) {
        return restClient.get()
                .uri(url)
                .retrieve()
                .body(clazz);
    }

    @Override
    public <T> T get(String url, Class<T> clazz, Map<String, ?> urlParam) {
        return restClient.get()
                .uri(url, urlParam)
                .retrieve()
                .body(clazz);
    }

    @Override
    public <T> ResponseEntity<T> get(String url, ParameterizedTypeReference<T> typeReference) {
        return restClient.get()
                .uri(url)
                .retrieve()
                .toEntity(typeReference);
    }

    @Override
    public <T> ResponseEntity<T> get(String url, HttpHeaders headers, ParameterizedTypeReference<T> typeReference) {
        return restClient.get()
                .uri(url)
                .headers(h -> h.addAll(headers))
                .retrieve()
                .toEntity(typeReference);
    }

    @Override
    public <T> T post(String url, Object requestBody, Class<T> clazz) {
        return restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(clazz);
    }

    @Override
    public <T> ResponseEntity<T> post(String url, Object requestBody, ParameterizedTypeReference<T> typeReference) {
        return restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .toEntity(typeReference);
    }

    @Override
    public <T> ResponseEntity<T> post(String url, Object requestBody, HttpHeaders headers, ParameterizedTypeReference<T> typeReference) {
        return restClient.post()
                .uri(url)
                .headers(h -> h.addAll(headers))
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .toEntity(typeReference);
    }
}
