package com.fanzhuo.quickstart.web.tool;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ImageSearchTool {

    private static final Logger log = LoggerFactory.getLogger(ImageSearchTool.class);

    @Value("${pexels.api-key}")
    private String apiKey;

    private static final String API_URL = "https://api.pexels.com/v1/search";
    // 请求超时（毫秒）：连接 5s，读取 10s，避免外部 API hang 住拖垮对话
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 10000;

    @Tool(description = "search image from web")
    public String searchImage(@ToolParam(description = "Search query keyword") String query) {
        try {
            return String.join(",", searchMediumImages(query));
        } catch (Exception e) {
            // 脱敏：不向前端/AI 泄露原始异常（可能含 key 或堆栈）
            log.error("图片查询失败, query={}", query, e);
            return "图片查询失败，请稍后重试";
        }
    }

    public List<String> searchMediumImages(String query) {
        // 设置请求参数
        Map<String, Object> params = new HashMap<>();
        params.put("query", query);

        // 发送GET请求（Hutool GET 请求的 form 方法会将参数拼接为 query string）
        // 注意：Pexels 要求 Authorization 头带 Bearer 前缀；并显式设置超时
        String response = HttpRequest.get(API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .form(params)
                .setConnectionTimeout(CONNECT_TIMEOUT)
                .setReadTimeout(READ_TIMEOUT)
                .execute()
                .body();

        // 解析响应JSON
        return JSONUtil.parseObj(response)
                .getJSONArray("photos")
                .stream()
                .map(photoObj -> (JSONObject) photoObj)
                .map(photoObj -> photoObj.getJSONObject("src"))
                .map(photo -> photo.getStr("medium"))
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
    }
}

