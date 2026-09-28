package com.fanzhuo.quickstart.web.tool;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fanzhuo.quickstart.config.GeoCodingService;
import com.fanzhuo.quickstart.config.HttpClientService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 高德 POI 地点搜索工具：按具体地点关键词 + 城市搜索真实 POI（餐厅/咖啡馆/电影院/公园等），
 * 返回结构化字段（名称/地址/电话/类型/评分/人均/营业时间），供对话模型直接引用作答。
 *
 * <p>两种检索模式：
 * <ul>
 *   <li><strong>普通模式</strong>：keywords 为具体地点类型或名称（如"咖啡馆""火锅"），
 *       直接按关键词搜索并截取少量字段返回；</li>
 *   <li><strong>场景推荐模式</strong>：keywords 命中抽象场景词（约会/浪漫/好去处等）且提供了城市时，
 *       自动映射为多类型轮询（咖啡厅/西餐厅/火锅/日料/电影院/公园/酒吧/海边），
 *       剔除与场景无关类型（住宅/美容美发等），按评分 ≥4.5 过滤并降序，聚合出"优质候选清单"，
 *       解决"高德按名称匹配、抽象词返回跑偏结果"的问题。</li>
 * </ul>
 */
@Service
public class PoiSearchTool {

    private static final String POI_URL = "https://restapi.amap.com/v3/place/text";
    private static final int PAGE_SIZE = 10;

    /** 抽象场景词：命中且不含具体类型词时，走场景推荐模式 */
    private static final List<String> SCENE_WORDS = List.of(
            "约会", "浪漫", "情侣", "好去处", "好玩", "打卡", "周末", "去哪", "哪里", "适合");

    /** 具体类型词：命中则走普通精确检索，避免误伤"推荐一家川菜馆"这类含抽象词的精确查询 */
    private static final List<String> SPECIFIC_WORDS = List.of(
            "咖啡", "餐厅", "餐吧", "火锅", "日料", "烧烤", "电影", "影院", "酒吧", "酒馆",
            "公园", "海边", "沙滩", "景区", "书店", "甜品", "奶茶", "下午茶", "西餐", "中餐",
            "私房菜", "海鲜", "大排档", "密室", "桌游", "手工", "陶艺", "游乐场", "夜宵", "茶室");

    /** 场景推荐候选类型池：每类各查几条后聚合（覆盖常见约会场景） */
    private static final String[] SCENE_POOL = {
            "咖啡厅", "西餐厅", "火锅", "日料", "电影院", "公园", "酒吧", "海边"
    };
    private static final int SCENE_PER_TYPE = 4;
    private static final int SCENE_MAX = 10;

    /** 店铺质量阈值：低于该评分不进"优质候选"（无评分的公园/海边等公共空间保留但排后） */
    private static final double MIN_RATING = 4.5;

    /** 与场景无关的类型黑名单（按高德 type 字段子串匹配） */
    private static final List<String> TYPE_BLACKLIST = List.of(
            "住宅", "小区", "别墅", "宿舍", "美发", "美容", "洗浴", "推拿", "汽车", "房产");

    private final HttpClientService httpClientService;
    private final GeoCodingService geoCodingService;
    private final String apiKey;

    public PoiSearchTool(HttpClientService httpClientService,
                         GeoCodingService geoCodingService,
                         @Value("${amap.api-key}") String apiKey) {
        this.httpClientService = httpClientService;
        this.geoCodingService = geoCodingService;
        this.apiKey = apiKey;
    }

