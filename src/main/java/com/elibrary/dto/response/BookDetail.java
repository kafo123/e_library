package com.elibrary.dto.response;

import com.elibrary.domain.enums.BookType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 书籍详情(含实时可借数量)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BookDetail {

    private Long id;

    private String title;

    private String author;

    private String isbn;

    private BookType type;

    private String category;

    private String description;

    private Integer totalCopies;

    private Integer availableCopies;

    private LocalDateTime createdAt;
}
