package com.elibrary.dto.response;

import com.elibrary.domain.enums.BookType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 浏览列表项(不含 description 大字段;不含可借数,避免列表缓存频繁失效)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BookSummary {

    private Long id;

    private String title;

    private String author;

    private BookType type;

    private String category;

    private LocalDateTime createdAt;
}
