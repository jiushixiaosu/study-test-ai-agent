package com.fanzhuo.quickstart.web.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 路线 B：直接实现 ChatMemory 接口，把完整对话历史持久化到 MySQL，并用 Redis 做热会话缓存。
 *
 * 注意：本类【不】加 @Service/@Component，由 ChatClientConfig.chatMemory() 这个
 * @Bean 工厂方法创建，从而让 windowSize 的 @Value 正确注入。若被组件扫描直接
 * 实例化，构造器上的裸 int 参数无法注入，会报 "required a bean of type 'int'"。
 *
 * 与 MessageWindowChatMemory 的区别：
 * - add() 采用「追加写」而非「先删后插」，完整历史全部保留在 chat_memory 表（审计轨迹）。
 * - get() 只返回最近 windowSize 条，作为喂给大模型的提示词上下文窗口，避免上下文无限膨胀。
 * - getAll() 提供绕过窗口、取出某会话全量历史的能力（审计 / 复盘用，直连 MySQL 不走缓存）。
 *
 * 存储策略（Cache-Aside）：
 * - MySQL 是【权威源】，永远保存全量历史；
 * - Redis（{@link ChatMemoryCache}）是【缓存层】，只保存窗口内的热数据；
 * - 缓存未命中或 Redis 异常时，一律回退到 MySQL，保证功能不受影响。
 *
 * 存储采用「type + content + metadata 结构化字段」而非整对象 JSON：
 * 直接 readValue(json, UserMessage.class) 会失败。按 type 用已知构造器重建最稳。
 */
public class MysqlChatMemory implements ChatMemory {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final int windowSize;

    /** Redis 缓存层；为 null 表示未启用缓存（全部走 MySQL） */
    private final ChatMemoryCache cache;

