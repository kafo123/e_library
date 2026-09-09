package com.elibrary.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.elibrary.domain.enums.BookType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 书籍/期刊(领域实体)
 */
@Data
@TableName("book")
public class Book {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String author;

    private String isbn;

    private BookType type;

    private String category;

    private String description;

    /** 馆藏总副本 */
    private Integer totalCopies;

    /** 当前可借副本(冗余计数,由 Redis 预扣 + 条件 UPDATE + 补偿共同维护) */
    private Integer availableCopies;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
