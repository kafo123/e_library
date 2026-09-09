package com.elibrary.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.slf4j.MDC;

/**
 * 统一响应体:{ code, message, data, traceId }
 */
@Data
@AllArgsConstructor
public class ApiResponse<T> {

    public static final int SUCCESS_CODE = 0;

    private int code;
    private String message;
    private T data;
    private String traceId;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS_CODE, "success", data, MDC.get("traceId"));
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), null, MDC.get("traceId"));
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null, MDC.get("traceId"));
    }
}
