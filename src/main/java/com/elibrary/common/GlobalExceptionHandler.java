package com.elibrary.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static com.elibrary.common.ErrorCode.INTERNAL_ERROR;
import static com.elibrary.common.ErrorCode.MISSING_USER_HEADER;
import static com.elibrary.common.ErrorCode.PARAM_INVALID;

/**
 * 全局异常处理:
 * - 业务异常(BizException)是预期分支,打 WARN
 * - 未知异常打 ERROR + traceId,绝不回传堆栈(防信息泄漏)
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        return ResponseEntity.status(e.getErrorCode().getHttpStatus())
                .body(ApiResponse.error(e.getErrorCode(), e.getMessage()));
    }

    /** @RequestBody + @Valid 校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("参数校验失败");
        return ResponseEntity.badRequest().body(ApiResponse.error(PARAM_INVALID, message));
    }

    /** 方法级参数校验失败(Spring 6.1 起 @Valid 作用于查询参数) */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodValidation(HandlerMethodValidationException e) {
        String message = e.getAllValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream())
                .map(err -> err.getDefaultMessage() == null ? "参数校验失败" : err.getDefaultMessage())
                .findFirst()
                .orElse("参数校验失败");
        return ResponseEntity.badRequest().body(ApiResponse.error(PARAM_INVALID, message));
    }

    /** 路径/查询参数类型转换失败(如 status=XXX) */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(PARAM_INVALID, "参数类型非法: " + e.getName()));
    }

    /** 缺少必传请求头:X-User-Id */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.status(MISSING_USER_HEADER.getHttpStatus()).body(ApiResponse.error(MISSING_USER_HEADER));
    }

    /** 兜底:未知异常不泄漏堆栈 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        log.error("系统异常: traceId={}", org.slf4j.MDC.get(TraceIdFilter.TRACE_ID), e);
        return ResponseEntity.status(INTERNAL_ERROR.getHttpStatus()).body(ApiResponse.error(INTERNAL_ERROR));
    }
}
