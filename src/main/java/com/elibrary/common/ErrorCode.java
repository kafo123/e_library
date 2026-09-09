package com.elibrary.common;

import lombok.Getter;

/**
 * 业务错误码:HTTP 状态码与业务码同时有意义,
 * HTTP 码供网关/基础设施判断,业务码供客户端业务分支。
 */
@Getter
public enum ErrorCode {

    PARAM_INVALID(40001, 400, "参数校验失败"),
    MISSING_USER_HEADER(40002, 400, "缺少 X-User-Id 请求头"),
    BOOK_NOT_FOUND(40401, 404, "书籍不存在"),
    NO_AVAILABLE_COPIES(40901, 409, "暂无可用副本"),
    ALREADY_BORROWED(40902, 409, "您已借阅此书且未归还"),
    NOT_BORROWED(40903, 409, "不存在可归还的借阅记录"),
    INTERNAL_ERROR(50000, 500, "系统内部错误");

    private final int code;
    private final int httpStatus;
    private final String message;

    ErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
