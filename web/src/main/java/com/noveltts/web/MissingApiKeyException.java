package com.noveltts.web;

/**
 * 缺少 API Key。
 *
 * <p>閘道沒有 Key 就無從呼叫上游。回 401 讓前端能明確提示
 * 「請先在設定頁填寫你的 OpenRouter API Key」，而不是籠統的錯誤。
 */
public class MissingApiKeyException extends RuntimeException {

    public MissingApiKeyException(String message) {
        super(message);
    }
}
