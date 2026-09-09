package com.elibrary.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 归还结果(幂等重放时返回与首次完全一致)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReturnResult {

    private Long borrowRecordId;

    private Long bookId;

    private String title;

    private LocalDateTime returnedAt;
}
