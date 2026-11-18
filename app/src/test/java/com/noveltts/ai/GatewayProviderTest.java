package com.noveltts.ai;

import com.noveltts.config.CryptoService;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GatewayProvider} 的行為驗證。
 *
 * <p>重點：閘道用的 provider 不對應資料庫任何一列，API Key 只在記憶體中，
 * 預設值填補後不會是 null。
 */
class GatewayProviderTest {

    @Test
    void 空白欄位補上預設值() {
        GatewayProvider p = new GatewayProvider(null, null, "sk-or-v1-test", null, null);

        assertEquals("openrouter", p.provider());
        assertEquals("https://openrouter.ai/api/v1", p.baseUrl());
        assertTrue(p.extraHeaders().isEmpty());
    }

    @Test
    void 保留使用者的設定() {
        GatewayProvider p = new GatewayProvider("openai", "https://example.com/v1",
                "sk-or-v1-abcdefgh", java.util.Map.of("X-Foo", "bar"), 30);

        assertEquals("openai", p.provider());
        assertEquals("https://example.com/v1", p.baseUrl());
        assertEquals(30, p.timeoutSeconds());
        assertEquals("bar", p.extraHeaders().get("X-Foo"));
    }

    @Test
    void 遮蔽後不顯示完整Key() {
        GatewayProvider p = new GatewayProvider("openrouter", null, "sk-or-v1-aaaaaaaabbbb", null, null);

        assertEquals("sk-****bbbb", p.maskedApiKey());
        assertFalse(p.maskedApiKey().contains("aaaaaaaabbbb"));
    }

    @Test
    void 短Key完全遮蔽() {
        assertEquals("****", new GatewayProvider("openrouter", null, "short", null, null).maskedApiKey());
        assertEquals("****", new GatewayProvider("openrouter", null, null, null, null).maskedApiKey());
    }

    @Test
    void ApiKey不為空時遮蔽結果長度合理() {
        GatewayProvider p = new GatewayProvider("openrouter", null, "sk-or-v1-1234567890", null, null);
        String masked = p.maskedApiKey();

        assertTrue(masked.startsWith("sk-"));
        assertTrue(masked.endsWith("7890"));
        assertTrue(masked.contains("****"));
    }
}
