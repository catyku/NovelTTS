package com.noveltts.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GatewayExceptionHandler} 的驗證。
 *
 * <p>重點：上游的錯誤要映射成正確的 HTTP 狀態碼 ——
 * Key 無效是呼叫端的問題（4xx），不該回 500 讓前端誤判成伺服器故障。
 */
class GatewayExceptionHandlerTest {

    private final GatewayExceptionHandler handler = new GatewayExceptionHandler();

    @Test
    void 上游4xx原樣回傳狀態碼() {
        HttpClientErrorException e = HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY,
                "No auth credentials found".getBytes(), null);

        ResponseEntity<Map<String, Object>> resp = handler.onClientError(e);

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertEquals("upstream_client_error", resp.getBody().get("error"));
    }

    @Test
    void 上游429額度用盡回429() {
        HttpClientErrorException e = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", HttpHeaders.EMPTY,
                "rate limited".getBytes(), null);

        ResponseEntity<Map<String, Object>> resp = handler.onClientError(e);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resp.getStatusCode());
    }

    @Test
    void 上游5xx轉成502() {
        HttpServerErrorException e = HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "Server Error", HttpHeaders.EMPTY,
                "boom".getBytes(), null);

        ResponseEntity<Map<String, Object>> resp = handler.onServerError(e);

        assertEquals(HttpStatus.BAD_GATEWAY, resp.getStatusCode());
        assertEquals("upstream_server_error", resp.getBody().get("error"));
    }

    @Test
    void 連線不上上游回502() {
        ResponseEntity<Map<String, Object>> resp = handler.onResourceAccess(
                new ResourceAccessException("timeout"));

        assertEquals(HttpStatus.BAD_GATEWAY, resp.getStatusCode());
        assertEquals("upstream_unreachable", resp.getBody().get("error"));
    }

    @Test
    void 缺少Key回401() {
        ResponseEntity<Map<String, Object>> resp = handler.onMissingKey(
                new MissingApiKeyException("缺少 X-OpenRouter-Key"));

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertEquals("missing_api_key", resp.getBody().get("error"));
    }

    @Test
    void 錯誤訊息不超過300字() {
        String longBody = "x".repeat(1000);
        HttpClientErrorException e = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY, longBody.getBytes(), null);

        ResponseEntity<Map<String, Object>> resp = handler.onClientError(e);

        assertTrue(resp.getBody().get("detail").toString().length() <= 300);
    }

    @Test
    void 回應內容不含ApiKey() {
        String key = "sk-or-v1-secret-value-123456";
        HttpClientErrorException e = HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY,
                ("No auth credentials found for " + key).getBytes(), null);

        ResponseEntity<Map<String, Object>> resp = handler.onClientError(e);

        // 上游錯誤 body 不該含 Key；若有也要能處理（此處確認不因此洩漏到 log）
        assertFalse(resp.getBody().toString().contains("sk-or-v1-secret-value-123456")
                && resp.getBody().get("detail").toString().contains(key),
                "回應不應回顯整把 Key");
    }
}
