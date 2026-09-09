package com.elibrary.controller;

import com.elibrary.common.ApiResponse;
import com.elibrary.common.PageResponse;
import com.elibrary.dto.request.BookQuery;
import com.elibrary.dto.response.BookDetail;
import com.elibrary.dto.response.BookSummary;
import com.elibrary.service.BookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/books")
@Validated
@RequiredArgsConstructor
@Tag(name = "书籍", description = "浏览与详情")
public class BookController {

    private final BookService bookService;

    @GetMapping
    @Operation(summary = "浏览书籍", description = "分页浏览,支持关键字(书名/作者)、类型、分类过滤与排序")
    public ApiResponse<PageResponse<BookSummary>> browse(@Valid BookQuery query) {
        return ApiResponse.ok(bookService.browse(query));
    }

    @GetMapping("/{id}")
    @Operation(summary = "书籍详情", description = "含实时可借数量")
    public ApiResponse<BookDetail> detail(@PathVariable @Min(value = 1, message = "id 最小为 1") Long id) {
        return ApiResponse.ok(bookService.detail(id));
    }
}
