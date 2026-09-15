package com.fanzhuo.quickstart.web.service;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 会话级分布式锁（基于 Redis / Redisson）。
 * <p>
 * 目的：保证同一个 conversationId（对话窗口）同一时刻只有一个请求在处理，
 * 即「一次问答未结束时不能接着提问」。
 * <p>
 * 为什么必须加：chat_memory 表有唯一键 uk_conv_order(conversation_id, msg_order)，
 * 而 MysqlChatMemory.add() 是「先 SELECT MAX(msg_order) 再 INSERT」的非原子操作。
 * 并发时两个请求读到相同的 MAX 值，插入相同 msg_order 会触发 DuplicateKeyException，
 * 导致这一轮记忆写入失败。前端的 loading 禁用只能防单页面，防不住多标签页/多端/接口直调。
 * <p>
 * 关键设计：
 * 1. 用 tryLock(waitTime, unit) 而非 lock()：拿不到锁快速失败，避免请求堆积拖垮线程池。
 * 2. 不指定 leaseTime：交给 Redisson 看门狗自动续期。AI 响应可能长达数十秒，
 *    若设固定过期时间，锁提前释放会让并发保护失效。
 * 3. Redis 不可用时降级为「无锁执行」：保证业务可用，仅失去并发保护，不至于整体不可用。
 */
@Service
public class ConversationLockService {

    private static final Logger log = LoggerFactory.getLogger(ConversationLockService.class);

    private static final String LOCK_KEY_PREFIX = "chat:lock:";

    private final RedissonClient redissonClient;

    /** 获取锁的最长等待时间（秒），超时即认为"上一条还在处理中" */
    @Value("${chat.lock.wait-time:2}")
    private long waitTimeSeconds;

    public ConversationLockService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 在会话锁保护下执行动作。
     *
     * @param conversationId 会话标识（锁的粒度）
     * @param action         拿到锁后执行的业务逻辑
     * @param busyFallback   未拿到锁（或其他异常）时的降级返回值
     * @return 执行结果
     */
    public <T> T executeWithLock(String conversationId,
                                 Supplier<T> action,
                                 Supplier<T> busyFallback) {
        if (conversationId == null || conversationId.isBlank()) {
            return action.get();
        }

        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + conversationId);

        // 注意：tryLock 与 action 必须分开 try-catch。
        // 若把 action.get() 放在同一个 try 里，业务异常会被下面的 catch(Exception) 捕获，
        // 进而"降级"再执行一次 action —— 导致模型被调用两次、记忆重复写入。
        boolean locked;
        try {
            // 不传 leaseTime —— 启用 Redisson 看门狗自动续期，适配 AI 长耗时响应
            locked = lock.tryLock(waitTimeSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return busyFallback.get();
        } catch (Exception e) {
            // Redis 故障：降级为无锁执行（唯一的降级入口，保证业务只执行一次）
            log.warn("获取 Redis 分布式锁异常，降级为无锁执行, conversationId={}", conversationId, e);
            return action.get();
        }

        if (!locked) {
            log.info("会话 {} 正在处理中，拒绝并发请求", conversationId);
            return busyFallback.get();
        }

        try {
            return action.get();   // 业务异常正常向上抛出，不会被重复执行
        } finally {
            unlockQuietly(lock, conversationId);
        }
    }

    private void unlockQuietly(RLock lock, String conversationId) {
        try {
            // 只释放自己仍持有的锁，避免锁超时后被误释放而抛 IllegalMonitorStateException
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("释放会话锁失败, conversationId={}", conversationId, e);
        }
    }
}
