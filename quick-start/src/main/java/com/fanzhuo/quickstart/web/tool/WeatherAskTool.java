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

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WeatherAskTool {

    private static final Pattern HOUR_PATTERN = Pattern.compile("(\\d{1,2})(?:[:\\u70b9\\u65f6](\\d{1,2}))?");

    private final HttpClientService httpClientService;
    private final GeoCodingService geoCodingService;
    private final String apiKey;

    public WeatherAskTool(HttpClientService httpClientService,
                          GeoCodingService geoCodingService,
                          @Value("${amap.api-key}") String apiKey) {
        this.httpClientService = httpClientService;
        this.geoCodingService = geoCodingService;
        this.apiKey = apiKey;
    }

    @Tool(description = "Query weather by city and optional date. Empty date means current weather. " +
            "Supports Chinese/English/pinyin city names, today/tomorrow, and yyyy-MM-dd dates.")
    public JSONObject searchWeather(
            @ToolParam(description = "City name, e.g. Beijing, Xiamen, 厦门, shanghai") String city,
            @ToolParam(required = false, description = "Optional date. Supports yyyy-MM-dd, today, tomorrow, jintian/mingtian, 今天/明天.") String date,
            @ToolParam(required = false, description = "Optional time. Hourly forecast is not supported by the free API; only daily weather is returned.") String time) {
        try {
            return searchWeatherFromLocal(city, date, time);
        } catch (Exception e) {
            return new JSONObject()
                    .set("status", "error")
                    .set("message", e.getMessage());
        }
    }

    private JSONObject searchWeatherFromLocal(String city, String date, String time) {
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("city must not be blank");
        }

        // 1. 地理编码：中文/拼音/英文城市名 -> 高德 adcode（根本解决中文城市识别问题）
        String adcode = geoCodingService.resolveAdcode(city);
        if (adcode == null || adcode.isBlank()) {
            throw new IllegalArgumentException("无法识别城市：" + city + "，请检查城市名称是否正确");
        }

        LocalDate queryDate = parseQueryDate(date);
        LocalTime queryTime = parseQueryTime(time);
        LocalDate today = LocalDate.now();

        if (queryDate == null && queryTime == null) {
            return requestRealtime(adcode, city);
        }
        if (queryDate == null) {
            queryDate = today;
        }
        long offsetDays = ChronoUnit.DAYS.between(today, queryDate);
        if (offsetDays < 0) {
            throw new IllegalArgumentException("仅支持查询今天或未来的天气。date=" + date);
        }

        if (queryTime != null) {
            // 高德免费天气不提供逐时预报，降级为当天/指定日预报并提示
            JSONObject result = requestForecast(adcode, city, queryDate);
            result.set("note", "高德免费天气仅支持按天预报，已返回 " + queryDate + " 的天气（逐时数据不可用）");
            return result;
        }

        if (offsetDays == 0) {
            return requestRealtime(adcode, city);
        }
        return requestForecast(adcode, city, queryDate);
    }

    private JSONObject requestRealtime(String adcode, String city) {
        String url = buildWeatherUrl(adcode, "base");
        JSONObject resp = doGet(url);
        validate(resp);

        JSONObject result = new JSONObject();
        JSONArray lives = resp.getJSONArray("lives");
        if (lives != null && !lives.isEmpty()) {
            JSONObject live = lives.getJSONObject(0);
            result.set("weather", live.getStr("weather"))
                    .set("temperature", live.getStr("temperature"))
                    .set("wind_direction", live.getStr("winddirection"))
                    .set("wind_power", live.getStr("windpower"))
                    .set("humidity", live.getStr("humidity"))
                    .set("report_time", live.getStr("reporttime"));
        }
        return decorate(result, "realtime", city, null);
    }

    private JSONObject requestForecast(String adcode, String city, LocalDate queryDate) {
        String url = buildWeatherUrl(adcode, "all");
        JSONObject resp = doGet(url);
        validate(resp);

        JSONObject result = new JSONObject();
        JSONArray forecasts = resp.getJSONArray("forecasts");
        if (forecasts != null && !forecasts.isEmpty()) {
            JSONArray casts = forecasts.getJSONObject(0).getJSONArray("casts");
            boolean found = false;
            for (int i = 0; i < casts.size(); i++) {
                JSONObject cast = casts.getJSONObject(i);
                if (queryDate.toString().equals(cast.getStr("date"))) {
                    fillCast(result, cast);
                    found = true;
                    break;
                }
            }
            if (!found && !casts.isEmpty()) {
                // 超出预报范围，返回最近可用的一天
                fillCast(result, casts.getJSONObject(0));
                result.set("note", "请求日期超出免费预报范围，已返回最近可用预报");
            }
        }
        return decorate(result, "forecast", city, queryDate);
    }

    private void fillCast(JSONObject result, JSONObject cast) {
        result.set("date", cast.getStr("date"))
                .set("day_weather", cast.getStr("dayweather"))
                .set("day_temp", cast.getStr("daytemp"))
                .set("night_temp", cast.getStr("nighttemp"))
                .set("day_wind", cast.getStr("daywind"))
                .set("night_wind", cast.getStr("nightwind"));
    }

    private String buildWeatherUrl(String adcode, String extensions) {
        return UriComponentsBuilder.fromUriString("https://restapi.amap.com/v3/weather/weatherInfo")
                .queryParam("key", apiKey)
                .queryParam("city", adcode)
                .queryParam("extensions", extensions)
                .queryParam("output", "json")
                .build().encode().toUriString();
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
            throw new IllegalStateException("高德天气请求失败：" + resp.getStr("info"));
        }
    }

    private JSONObject decorate(JSONObject result, String type, String city, LocalDate date) {
        result.set("query_type", type)
                .set("query_city", city);
        if (date != null) {
            result.set("query_date", date.toString());
        }
        return result;
    }

    private LocalDate parseQueryDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        String normalized = date.trim().toLowerCase();
        LocalDate today = LocalDate.now();
        return switch (normalized) {
            case "today", "jintian", "\u4eca\u5929", "\u4eca\u65e5" -> today;
            case "tomorrow", "mingtian", "\u660e\u5929", "\u660e\u65e5" -> today.plusDays(1);
            case "houtian", "\u540e\u5929" -> today.plusDays(2);
            case "dahoutian", "\u5927\u540e\u5929" -> today.plusDays(3);
            default -> parseAbsoluteDate(normalized);
        };
    }

    private LocalDate parseAbsoluteDate(String date) {
        String normalized = date.replace('/', '-');
        for (DateTimeFormatter formatter : new DateTimeFormatter[]{
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("yyyy-M-d")
        }) {
            try {
                return LocalDate.parse(normalized, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        throw new IllegalArgumentException("Unsupported date format: " + date
                + ". Please use yyyy-MM-dd, today, tomorrow, jintian, mingtian, or Chinese date words.");
    }

    private LocalTime parseQueryTime(String time) {
        if (time == null || time.isBlank()) {
            return null;
        }
        String normalized = time.trim().toLowerCase();
        Matcher matcher = HOUR_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Unsupported time format: " + time + ". Please use HH:mm or natural hour text.");
        }
        int hour = Integer.parseInt(matcher.group(1));
        int minute = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new IllegalArgumentException("Invalid time: " + time);
        }
        if (hour <= 11 && isAfternoonOrEvening(normalized)) {
            hour += 12;
        }
        return LocalTime.of(hour, minute);
    }

    private boolean isAfternoonOrEvening(String text) {
        return text.contains("pm")
                || text.contains("afternoon")
                || text.contains("evening")
                || text.contains("xiawu")
                || text.contains("wanshang")
                || text.contains("\u4e0b\u5348")
                || text.contains("\u665a\u4e0a")
                || text.contains("\u665a");
    }
}
