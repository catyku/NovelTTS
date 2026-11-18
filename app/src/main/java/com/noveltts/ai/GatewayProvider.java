package com.noveltts.ai;

import java.util.Map;

/**
 * 無狀態 AI 閘道用的 provider 設定。
 *
 * <p>與 {@link com.noveltts.domain.entity.AiProviderConfig} 的差別：這個不對應資料庫任何一列，
 * API Key 由呼叫端（瀏覽器）在每次請求中提供，伺服器端不保存、不落盤。
 *
 * <p>設計前提：使用者的 OpenRouter Key 存放在瀏覽器 localStorage，
 * 前端呼叫閘道時以 {@code X-OpenRouter-Key} 標頭帶上。
 * 伺服器只在該次請求的記憶體中使用它，請求結束即釋放。
 */
public record GatewayProvider(
        String provider,
        String baseUrl,
        String apiKey,
        Map<String, String> extraHeaders,
        Integer timeoutSeconds
) {
    public GatewayProvider {
        if (provider == null || provider.isBlank()) {
            provider = "openrouter";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://openrouter.ai/api/v1";
        }
        if (extraHeaders == null) {
            extraHeaders = Map.of();
        }
    }

    /** 遮蔽後的 key，供除錯與前端顯示用。 */
    public String maskedApiKey() {
        if (apiKey == null || apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 3) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
