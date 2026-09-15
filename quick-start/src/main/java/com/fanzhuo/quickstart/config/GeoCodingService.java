package com.fanzhuo.quickstart.config;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 高德地图地理编码服务：把任意语言的城市名/地址解析为高德 adcode（行政区划代码）。
 * 天气 API 使用 adcode 查询，从根本上避免"中文城市名无法识别"的问题。
 */
@Service
public class GeoCodingService {

    private final HttpClientService httpClientService;
    private final String apiKey;
    private static final String GEO_URL = "https://restapi.amap.com/v3/geocode/geo";

    public GeoCodingService(HttpClientService httpClientService,
                           @Value("${amap.api-key}") String apiKey) {
        this.httpClientService = httpClientService;
        this.apiKey = apiKey;
    }

    /**
     * 解析城市名为高德 adcode
     *
     * @param address 城市名或地址，支持中文/英文/拼音，如 "厦门"、"xiamen"、"Xiamen"
     * @return 6 位 adcode（如 "350200"），解析失败返回 null
     */
    public String resolveAdcode(String address) {
        String url = UriComponentsBuilder.fromUriString(GEO_URL)
                .queryParam("key", apiKey)
                .queryParam("address", address)
                .build().encode().toUriString();

        JSONObject resp = httpClientService
                .get(url, HttpHeaders.EMPTY, new ParameterizedTypeReference<JSONObject>() {})
                .getBody();

        if (resp == null || !"1".equals(resp.getStr("status"))) {
            return null;
        }
        JSONArray geocodes = resp.getJSONArray("geocodes");
        if (geocodes == null || geocodes.isEmpty()) {
            return null;
        }
        return geocodes.getJSONObject(0).getStr("adcode");
    }
}