    public MysqlChatMemory(JdbcTemplate jdbcTemplate,
                           ObjectMapper objectMapper,
                           int windowSize,
                           ChatMemoryCache cache) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.windowSize = windowSize;
        this.cache = cache;
    }

    @Override
    @Transactional
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        // ① 生成起始序号：优先用 Redis INCR（原子，省掉 SELECT MAX）
        //    计数器不存在时（首次或 TTL 过期）会用 nextOrderFromDb 惰性初始化，避免与库中已有序号冲突
        Integer start = null;
        if (cache != null) {
            Long seq = cache.nextOrder(conversationId, messages.size(),
                    () -> nextOrderFromDb(conversationId));
            if (seq != null) {
                start = seq.intValue();
            }
        }
        // 降级：缓存未启用或 Redis 异常 → 回退为查询当前最大序号
        if (start == null) {
            start = nextOrderFromDb(conversationId).intValue();
        }

        // ② 首条消息（该会话第一次写入）时，用第一条用户提问作为会话标题
        String title = null;
        if (start == 0) {
            title = messages.stream()
                    .filter(m -> m.getMessageType() == MessageType.USER)
                    .map(Message::getText)
                    .filter(t -> t != null && !t.isBlank())
                    .findFirst()
                    .map(t -> t.length() > 64 ? t.substring(0, 64) : t)
                    .orElse(null);
        }

        // ③ 写入 MySQL（权威源，全量保留）
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            String content = m.getText();
            if (content == null) {
                content = ""; // content 设为 NOT NULL，null 时落空串
            }
            jdbcTemplate.update(
                    "INSERT INTO chat_memory(conversation_id, title, msg_order, msg_type, content, metadata) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    conversationId,
                    title,
                    start + i,
                    m.getMessageType().name(),
                    content,
                    toJson(m.getMetadata()));
        }

        // ④ 更新缓存（追加 + LTRIM 保持窗口）
        if (cache != null) {
            cache.putMessages(conversationId, messages, true);
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        // ① 缓存命中 → 直接返回，零 DB 查询
        if (cache != null) {
            List<Message> cached = cache.getMessages(conversationId);
            if (cached != null) {
                return cached;
            }
        }

        // ② 未命中 → 只查窗口内的 N 条（SQL 层裁剪，避免读全量再丢弃）
        List<Message> window = queryWindow(conversationId, windowSize);

        // ③ 回填缓存，供后续请求命中
        if (cache != null && !window.isEmpty()) {
            cache.putMessages(conversationId, window, false);
        }
        return window;
    }

    @Override
    public void clear(String conversationId) {
        jdbcTemplate.update("DELETE FROM chat_memory WHERE conversation_id = ?", conversationId);
        if (cache != null) {
            cache.evict(conversationId);
        }
    }

    /**
     * 审计用途：取出该会话的完整历史（不走窗口裁剪，也不走缓存）。
     * 缓存只保存窗口内的热数据，因此全量回放必须直连 MySQL。
     */
    public List<Message> getAll(String conversationId) {
        return jdbcTemplate.query(
                "SELECT msg_type, content, metadata FROM chat_memory "
                        + "WHERE conversation_id = ? ORDER BY msg_order ASC",
                ROW_MAPPER, conversationId);
    }

    /**
     * 列出所有会话（去重 conversation_id），含消息条数与最近活跃时间。
     * 供前端「历史会话列表」使用。
     * <p>
     * 说明：此接口属低频调用（仅打开页面时），暂不加缓存；表上已有
     * uk_conv_order(conversation_id, msg_order) 复合索引可用。
     */
    public List<ConversationSummary> listConversations() {
        return jdbcTemplate.query(
                "SELECT conversation_id, MAX(title) AS title, COUNT(*) AS msg_count, "
                        + "MAX(created_at) AS last_active "
                        + "FROM chat_memory GROUP BY conversation_id ORDER BY last_active DESC",
                (rs, rowNum) -> new ConversationSummary(
                        rs.getString("conversation_id"),
                        rs.getString("title"),
                        rs.getInt("msg_count"),
                        rs.getTimestamp("last_active").toLocalDateTime()));
    }

    /** 会话列表项（轻量，不含消息体）。 */
    public record ConversationSummary(String conversationId, String title, int msgCount, java.time.LocalDateTime lastActive) {
    }

    /**
     * 从 MySQL 取下一个可用序号（当前 MAX(msg_order) + 1）。
     * 用于 Redis 计数器的惰性初始化，以及缓存不可用时的降级。
     */
    private Long nextOrderFromDb(String conversationId) {
        Integer maxOrder = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(msg_order), -1) FROM chat_memory WHERE conversation_id = ?",
                Integer.class, conversationId);
        return (maxOrder == null ? -1L : maxOrder.longValue()) + 1;
    }

    /**
     * 只取最近 limit 条（按 msg_order 升序返回，符合对话上下文顺序）。
     * <p>
     * 用「子查询 DESC + LIMIT 再外层 ASC」的方式在 SQL 层完成裁剪，
     * 避免旧实现「读全量再在内存 subList」的 IO 浪费。
     * 该语句可命中 uk_conv_order(conversation_id, msg_order) 复合索引。
     */
    private List<Message> queryWindow(String conversationId, int limit) {
        return jdbcTemplate.query(
                "SELECT msg_type, content, metadata FROM ("
                        + "  SELECT msg_type, content, metadata, msg_order FROM chat_memory "
                        + "  WHERE conversation_id = ? ORDER BY msg_order DESC LIMIT ?"
                        + ") t ORDER BY msg_order ASC",
                ROW_MAPPER, conversationId, limit);
    }

    private final RowMapper<Message> ROW_MAPPER = (rs, rowNum) ->
            reconstruct(rs.getString("msg_type"), rs.getString("content"), rs.getString("metadata"));

    /**
     * 按 msg_type 用对应构造器重建 Message。
     * 注意：TOOL 类型（ToolResponseMessage）在 1.1.7 没有 (String) 公开构造器，
     *       这里用 UserMessage 兜底保留文本，工具元数据会丢失——本项目对话回放不涉及工具调用，可接受。
     */
    private Message reconstruct(String type, String content, String metaJson) {
        MessageType mt = MessageType.valueOf(type);
        return switch (mt) {
            case USER -> new UserMessage(content);
            case ASSISTANT -> new AssistantMessage(content);
            case SYSTEM -> new SystemMessage(content);
            case TOOL -> new UserMessage("[TOOL] " + (content == null ? "" : content));
        };
    }

    private Map<String, Object> parseMeta(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
