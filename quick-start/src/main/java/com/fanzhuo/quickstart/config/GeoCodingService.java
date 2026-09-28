package com.fanzhuo.quickstart.config;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 高德地图地理编码服务：把任意语言的城市名/地址解析为高德 adcode（行政区划代码）。
 * 天气 API 使用 adcode 查询，从根本上避免"中文城市名无法识别"的问题。
 * <p>
 * 两个关键实现约束（都曾导致"无法识别城市"的误报）：
 * <ol>
 *   <li><b>用 Hutool HttpUtil 发送，而不是 RestClient</b>：URL 已由
 *       {@code build().encode()} 完成百分号编码，若再交给 RestClient 的
 *       {@code uri(String)}（把参数当 URI 模板再次处理），存在二次编码风险，
 *       使高德收到乱码地址而返回空结果。</li>
 *   <li><b>响应先用 String 接收再手动解析</b>：不能写成
 *       {@code new ParameterizedTypeReference<JSONObject>() {}}，
 *       因为 Jackson 无法正确反序列化 Hutool 的 JSONObject（会按 POJO 模式
 *       处理并得到空对象），导致 status 取不到值。</li>
 * </ol>
 */
@Service
public class GeoCodingService {

    private static final Logger log = LoggerFactory.getLogger(GeoCodingService.class);

    private final String apiKey;
    private static final String GEO_URL = "https://restapi.amap.com/v3/geocode/geo";

    public GeoCodingService(@Value("${amap.api-key}") String apiKey) {
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

        String body;
        try {
            body = HttpUtil.get(url, 5000);
        } catch (Exception e) {
            log.error("地理编码请求异常, address={}, url={}", address, url, e);
            return null;
        }

        JSONObject resp = parseBody(body);
        if (resp == null || !"1".equals(resp.getStr("status"))) {
            log.warn("地理编码失败, address={}, url={}, 响应={}", address, url, body);
            return null;
        }

        JSONArray geocodes = resp.getJSONArray("geocodes");
        if (geocodes == null || geocodes.isEmpty()) {
            log.warn("地理编码无匹配结果, address={}, 响应={}", address, body);
            return null;
        }

        String adcode = geocodes.getJSONObject(0).getStr("adcode");
        log.info("地理编码成功, address={} -> adcode={}", address, adcode);
        return adcode;
    }

    /** 解析响应体为 JSONObject；失败返回 null 而非抛异常，保证调用方走既有的降级分支。 */
    private JSONObject parseBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JSONUtil.parseObj(body);
        } catch (Exception e) {
            log.error("解析地理编码响应失败, body={}", body, e);
            return null;
        }
    }
}
