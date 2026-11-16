package com.noveltts.web.controller;

import com.noveltts.web.dto.ApiModels;
import com.noveltts.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestControllerAdvice(basePackages = "com.noveltts.web.controller")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiModels.ApiError> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(new ApiModels.ApiError("參數錯誤", e.getMessage(), traceId()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiModels.ApiError> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiModels.ApiError("處理失敗", e.getMessage(), traceId()));
    }

    /**
     * 找不到資源 → 404。
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiModels.ApiError> handleNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiModels.ApiError("找不到資源", e.getMessage(), traceId()));
    }

    /**
     * ResponseStatusException 帶著明確的 HTTP 狀態（例如找不到資源 → 404）。
     * 必須在 handleOther 之前處理，否則會被 @ExceptionHandler(Exception.class)
     * 抓走並統一轉成 500，讓「找不到」看起來像伺服器故障。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiModels.ApiError> handleResponseStatus(ResponseStatusException e) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        return ResponseEntity.status(status)
                .body(new ApiModels.ApiError(status == HttpStatus.NOT_FOUND ? "找不到資源" : "請求失敗",
                        e.getReason() == null ? e.getMessage() : e.getReason(), traceId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiModels.ApiError> handleOther(Exception e) {
        log.error("未預期錯誤", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiModels.ApiError("伺服器錯誤", e.getMessage(), traceId()));
    }

    private String traceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
