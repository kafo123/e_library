package com.elibrary.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 我的借阅列表项(overdue 为读时派生的逾期状态)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BorrowingItem {

    private Long borrowRecordId;

    private Long bookId;

    private String title;

    private LocalDateTime borrowedAt;

    private LocalDateTime dueAt;

    private boolean overdue;
}
