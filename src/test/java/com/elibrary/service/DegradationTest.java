package com.elibrary.service;

import com.elibrary.cache.BorrowScriptService;
import com.elibrary.common.BizException;
import com.elibrary.common.ErrorCode;
import com.elibrary.domain.enums.BorrowStatus;
import com.elibrary.dto.response.BorrowResult;
import com.elibrary.dto.response.ReturnResult;
import com.elibrary.support.EmbeddedRedisTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 降级路径测试(设计文档 §8.3):
 * Redis 完全不可用时,借/还的正确性必须由 DB 独立保证
 * (条件 UPDATE + uk_user_book_active 唯一索引兜底,重试无副作用)。
 * 通过脚本执行抛连接异常模拟 Redis 故障 —— 核心逻辑不依赖健康探测,
 * 异常捕获即降级开关。
 */
class DegradationTest extends EmbeddedRedisTestBase {

    @Autowired
    private BorrowService borrowService;

    @MockitoBean
    private BorrowScriptService scripts;

    @Test
    void Redis不可用时借还仍正确_重复操作被DB兜底拒绝() {
        when(scripts.borrow(anyLong(), anyLong())).thenThrow(RedisConnectionFailureException.class);
        when(scripts.returnBook(anyLong(), anyLong())).thenThrow(RedisConnectionFailureException.class);

        // 借阅:纯 DB 路径
        BorrowResult first = borrowService.borrow(1, 1);
        assertThat(first.getBorrowRecordId()).isNotNull();
        assertThat(bookMapper.selectById(1L).getAvailableCopies()).isEqualTo(2);

        // 重复借阅(重试/重复提交):uk_user_book_active 唯一索引兜底 → 40902,无副作用
        assertThatThrownBy(() -> borrowService.borrow(1, 1))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ALREADY_BORROWED));
        assertThat(bookMapper.selectById(1L).getAvailableCopies()).isEqualTo(2);
        assertThat(recordMapper.selectByUserAndStatus(1, BorrowStatus.BORROWED)).hasSize(1);

        // 归还:纯 DB 路径
        ReturnResult returned = borrowService.returnBook(1, 1);
        assertThat(returned.getReturnedAt()).isNotNull();
        assertThat(bookMapper.selectById(1L).getAvailableCopies()).isEqualTo(3);
        assertThat(recordMapper.selectByUserAndStatus(1, BorrowStatus.BORROWED)).isEmpty();

        // 重复归还(重试)→ 40903,无副作用
        assertThatThrownBy(() -> borrowService.returnBook(1, 1))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_BORROWED));
        assertThat(bookMapper.selectById(1L).getAvailableCopies()).isEqualTo(3);
    }
}
