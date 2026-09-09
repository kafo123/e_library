package com.elibrary.service;

import com.elibrary.cache.BorrowScriptService;
import com.elibrary.common.BizException;
import com.elibrary.common.ErrorCode;
import com.elibrary.domain.Book;
import com.elibrary.domain.BorrowRecord;
import com.elibrary.domain.enums.BorrowStatus;
import com.elibrary.dto.response.BorrowResult;
import com.elibrary.dto.response.BorrowingItem;
import com.elibrary.dto.response.ReturnResult;
import com.elibrary.support.EmbeddedRedisTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 借/还主链路集成测试(MySQL + 真实 Redis):
 * 成功路径、全部业务分支、库存/已借集合与 DB 的一致性、懒加载自愈。
 */
class BorrowFlowIntegrationTest extends EmbeddedRedisTestBase {

    @Autowired
    private BorrowService borrowService;

    @Test
    void 借阅成功后库存与已借集合同步扣减() {
        BorrowResult result = borrowService.borrow(1, 1);

        assertThat(result.getBorrowRecordId()).isNotNull();
        assertThat(result.getBookId()).isEqualTo(1L);
        assertThat(result.getTitle()).isEqualTo("Effective Java");
        assertThat(result.getDueAt()).isCloseTo(result.getBorrowedAt().plusDays(14), within(1, ChronoUnit.SECONDS));

        // DB:可借副本 3 -> 2
        Book book = bookMapper.selectById(1L);
        assertThat(book.getAvailableCopies()).isEqualTo(2);
        // Redis:库存计数与已借集合同步
        assertThat(redis.opsForValue().get(BorrowScriptService.stockKey(1))).isEqualTo("2");
        assertThat(redis.opsForSet().isMember(BorrowScriptService.borrowedKey(1), "1")).isTrue();
    }

    @Test
    void 无可用副本时拒绝() {
        // 书 8 仅 1 副本:用户 1 借走后用户 2 再借
        borrowService.borrow(1, 8);
        assertThatThrownBy(() -> borrowService.borrow(2, 8))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NO_AVAILABLE_COPIES));
        // 不变量未被破坏
        assertThat(bookMapper.selectById(8L).getAvailableCopies()).isEqualTo(0);
        assertThat(redis.opsForValue().get(BorrowScriptService.stockKey(8))).isEqualTo("0");
    }

    @Test
    void 重复借阅被拒绝且无副作用_客户端重试场景() {
        BorrowResult first = borrowService.borrow(1, 2);
        // 重试/重复提交 → 40902,库存不变
        assertThatThrownBy(() -> borrowService.borrow(1, 2))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ALREADY_BORROWED));
        assertThat(bookMapper.selectById(2L).getAvailableCopies()).isEqualTo(1);
        assertThat(recordMapper.selectByUserAndStatus(1, BorrowStatus.BORROWED)).hasSize(1);
        assertThat(borrowService.myBorrowings(1, BorrowStatus.BORROWED).get(0).getBorrowRecordId())
                .isEqualTo(first.getBorrowRecordId());
    }

    @Test
    void 借阅不存在的书返回404() {
        assertThatThrownBy(() -> borrowService.borrow(1, 999))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BOOK_NOT_FOUND));
    }

    @Test
    void 归还后库存恢复且无法再次归还() {
        borrowService.borrow(1, 3);
        ReturnResult result = borrowService.returnBook(1, 3);

        assertThat(result.getReturnedAt()).isNotNull();
        // 库存恢复 2 -> 2
        assertThat(bookMapper.selectById(3L).getAvailableCopies()).isEqualTo(2);
        assertThat(redis.opsForValue().get(BorrowScriptService.stockKey(3))).isEqualTo("2");
        // 已借集合移除
        assertThat(redis.opsForSet().isMember(BorrowScriptService.borrowedKey(1), "3")).isFalse();
        // 再次归还(重试)→ 40903,无副作用
        assertThatThrownBy(() -> borrowService.returnBook(1, 3))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_BORROWED));
        assertThat(bookMapper.selectById(3L).getAvailableCopies()).isEqualTo(2);
    }

    @Test
    void 归还未借过的书被拒绝() {
        assertThatThrownBy(() -> borrowService.returnBook(1, 5))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_BORROWED));
    }

    @Test
    void 归还后可再次借阅同一本书() {
        borrowService.borrow(1, 4);
        borrowService.returnBook(1, 4);
        BorrowResult again = borrowService.borrow(1, 4);

        assertThat(again.borrowRecordId()).isNotNull();
        List<BorrowRecord> records = recordMapper.selectByUserAndStatus(1, BorrowStatus.BORROWED);
        assertThat(records).hasSize(1);
    }

    @Test
    void 我的借阅列表含逾期标记() {
        borrowService.borrow(1, 1);
        borrowService.borrow(1, 4);

        List<BorrowingItem> items = borrowService.myBorrowings(1, BorrowStatus.BORROWED);
        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(item -> assertThat(item.isOverdue()).isFalse());

        // 归还后当前借阅只剩 1 条,历史记录 2 条
        borrowService.returnBook(1, 4);
        assertThat(borrowService.myBorrowings(1, BorrowStatus.BORROWED)).hasSize(1);
        assertThat(borrowService.myBorrowings(1, BorrowStatus.RETURNED)).hasSize(1);
    }

    @Test
    void Redis数据丢失后借阅自动懒加载自愈() {
        // 用户 1 先借走书 3 的一个副本
        borrowService.borrow(1, 3);
        assertThat(redis.opsForValue().get(BorrowScriptService.stockKey(3))).isEqualTo("1");

        // 模拟 Redis 重启:全部数据丢失
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        // 用户 2 再借:stock key 缺失 → NOT_INIT → 回源 DB 重建(available_copies=1)→ 重试成功
        BorrowResult result = borrowService.borrow(2, 3);
        assertThat(result.getBorrowRecordId()).isNotNull();
        assertThat(redis.opsForValue().get(BorrowScriptService.stockKey(3))).isEqualTo("0");
        assertThat(bookMapper.selectById(3L).getAvailableCopies()).isEqualTo(0);
    }

    @Test
    void Redis数据丢失后归还自动懒加载自愈() {
        borrowService.borrow(1, 2);

        // 模拟 Redis 重启
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        // 归还:集合缺失 → 重建自愈 → 成功
        ReturnResult result = borrowService.returnBook(1, 2);
        assertThat(result.getReturnedAt()).isNotNull();
        assertThat(bookMapper.selectById(2L).getAvailableCopies()).isEqualTo(2);
    }
}
