package com.noveltts.ai;

/**
 * 上游 AI 服務呼叫失敗。攜帶 HTTP status 以便區分「可重試」與「不可重試」。
 * - 429 / 5xx：可重試（指數退避）
 * - 其他 4xx：不可重試（例如 401 API Key 錯誤、400 參數錯誤）
 */
public class AiCallException extends RuntimeException {

    private final int statusCode;

    public AiCallException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public AiCallException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    /** 是否值得重試。 */
    public boolean isRetryable() {
        // 只重試明確的暫時性錯誤。
        //
        // 傳輸層失敗（statusCode 0，含讀取逾時）刻意不重試 ——
        // 伺服器端逾時就快速回應，讓使用者按「繼續 AI 分析」重試那一章。
        // 在伺服器端重試會讓整個請求拖過 Cloudflare 的 100 秒上限而變成 524。
        return statusCode == 429 || statusCode >= 500;
    }
}