    @Tool(description = "Search real POI places in a city. Two modes: (1) SPECIFIC keyword mode - pass a concrete " +
            "place type or brand (咖啡馆/西餐厅/电影院/公园/海边/密室) and get that category's top results with " +
            "name/district/address/tel/type/rating/cost/open_time. (2) SCENE mode - for abstract requests like " +
            "约会地点/浪漫去处/周末好去处/情侣推荐, pass the abstract phrase as keywords PLUS a city; the tool " +
            "auto-expands to multiple categories (cafe/western/firepot/japanese-food/cinema/park/bar/seaside), " +
            "filters out irrelevant types (residences/hair salons) and low ratings, and returns a quality-curated " +
            "shortlist for dating recommendations. Always prefer providing a city (e.g. 厦门).")
    public JSONObject searchPoi(
            @ToolParam(description = "Place keyword. Either a concrete type/name (咖啡馆/火锅/电影院/公园) or an " +
                    "abstract scene phrase (约会/浪漫/好去处/周末去哪玩).") String keywords,
            @ToolParam(required = false, description = "City name in Chinese/English/pinyin, e.g. 厦门, xiamen. " +
                    "Required for SCENE mode; recommended for specific keyword mode.") String city) {
        try {
            if (keywords != null && isSceneQuery(keywords)) {
                if (city == null || city.isBlank()) {
                    return new JSONObject()
                            .set("status", "ok")
                            .set("count", 0)
                            .set("results", new JSONArray())
                            .set("note", "推荐/约会类查询需要城市名（city 参数），请带上城市后再调一次，如 city=厦门");
                }
                return sceneSearch(keywords, city);
            }
            return searchPoiFromLocal(keywords, city);
        } catch (Exception e) {
            return new JSONObject()
                    .set("status", "error")
                    .set("message", e.getMessage());
        }
    }

    // ==================== 场景推荐模式 ====================

    private boolean isSceneQuery(String keywords) {
        boolean specific = SPECIFIC_WORDS.stream().anyMatch(keywords::contains);
        if (specific) {
            return false;
        }
        return SCENE_WORDS.stream().anyMatch(keywords::contains);
    }

    private JSONObject sceneSearch(String keywords, String city) {
        String adcode = geoCodingService.resolveAdcode(city);
        if (adcode == null || adcode.isBlank()) {
            throw new IllegalArgumentException("无法识别城市：" + city + "，请检查城市名称是否正确");
        }

        // 多类型轮询聚合（单个类型失败不影响其他类型）
        List<JSONObject> candidates = new ArrayList<>();
        for (String type : SCENE_POOL) {
            JSONObject resp = queryOne(type, adcode);
            JSONArray pois = resp.getJSONArray("pois");
            if (pois == null) {
                continue;
            }
            int n = Math.min(pois.size(), SCENE_PER_TYPE);
            for (int i = 0; i < n && candidates.size() < SCENE_MAX * 4; i++) {
                candidates.add(pois.getJSONObject(i));
            }
        }

        // 1) 剔除无关类型
        List<JSONObject> kept = new ArrayList<>();
        for (JSONObject p : candidates) {
            String type = nvl(p.getStr("type"));
            boolean banned = TYPE_BLACKLIST.stream().anyMatch(type::contains);
            if (!banned) {
                kept.add(p);
            }
        }

        // 2) 评分过滤 + 排序：有评分且 >= 阈值按降序在前；无评分（公园/海边等）保留垫底、最多补 3 条
        List<JSONObject> rated = new ArrayList<>();
        List<JSONObject> unrated = new ArrayList<>();
        for (JSONObject p : kept) {
            Double rating = ratingOf(p);
            if (rating == null) {
                unrated.add(p);
            } else if (rating >= MIN_RATING) {
                rated.add(p);
            }
        }
        rated.sort(Comparator.comparingDouble((JSONObject p) -> ratingOf(p)).reversed());

        List<JSONObject> merged = new ArrayList<>(rated);
        for (int i = 0; i < Math.min(3, unrated.size()); i++) {
            merged.add(unrated.get(i));
        }
        if (merged.size() > SCENE_MAX) {
            merged = new ArrayList<>(merged.subList(0, SCENE_MAX));
        }

        JSONArray results = new JSONArray();
        for (JSONObject p : merged) {
            results.add(buildBrief(p));
        }
        return new JSONObject()
                .set("status", "ok")
                .set("query_keywords", keywords)
                .set("query_city", city)
                .set("mode", "scene_recommend")
                .set("count", results.size())
                .set("note", "场景推荐模式：聚合了" + SCENE_POOL.length + "类场所，已剔除住宅/美容美发等无关类型，" +
                        "店铺按评分≥" + MIN_RATING + "过滤（无评分的公园/海边等公共空间排在后部）")
                .set("results", results);
    }

