package com.elibrary.cache;

import com.elibrary.common.PageResponse;
import com.elibrary.config.AppProperties;
import com.elibrary.dto.response.BookDetail;
import com.elibrary.dto.response.BookSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;

/**
 * 书籍读缓存(Cache-Aside,设计文档 §9):
 * - 详情:TTL + 随机抖动(防雪崩),空值标记(防穿透),借/还成功后主动删除
 * - 浏览分页:仅 TTL(列表不含可借数,无需在借/还后失效)
 * - 任何缓存异常降级直查 DB,缓存故障不影响主流程
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BookCacheService {

    private static final String NULL_MARKER = "NULL";
    private static final String DETAIL_KEY_PREFIX = "lib:book:";
    private static final String BROWSE_KEY_PREFIX = "lib:books:page:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RedisHealth redisHealth;
    private final AppProperties props;
    private final Random random = new Random();

    /**
     * 书籍详情缓存:命中直接返回;未命中回源 DB 并写缓存(含空值标记)。
     */
    public Optional<BookDetail> getDetail(long bookId, Supplier<Optional<BookDetail>> dbLoader) {
        if (!redisHealth.isHealthy()) {
            return dbLoader.get();
        }
        String key = DETAIL_KEY_PREFIX + bookId;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return NULL_MARKER.equals(cached)
                        ? Optional.empty()
                        : Optional.of(objectMapper.readValue(cached, BookDetail.class));
            }
            Optional<BookDetail> detail = dbLoader.get();
            long ttl = detail.isPresent()
                    ? props.getCache().getBookDetailTtlSeconds()
                            + random.nextInt((int) props.getCache().getBookDetailJitterSeconds() + 1)
                    : props.getCache().getNullValueTtlSeconds();
            redis.opsForValue().set(key, detail.map(this::write).orElse(NULL_MARKER), Duration.ofSeconds(ttl));
            return detail;
        } catch (Exception e) {
            log.warn("详情缓存异常,降级直查 DB: bookId={}", bookId, e);
            return dbLoader.get();
        }
    }

    /**
     * 借/还成功后删除详情缓存(availableCopies 已变化)
     */
    public void deleteDetail(long bookId) {
        try {
            redis.delete(DETAIL_KEY_PREFIX + bookId);
        } catch (Exception e) {
            log.warn("删除详情缓存失败,由 TTL 兜底: bookId={}", bookId, e);
        }
    }

    /**
     * 浏览分页缓存:命中直接返回;未命中回源 DB 并写缓存。
     */
    public PageResponse<BookSummary> getBrowsePage(String cacheKey, Supplier<PageResponse<BookSummary>> dbLoader) {
        if (!redisHealth.isHealthy()) {
            return dbLoader.get();
        }
        try {
            String cached = redis.opsForValue().get(BROWSE_KEY_PREFIX + cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<PageResponse<BookSummary>>() {});
            }
            PageResponse<BookSummary> page = dbLoader.get();
            redis.opsForValue().set(BROWSE_KEY_PREFIX + cacheKey, write(page),
                    Duration.ofSeconds(props.getCache().getBrowseTtlSeconds()));
            return page;
        } catch (Exception e) {
            log.warn("分页缓存异常,降级直查 DB", e);
            return dbLoader.get();
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("缓存序列化失败", e);
        }
    }
}
