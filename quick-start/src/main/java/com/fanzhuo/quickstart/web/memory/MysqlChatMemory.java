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
 * 路线 B：直接实现 ChatMemory 接口，把完整对话历史持久化到 MySQL。
 *
 * 注意：本类【不】加 @Service/@Component，由 ChatClientConfig.chatMemory() 这个
 * @Bean 工厂方法创建，从而让 windowSize 的 @Value 正确注入。若被组件扫描直接
 * 实例化，构造器上的裸 int 参数无法注入，会报 "required a bean of type 'int'"。
 *
 * 与 MessageWindowChatMemory 的区别：
 * - add() 采用「追加写」而非「先删后插」，完整历史全部保留在 chat_memory 表（审计轨迹）。
 * - get() 只返回最近 windowSize 条，作为喂给大模型的提示词上下文窗口，避免上下文无限膨胀。
 * - getAll() 提供绕过窗口、取出某会话全量历史的能力（审计 / 复盘用）。
 *
 * 存储采用「type + content + metadata 结构化字段」而非整对象 JSON：
 * 直接 readValue(json, UserMessage.class) 会失败。按 type 用已知构造器重建最稳。
 */
public class MysqlChatMemory implements ChatMemory {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final int windowSize;

    public MysqlChatMemory(JdbcTemplate jdbcTemplate,
                           ObjectMapper objectMapper,
                           int windowSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.windowSize = windowSize;
    }

    @Override
    @Transactional
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        // 取当前会话最大序号，新消息在其后追加，保证顺序且全量保留
        Integer maxOrder = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(msg_order), -1) FROM chat_memory WHERE conversation_id = ?",
                Integer.class, conversationId);
        int start = (maxOrder == null ? -1 : maxOrder) + 1;

        // 首条消息（该会话第一次写入）时，用第一条用户提问作为会话标题
        String title = null;
        if (start == 0) { // 说明此前没有任何消息
            title = messages.stream()
                    .filter(m -> m.getMessageType() == MessageType.USER)
                    .map(Message::getText)
                    .filter(t -> t != null && !t.isBlank())
                    .findFirst()
                    .map(t -> t.length() > 64 ? t.substring(0, 64) : t)
                    .orElse(null);
        }

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
    }

    @Override
    public List<Message> get(String conversationId) {
        List<Message> all = jdbcTemplate.query(
                "SELECT msg_type, content, metadata FROM chat_memory "
                        + "WHERE conversation_id = ? ORDER BY msg_order ASC",
                ROW_MAPPER, conversationId);
        // 返回最近 windowSize 条作为上下文窗口；全量仍在库中
        if (all.size() <= windowSize) {
            return all;
        }
        return new ArrayList<>(all.subList(all.size() - windowSize, all.size()));
    }

    @Override
    public void clear(String conversationId) {
        jdbcTemplate.update("DELETE FROM chat_memory WHERE conversation_id = ?", conversationId);
    }

    /** 审计用途：取出该会话的完整历史（不走窗口裁剪）。 */
    public List<Message> getAll(String conversationId) {
        return jdbcTemplate.query(
                "SELECT msg_type, content, metadata FROM chat_memory "
                        + "WHERE conversation_id = ? ORDER BY msg_order ASC",
                ROW_MAPPER, conversationId);
    }

    /**
     * 列出所有会话（去重 conversation_id），含消息条数与最近活跃时间。
     * 供前端「历史会话列表」使用。
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
