package com.fanzhuo.quickstart.config;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * @author wulq
 * @date 2022-08-23
 */
public interface HttpClientService {

    /**
     * GET请求
     *
     * @param url   请求url地址
     * @param clazz 返回对象的类型
     * @param <T>   对象类型
     * @return 结果值
     */
    <T> T get(String url, Class<T> clazz);

    /**
     * GET请求
     *
     * @param url      请求url地址
     * @param clazz    返回对象的类型
     * @param <T>      对象类型
     * @param urlParam 请求参数
     * @return 结果值
     */
    <T> T get(String url, Class<T> clazz, Map<String, ?> urlParam);

    /**
     * GET请求
     *
     * @param url           请求url地址
     * @param typeReference 返回复杂对象的类型
     * @param <T>           对象类型
     * @return 结果值
     */
    <T> ResponseEntity<T> get(String url, ParameterizedTypeReference<T> typeReference);

    /**
     * GET请求
     *
     * @param url           请求url地址
     * @param headers       请求头
     * @param typeReference 返回复杂对象的类型
     * @param <T>           对象类型
     * @return
     */
    <T> ResponseEntity<T> get(String url, HttpHeaders headers, ParameterizedTypeReference<T> typeReference);

    /**
     * POST请求
     *
     * @param url         请求url地址
     * @param requestBody 请求参数
     * @param clazz       返回对象的类型
     * @param <T>         对象类型
     * @return 结果值
     */
    <T> T post(String url, Object requestBody, Class<T> clazz);

    /**
     * POST请求
     *
     * @param url           请求url地址
     * @param requestBody   请求参数
     * @param typeReference 返回复杂对象的类型
     * @param <T>           对象类型
     * @return 结果值
     */
    <T> ResponseEntity<T> post(String url, Object requestBody, ParameterizedTypeReference<T> typeReference);

    /**
     * POST请求
     *
     * @param url           请求url地址
     * @param requestBody   请求参数
     * @param headers       请求头
     * @param typeReference 返回复杂对象的类型
     * @param <T>           对象类型
     * @return 结果值
     */
    <T> ResponseEntity<T> post(String url, Object requestBody, HttpHeaders headers, ParameterizedTypeReference<T> typeReference);


}
