package com.fanzhuo.quickstart.web.memory;

import org.redisson.api.RAtomicLong;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 会话记忆的 Redis 缓存层（Cache-Aside 模式）。
 * <p>
 * 定位：MySQL 是权威源（全量历史，审计用），本类只做「热会话窗口」的加速缓存。
 * 缓存失效/异常时一律返回 null 或静默降级，由 {@link MysqlChatMemory} 回退到 MySQL 查询，
 * 因此本类【不会】影响对话可用性。
 * <p>
 * 为什么用 Redisson 的数据结构而不是 StringRedisTemplate：
 * 项目已有 RedissonClient（用于分布式锁），Redisson 的 RList 自带 trim / expire，
 * 语义更贴合，且无需关心 Spring 是否自动配置了 RedisTemplate。
 * <p>
 * 只用基础数据结构（RList / RAtomicLong），普通 Redis 即可支持，
 * 不需要 RedisJSON / Redis Query Engine 模块。
 * <p>
 * 键设计：
 * <pre>
 *   chat:mem:{convId}   RList    最近 windowSize 条消息（LTRIM 自动保持窗口）
 *   chat:seq:{convId}   AtomicLong  消息序号计数器（INCR 生成 msg_order）
 * </pre>
 */
public class ChatMemoryCache {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryCache.class);

    private static final String KEY_MEM = "chat:mem:";
    private static final String KEY_SEQ = "chat:seq:";

    /** 序列化分隔符：type \u0001 content（\u0001 为不可见控制字符，正文几乎不可能出现） */
    private static final char SEP = '\u0001';

    private final RedissonClient redisson;
    private final int windowSize;
    private final long ttlSeconds;

    public ChatMemoryCache(RedissonClient redisson, int windowSize, long ttlSeconds) {
        this.redisson = redisson;
        this.windowSize = windowSize;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * 读取缓存的消息窗口。
     *
     * @return 命中返回消息列表；未命中或异常返回 {@code null}（注意：不是空列表）
     */
    public List<Message> getMessages(String conversationId) {
        try {
            RList<String> list = redisson.getList(KEY_MEM + conversationId);
            if (!list.isExists() || list.isEmpty()) {
                return null;
            }
            List<String> raw = list.readAll();
            List<Message> result = new ArrayList<>(raw.size());
            for (String item : raw) {
                Message m = deserialize(item);
                if (m != null) {
                    result.add(m);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("读取记忆缓存失败，降级走 MySQL, conversationId={}", conversationId, e);
            return null;
        }
    }

    /**
     * 写入消息窗口。
     *
     * @param append true=追加（正常写入）；false=覆盖（缓存 miss 后从 MySQL 回填）
     */
    public void putMessages(String conversationId, List<Message> messages, boolean append) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        try {
            RList<String> list = redisson.getList(KEY_MEM + conversationId);
            List<String> raw = new ArrayList<>(messages.size());
            for (Message m : messages) {
                String item = serialize(m);
                if (item != null) {
                    raw.add(item);
                }
            }
            if (!append) {
                list.delete();   // 回填前先清空，避免与旧数据叠加
            }
            list.addAll(raw);
            // 关键：只保留最近 windowSize 条，Redis 端即完成滑动窗口裁剪
            list.trim(-windowSize, -1);
            list.expire(ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入记忆缓存失败（不影响主流程）, conversationId={}", conversationId, e);
        }
    }

    /**
     * 生成消息序号（原子递增），返回本批消息的起始序号。
     * <p>
     * 关键坑：序号计数器带 TTL，长时间不活跃会过期。若过期后直接从 0 开始递增，
     * 会与 MySQL 中已有的 msg_order 冲突（uk_conv_order 唯一键）导致插入失败。
     * 因此当计数器不存在时，先用 dbNextSupplier 提供的「DB 当前最大序号 + 1」做惰性初始化，
     * compareAndSet 保证多实例并发下只有一个初始化生效（后续 addAndGet 自然接续）。
     *
     * @param dbNextSupplier 回退供应商：返回数据库中下一个可用序号
     * @return 起始序号；失败返回 {@code null}，由调用方回退为 SELECT MAX(msg_order)
     */
    public Long nextOrder(String conversationId, int size, Supplier<Long> dbNextSupplier) {
        try {
            RAtomicLong seq = redisson.getAtomicLong(KEY_SEQ + conversationId);
            if (!seq.isExists()) {
                seq.compareAndSet(0L, dbNextSupplier.get());
            }
            long end = seq.addAndGet(size);
            seq.expire(ttlSeconds, TimeUnit.SECONDS);
            return end - size + 1;
        } catch (Exception e) {
            log.warn("生成消息序号失败，降级为 SELECT MAX, conversationId={}", conversationId, e);
            return null;
        }
    }

    /** 清空某会话的缓存（clear 时调用）。 */
    public void evict(String conversationId) {
        try {
            redisson.getKeys().delete(KEY_MEM + conversationId, KEY_SEQ + conversationId);
        } catch (Exception e) {
            log.warn("清理会话缓存失败, conversationId={}", conversationId, e);
        }
    }

    // ===== 序列化（用分隔符拼接，比 JSON 更快，避免编解码开销） =====

    private String serialize(Message message) {
        try {
            String content = message.getText();
            return message.getMessageType().name() + SEP + (content == null ? "" : content);
        } catch (Exception e) {
            log.warn("序列化消息失败，跳过该条", e);
            return null;
        }
    }

    private Message deserialize(String raw) {
        try {
            int idx = raw.indexOf(SEP);
            if (idx < 0) {
                return null;
            }
            MessageType type = MessageType.valueOf(raw.substring(0, idx));
            String content = raw.substring(idx + 1);
            return switch (type) {
                case USER -> new UserMessage(content);
                case ASSISTANT -> new AssistantMessage(content);
                case SYSTEM -> new SystemMessage(content);
                // TOOL 类型在 1.1.7 无 (String) 公开构造器，与 MysqlChatMemory 的处理保持一致
                case TOOL -> new UserMessage("[TOOL] " + content);
            };
        } catch (Exception e) {
            log.warn("反序列化消息失败，跳过该条", e);
            return null;
        }
    }
}
