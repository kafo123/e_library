package com.elibrary.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页响应体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {

    private long page;

    private long size;

    private long total;

    private long pages;

    private List<T> items;

    public static <T> PageResponse<T> of(IPage<?> page, List<T> items) {
        return new PageResponse<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getPages(), items);
    }
}
