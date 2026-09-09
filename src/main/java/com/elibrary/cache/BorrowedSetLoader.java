package com.elibrary.cache;

import com.elibrary.repository.BorrowRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 用户已借集合懒重建自愈(设计文档 §9.3):
 * 集合 key 缺失(Redis 重启/数据丢失)时回源 DB 重建;
 * loading 锁保证并发下仅重建一次。
 * 注意:集合为空时 key 依然不存在(Redis 语义),此时每次都会触发重建查询,
 * 查询走 idx_record_user_status 索引,成本可忽略。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BorrowedSetLoader {

    private final StringRedisTemplate redis;
    private final BorrowRecordMapper recordMapper;

    public void ensure(long userId) {
        String key = BorrowScriptService.borrowedKey(userId);
        if (redis.type(key) == DataType.SET) {
            return;
        }
        String loadingKey = key + ":loading";
        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(loadingKey, "1", Duration.ofSeconds(5)))) {
            try {
                List<Long> bookIds = recordMapper.selectActiveBookIdsByUser(userId);
                if (!bookIds.isEmpty()) {
                    redis.opsForSet().add(key, bookIds.stream().map(String::valueOf).toArray(String[]::new));
                }
            } finally {
                redis.delete(loadingKey);
            }
        }
    }
}
