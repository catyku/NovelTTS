package com.noveltts.ai;

import com.noveltts.ai.dto.ChatUsage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 成本解析測試。
 *
 * 驗證 OpenRouter 回應的 {@code usage.cost} 與
 * {@code usage.cost_details.upstream_inference_cost} 能被正確解析，
 * 以及 BYOK 路由的費用加總邏輯。
 */
class CostExtractionTest {

    private final OpenRouterClient client = new OpenRouterClient(null, null);

    /**
     * 透過反射呼叫 private 的 extractUsage —— 這個方法不該被外部直接使用，
     * 但它是成本記錄的關鍵路徑，值得單獨測試。
     */
    private ChatUsage extract(Map<String, Object> response) {
        try {
            var method = OpenRouterClient.class.getDeclaredMethod("extractUsage", Map.class);
            method.setAccessible(true);
            return (ChatUsage) method.invoke(client, response);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("無法呼叫 extractUsage", e);
        }
    }

    private Map<String, Object> usage(Object usage) {
        Map<String, Object> response = new HashMap<>();
        response.put("usage", usage);
        return response;
    }

    @Test
    void 解析一般路由的費用() {
        Map<String, Object> u = new HashMap<>();
        u.put("prompt_tokens", 737);
        u.put("completion_tokens", 448);
        u.put("cost", 0.0012345);
        u.put("is_byok", false);

        ChatUsage result = extract(usage(u));

        assertThat(result.promptTokens()).isEqualTo(737);
        assertThat(result.completionTokens()).isEqualTo(448);
        assertThat(result.cost()).isEqualByComparingTo("0.0012345");
        assertThat(result.upstreamInferenceCost()).isNull();
        assertThat(result.isByok()).isFalse();
        assertThat(result.totalSpend()).isEqualByComparingTo("0.0012345");
    }

    @Test
    void byok路由需加上上游推論費() {
        Map<String, Object> details = new HashMap<>();
        details.put("upstream_inference_cost", 0.00007665);

        Map<String, Object> u = new HashMap<>();
        u.put("prompt_tokens", 100);
        u.put("completion_tokens", 50);
        // BYOK 時 cost 只是 OpenRouter 的轉接手續費
        u.put("cost", 0.0);
        u.put("is_byok", true);
        u.put("cost_details", details);

        ChatUsage result = extract(usage(u));

        assertThat(result.isByok()).isTrue();
        assertThat(result.cost()).isEqualByComparingTo("0");
        assertThat(result.upstreamInferenceCost()).isEqualByComparingTo("0.00007665");
        // 總支出必須包含上游推論費，否則 BYOK 會記成 0
        assertThat(result.totalSpend()).isEqualByComparingTo("0.00007665");
    }

    @Test
    void 支援數字字串形式的費用() {
        Map<String, Object> u = new HashMap<>();
        u.put("prompt_tokens", 10);
        u.put("cost", "0.0005");

        ChatUsage result = extract(usage(u));

        assertThat(result.cost()).isEqualByComparingTo("0.0005");
        assertThat(result.totalSpend()).isEqualByComparingTo("0.0005");
    }

    @Test
    void 無費用欄位時回傳null而非例外() {
        Map<String, Object> u = new HashMap<>();
        u.put("prompt_tokens", 10);
        u.put("completion_tokens", 20);

        ChatUsage result = extract(usage(u));

        assertThat(result.cost()).isNull();
        assertThat(result.totalSpend()).isNull();
    }

    @Test
    void 無usage物件時回傳空值() {
        ChatUsage result = extract(new HashMap<>());

        assertThat(result.promptTokens()).isNull();
        assertThat(result.totalSpend()).isNull();
    }

    @Test
    void 費用字串無法解析時視為無費用() {
        Map<String, Object> u = new HashMap<>();
        u.put("cost", "not-a-number");

        ChatUsage result = extract(usage(u));

        assertThat(result.cost()).isNull();
    }

    @Test
    void 科學記號形式的費用能正確解析() {
        Map<String, Object> u = new HashMap<>();
        u.put("cost", 8.41005E-5);

        ChatUsage result = extract(usage(u));

        assertThat(result.cost()).isEqualByComparingTo(new BigDecimal("0.0000841005"));
    }
}
