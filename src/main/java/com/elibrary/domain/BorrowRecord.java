package com.elibrary.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.elibrary.domain.enums.BorrowStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 借阅记录(领域实体)
 */
@Data
@TableName("borrow_record")
public class BorrowRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long bookId;

    private LocalDateTime borrowedAt;

    private LocalDateTime dueAt;

    private LocalDateTime returnedAt;

    private BorrowStatus status;

    /**
     * 借阅中=1,已归还=NULL。
     * 配合唯一索引 uk_user_book_active 实现"同一用户对同一书至多一条活跃记录"
     * (MySQL 唯一索引允许多个 NULL,历史归还记录不受约束)。
     */
    private Integer activeFlag;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * 领域行为:是否已逾期(仅借阅中且超过到期时间)
     */
    public boolean isOverdue(LocalDateTime now) {
        return status == BorrowStatus.BORROWED && dueAt != null && now.isAfter(dueAt);
    }
}
