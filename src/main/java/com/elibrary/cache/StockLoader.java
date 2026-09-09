package com.elibrary.cache;

import com.elibrary.domain.Book;
import com.elibrary.repository.BookMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 库存 key 懒加载自愈(设计文档 §9.3):
 * stock key 缺失(Redis 重启/数据丢失)时回源 DB 重建;
 * loading 锁(SETNX)保证并发下仅加载一次。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StockLoader {

    private final StringRedisTemplate redis;
    private final BookMapper bookMapper;

    /**
     * @return true = stock key 已可用(存在或已重建)
     */
    public boolean ensureStock(long bookId) {
        String stockKey = BorrowScriptService.stockKey(bookId);
        if (Boolean.TRUE.equals(redis.hasKey(stockKey))) {
            return true;
        }
        String loadingKey = stockKey + ":loading";
        Boolean acquired = redis.opsForValue().setIfAbsent(loadingKey, "1", Duration.ofSeconds(5));
        if (Boolean.TRUE.equals(acquired)) {
            try {
                Book book = bookMapper.selectById(bookId);
                if (book != null) {
                    redis.opsForValue().set(stockKey, String.valueOf(book.getAvailableCopies()));
                    return true;
                }
                return false;
            } finally {
                redis.delete(loadingKey);
            }
        }
        // 未抢到 loading 锁:等其他加载者完成后重查(最多 1s)
        for (int i = 0; i < 5; i++) {
            sleep(200);
            if (Boolean.TRUE.equals(redis.hasKey(stockKey))) {
                return true;
            }
        }
        log.warn("库存懒加载等待超时 bookId={}", bookId);
        return false;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
