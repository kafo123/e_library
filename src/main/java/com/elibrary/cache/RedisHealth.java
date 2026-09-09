package com.elibrary.cache;

import com.elibrary.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 健康探测。
 * 故障后进入一段"不健康窗口"(redis-unhealthy-seconds),期间直接走降级路径,
 * 避免每个请求都打满连接超时;窗口结束重新探测,Redis 恢复后自动回归主路径。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RedisHealth {

    private final StringRedisTemplate redis;
    private final AppProperties props;
    private final AtomicLong unhealthyUntil = new AtomicLong(0);

    public boolean isHealthy() {
        long now = System.currentTimeMillis();
        if (now < unhealthyUntil.get()) {
            return false;
        }
        try {
            redis.execute((RedisCallback<Object>) connection -> {
                connection.ping();
                return null;
            });
            return true;
        } catch (Exception e) {
            unhealthyUntil.set(now + props.getRedisUnhealthySeconds() * 1000L);
            log.warn("Redis 不可用,{} 秒内走降级路径(纯 DB,正确性不变)", props.getRedisUnhealthySeconds());
            return false;
        }
    }
}
