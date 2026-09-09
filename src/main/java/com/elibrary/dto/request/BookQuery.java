package com.elibrary.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 浏览书籍查询参数
 */
@Data
public class BookQuery {

    @Min(value = 1, message = "page 最小为 1")
    private long page = 1;

    @Min(value = 1, message = "size 最小为 1")
    @Max(value = 100, message = "size 最大为 100")
    private long size = 20;

    /** 关键字:匹配书名或作者 */
    private String keyword;

    /** EBOOK / JOURNAL(可选) */
    private String type;

    private String category;

    /** 排序字段:createdAt / title / author(白名单校验) */
    private String sortBy;

    /** asc / desc */
    private String order;
}
