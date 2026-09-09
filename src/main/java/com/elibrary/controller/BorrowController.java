package com.elibrary.controller;

import com.elibrary.common.ApiResponse;
import com.elibrary.domain.enums.BorrowStatus;
import com.elibrary.dto.response.BorrowResult;
import com.elibrary.dto.response.BorrowingItem;
import com.elibrary.dto.response.ReturnResult;
import com.elibrary.service.BorrowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Validated
@RequiredArgsConstructor
@Tag(name = "借阅", description = "借阅 / 归还 / 我的借阅")
public class BorrowController {

    private final BorrowService borrowService;

    @PostMapping("/books/{id}/borrow")
    @Operation(summary = "借阅书籍", description = "需请求头 X-User-Id")
    public ApiResponse<BorrowResult> borrow(@PathVariable @Min(value = 1, message = "id 最小为 1") Long id,
                                            @RequestHeader("X-User-Id") long userId) {
        return ApiResponse.ok(borrowService.borrow(userId, id));
    }

    @PostMapping("/books/{id}/return")
    @Operation(summary = "归还书籍", description = "需请求头 X-User-Id")
    public ApiResponse<ReturnResult> returnBook(@PathVariable @Min(value = 1, message = "id 最小为 1") Long id,
                                                @RequestHeader("X-User-Id") long userId) {
        return ApiResponse.ok(borrowService.returnBook(userId, id));
    }

    @GetMapping("/borrowings")
    @Operation(summary = "我的借阅", description = "默认返回当前借阅中(status=BORROWED)")
    public ApiResponse<List<BorrowingItem>> myBorrowings(
            @RequestHeader("X-User-Id") long userId,
            @RequestParam(defaultValue = "BORROWED") BorrowStatus status) {
        return ApiResponse.ok(borrowService.myBorrowings(userId, status));
    }
}
