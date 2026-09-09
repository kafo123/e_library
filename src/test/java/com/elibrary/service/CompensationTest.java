package com.elibrary.service;

import com.elibrary.cache.BorrowScriptService;
import com.elibrary.cache.StockLoader;
import com.elibrary.common.BizException;
import com.elibrary.common.ErrorCode;
import com.elibrary.domain.BorrowRecord;
import com.elibrary.domain.enums.BorrowStatus;
import com.elibrary.support.EmbeddedRedisTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 补偿测试(设计文档 §8.1 第三步):
 * Redis 预扣成功但 DB 落库被拒绝时,Redis 侧必须回补到原状。
 */
class CompensationTest extends EmbeddedRedisTestBase {

    @Autowired
    private BorrowService borrowService;

    @Autowired
    private StockLoader stockLoader;

    @Test
    void DB落库被唯一索引拒绝后Redis预扣被完整回补() {
        long bookId = 6; // 8 副本
        long userId = 1;

        // 预先在 DB 直插一条活跃借阅(模拟 Redis 集合缺失/重建中的不一致窗口),
        // 使借阅 INSERT 触发 uk_user_book_active 唯一索引冲突
        BorrowRecord existing = new BorrowRecord();
        existing.setUserId(userId);
        existing.setBookId(bookId);
        existing.setStatus(BorrowStatus.BORROWED);
        existing.setActiveFlag(1);
        existing.setBorrowedAt(LocalDateTime.now());
        existing.setDueAt(LocalDateTime.now().plusDays(14));
        existing.setCreatedAt(LocalDateTime.now());
        existing.setUpdatedAt(LocalDateTime.now());
        recordMapper.insert(existing);

        // 初始化 Redis 库存(8),已借集合保持为空 → Lua 预扣会"成功"通过
        assertThat(stockLoader.ensureStock(bookId)).isTrue();
        assertThat(redis.opsForValue().get(BorrowScriptService.stockKey(bookId))).isEqualTo("8");

        // 借阅:Lua 预扣成功(8 -> 7,SADD)→ DB INSERT 冲突 → 触发补偿
        assertThatThrownBy(() -> borrowService.borrow(userId, bookId))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ALREADY_BORROWED));

        // 补偿后 Redis 恢复原状:库存回到 8,已借集合不含该书
        assertThat(redis.opsForValue().get(BorrowScriptService.stockKey(bookId))).isEqualTo("8");
        assertThat(redis.opsForSet().isMember(BorrowScriptService.borrowedKey(userId), String.valueOf(bookId)))
                .isFalse();
    }

    @Test
    void DB条件更新拒绝无库存时Redis预扣被回补() {
        long bookId = 8; // 仅 1 副本
        // DB 侧可用副本直接置 0(模拟 Redis 与 DB 的不一致窗口),Redis 库存为 1
        bookMapper.decrementAvailable(bookId);
        assertThat(stockLoader.ensureStock(bookId)).isTrue();
        assertThat(redis.opsForValue().get(BorrowScriptService.stockKey(bookId))).isEqualTo("1");

        // Lua 预扣成功(1 -> 0)→ DB 条件更新 0 行 → 40901 + 补偿
        assertThatThrownBy(() -> borrowService.borrow(1, bookId))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NO_AVAILABLE_COPIES));

        // 补偿后 Redis 库存回到 1
        assertThat(redis.opsForValue().get(BorrowScriptService.stockKey(bookId))).isEqualTo("1");
        assertThat(redis.opsForSet().isMember(BorrowScriptService.borrowedKey(1), String.valueOf(bookId)))
                .isFalse();
    }
}
