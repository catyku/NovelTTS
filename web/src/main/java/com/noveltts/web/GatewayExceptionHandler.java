package com.noveltts.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * 閘道端點的例外處理。
 *
 * <p>沒有這一層的話，上游 OpenRouter 的錯誤會變成 500 —— 但它其實是
 * 呼叫端的問題（Key 無效、額度用盡），該回 4xx 才能讓前端正確顯示
 * 「你的 Key 有問題」而不是「伺服器壞了」。
 *
 * <p>回應內容不包含 API Key，也不包含上游的完整錯誤堆疊。
 */
@RestControllerAdvice(assignableTypes = {
        com.noveltts.web.controller.GatewayApiController.class,
        com.noveltts.web.controller.GatewayMergeController.class
})
public class GatewayExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    /**
     * OpenRouter 回 4xx：Key 無效、額度不足、參數有問題。
     *
     * <p>這些是呼叫端的錯誤，原樣回傳上游的狀態碼與訊息（但不含 Key）。
     */
    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<Map<String, Object>> onClientError(HttpClientErrorException e) {
        log.warn("閘道收到上游 4xx：{}", e.getStatusCode().value());
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of(
                        "error", "upstream_client_error",
                        "detail", safeDetail(e)));
    }

    /** OpenRouter 回 5xx：上游服務本身有問題。 */
    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<Map<String, Object>> onServerError(HttpServerErrorException e) {
        log.error("閘道收到上游 5xx：{}", e.getStatusCode().value());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of(
                        "error", "upstream_server_error",
                        "detail", safeDetail(e)));
    }

    /** 連線不到上游（逾時、DNS 失敗）。 */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, Object>> onResourceAccess(ResourceAccessException e) {
        log.error("閘道無法連線上游：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "upstream_unreachable", "detail", "無法連線到上游服務"));
    }

    /** 其他 RestClient 錯誤。 */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, Object>> onRestClient(RestClientException e) {
        log.error("閘道呼叫上游失敗：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "upstream_error", "detail", "上游服務回應異常"));
    }

    /**
     * 缺少 API Key。
     *
     * <p>閘道沒有 Key 就沒辦法做事，明確回 401 讓前端提示使用者去設定。
     */
    @ExceptionHandler(MissingApiKeyException.class)
    public ResponseEntity<Map<String, Object>> onMissingKey(MissingApiKeyException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "missing_api_key", "detail", "請提供 X-OpenRouter-Key"));
    }

    /** 請求內容不合法。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> onIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "bad_request", "detail", String.valueOf(e.getMessage())));
    }

    /** API Key 的樣式：sk-or-v1- 後接一段字元。 */
    private static final java.util.regex.Pattern KEY_PATTERN =
            java.util.regex.Pattern.compile("sk-[A-Za-z0-9_\\-]{8,}");

    /**
     * 取出上游錯誤訊息，並遮蔽可能出現的 API Key。
     *
     * <p>上游的錯誤 body 通常只有一行文字（"No auth credentials found"），
     * 不會回顯 Key。但這是公開的端點，寧可多加一道遮蔽 ——
     * 万一上游哪天把送出的標頭原樣貼進錯誤訊息，就會連帶把使用者的 Key
     * 回傳到瀏覽器（再經由畫面截圖或 log 外流）。
     *
     * <p>同時截斷長度，避免塞爆回應。
     */
    private String safeDetail(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return e.getMessage();
        }
        String masked = KEY_PATTERN.matcher(body).replaceAll("sk-****");
        return masked.length() > 300 ? masked.substring(0, 300) : masked;
    }
}
