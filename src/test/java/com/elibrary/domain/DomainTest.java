package com.elibrary.domain;

import com.elibrary.domain.enums.BorrowStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 领域行为测试:isOverdue 边界
 */
class DomainTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 9, 12, 0, 0);

    private static BorrowRecord record(BorrowStatus status, LocalDateTime dueAt) {
        BorrowRecord record = new BorrowRecord();
        record.setStatus(status);
        record.setDueAt(dueAt);
        return record;
    }

    @Test
    void 未到期不逾期() {
        BorrowRecord record = record(BorrowStatus.BORROWED, NOW.plusDays(1));
        assertThat(record.isOverdue(NOW)).isFalse();
    }

    @Test
    void 恰到期不逾期() {
        BorrowRecord record = record(BorrowStatus.BORROWED, NOW);
        assertThat(record.isOverdue(NOW)).isFalse();
    }

    @Test
    void 已过到期时间逾期() {
        BorrowRecord record = record(BorrowStatus.BORROWED, NOW.minusSeconds(1));
        assertThat(record.isOverdue(NOW)).isTrue();
    }

    @Test
    void 已归还不算逾期() {
        BorrowRecord record = record(BorrowStatus.RETURNED, NOW.minusDays(10));
        assertThat(record.isOverdue(NOW)).isFalse();
    }
}