    private JSONObject queryOne(String keyword, String adcode) {
        String url = UriComponentsBuilder.fromUriString(POI_URL)
                .queryParam("key", apiKey)
                .queryParam("keywords", keyword)
                .queryParam("city", adcode)
                .queryParam("citylimit", "true")
                .queryParam("offset", SCENE_PER_TYPE)
                .queryParam("page", 1)
                .queryParam("extensions", "all")
                .queryParam("output", "json")
                .build().encode().toUriString();
        JSONObject resp = doGet(url);
        if (!"1".equals(resp.getStr("status"))) {
            return new JSONObject(); // 该类型查询失败，跳过
        }
        return resp;
    }

    private Double ratingOf(JSONObject poi) {
        Object biz = poi.get("biz_ext");
        if (!(biz instanceof JSONObject bizExt)) {
            return null;
        }
        String s = bizExt.getStr("rating");
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 普通精确检索模式 ====================

    private JSONObject searchPoiFromLocal(String keywords, String city) {
        if (keywords == null || keywords.isBlank()) {
            throw new IllegalArgumentException("keywords must not be blank");
        }

        UriComponentsBuilder urlBuilder = UriComponentsBuilder.fromUriString(POI_URL)
                .queryParam("key", apiKey)
                .queryParam("keywords", keywords)
                .queryParam("offset", PAGE_SIZE)
                .queryParam("page", 1)
                .queryParam("extensions", "all")
                .queryParam("output", "json");

        String cityLabel = "全国";
        if (city != null && !city.isBlank()) {
            // 城市名 -> adcode，并用 citylimit=true 严格限定结果在城市内
            String adcode = geoCodingService.resolveAdcode(city);
            if (adcode == null || adcode.isBlank()) {
                throw new IllegalArgumentException("无法识别城市：" + city + "，请检查城市名称是否正确");
            }
            urlBuilder.queryParam("city", adcode).queryParam("citylimit", "true");
            cityLabel = city;
        }
        String url = urlBuilder.build().encode().toUriString();

        JSONObject resp = doGet(url);
        validate(resp);

        JSONObject result = new JSONObject()
                .set("status", "ok")
                .set("query_keywords", keywords)
                .set("query_city", cityLabel);

        JSONArray pois = resp.getJSONArray("pois");
        if (pois == null || pois.isEmpty()) {
            result.set("count", 0)
                    .set("results", new JSONArray())
                    .set("note", "未找到匹配的地点，请尝试更具体的关键词（如 咖啡馆/火锅/电影院）");
            return result;
        }

        JSONArray results = new JSONArray();
        int max = Math.min(pois.size(), PAGE_SIZE);
        for (int i = 0; i < max; i++) {
            results.add(buildBrief(pois.getJSONObject(i)));
        }
        return result.set("count", results.size()).set("results", results);
    }

    /**
     * 把单个 POI 压缩为少量字段，避免全量塞入对话上下文浪费 token。
     */
    private JSONObject buildBrief(JSONObject poi) {
        JSONObject brief = new JSONObject();
        brief.set("name", poi.getStr("name"));
        brief.set("district", nvl(poi.getStr("adname")));
        brief.set("address", nvl(poi.getStr("address")));
        brief.set("tel", nvl(poi.getStr("tel")));
        brief.set("type", firstCategory(poi.getStr("type")));
        brief.set("location", nvl(poi.getStr("location")));

        // biz_ext 仅在餐饮/酒店/景点/影院类 POI 下存在；部分场景可能是空数组，需类型安全取值
        Object biz = poi.get("biz_ext");
        if (biz instanceof JSONObject bizExt) {
            String openTime = bizExt.getStr("opentime2");
            if (openTime == null) {
                openTime = bizExt.getStr("open_time");
            }
            brief.set("rating", bizExt.getStr("rating"));
            brief.set("cost", bizExt.getStr("cost"));
            brief.set("open_time", openTime);
        }
        return brief;
    }

    private String firstCategory(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        // type 形如 "餐饮服务;咖啡厅;咖啡厅"，只保留大类即可让模型理解
        int semi = type.indexOf(';');
        return semi > 0 ? type.substring(0, semi) : type;
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    private JSONObject doGet(String url) {
        // 用 Hutool 直接发送，避免 RestClient 对已编码 URL 再次处理（双重编码风险）
        String body = HttpUtil.get(url, 8000);
        if (body == null || body.isBlank()) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(body);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void validate(JSONObject resp) {
        if (!"1".equals(resp.getStr("status"))) {
            throw new IllegalStateException("高德 POI 搜索失败：" + resp.getStr("info"));
        }
    }
}
