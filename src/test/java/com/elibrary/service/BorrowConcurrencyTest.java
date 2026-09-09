package com.elibrary.service;

import com.elibrary.cache.BorrowScriptService;
import com.elibrary.common.BizException;
import com.elibrary.common.ErrorCode;
import com.elibrary.dto.response.BorrowResult;
import com.elibrary.support.EmbeddedRedisTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发正确性测试(真实 Redis 链路):
 * 守护不变量 I1(库存不超发)与 I2(同用户不重复借阅)。
 */
class BorrowConcurrencyTest extends EmbeddedRedisTestBase {

    @Autowired
    private BorrowService borrowService;

    @Test
    void 二十个用户并发抢仅有一副本的书_恰好一人成功() throws Exception {
        int threads = 20;
        long bookId = 8; // 种子数据:仅 1 副本
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BorrowResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            long userId = 100 + i; // 不同用户抢同一本书
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return borrowService.borrow(userId, bookId);
            }));
        }
        ready.await();
        start.countDown();

        int success = 0;
        int noStock = 0;
        for (Future<BorrowResult> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
                success++;
            } catch (Exception e) {
                Throwable cause = e.getCause();
                if (cause instanceof BizException biz && biz.getErrorCode() == ErrorCode.NO_AVAILABLE_COPIES) {
                    noStock++;
                } else {
                    throw new AssertionError("出现非预期异常", cause);
                }
            }
        }
        pool.shutdown();

        assertThat(success).isEqualTo(1);
        assertThat(noStock).isEqualTo(threads - 1);
        // DB 与 Redis 库存一致,且不为负数
        assertThat(bookMapper.selectById(bookId).getAvailableCopies()).isZero();
        assertThat(redis.opsForValue().get(BorrowScriptService.stockKey(bookId))).isEqualTo("0");
    }

    @Test
    void 同一用户并发重复借阅同一本书_恰好一人成功() throws Exception {
        int threads = 8;
        long userId = 1;
        long bookId = 2; // 2 副本,但同一用户只能借一次
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BorrowResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return borrowService.borrow(userId, bookId); // 重复提交
            }));
        }
        ready.await();
        start.countDown();

        int success = 0;
        int already = 0;
        for (Future<BorrowResult> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
                success++;
            } catch (Exception e) {
                Throwable cause = e.getCause();
                if (cause instanceof BizException biz && biz.getErrorCode() == ErrorCode.ALREADY_BORROWED) {
                    already++;
                } else {
                    throw new AssertionError("出现非预期异常", cause);
                }
            }
        }
        pool.shutdown();

        assertThat(success).isEqualTo(1);
        assertThat(already).isEqualTo(threads - 1);
        // 库存只扣一次:2 -> 1
        assertThat(bookMapper.selectById(bookId).getAvailableCopies()).isEqualTo(1);
        assertThat(redis.opsForValue().get(BorrowScriptService.stockKey(bookId))).isEqualTo("1");
    }
}
