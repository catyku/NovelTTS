package com.noveltts.ai.dto;

import java.math.BigDecimal;

/**
 * AI 呼叫的用量資訊。
 *
 * @param promptTokens     輸入 token 數
 * @param completionTokens 輸出 token 數
 * @param cost             OpenRouter 回報的總費用（USD）。BYOK 時這只是 OpenRouter 的
 *                         手續費，真實推論花費另計於 upstreamInferenceCost
 * @param upstreamInferenceCost 上游模型實際收費（USD）。一般路由為 0 或 null；
 *                         BYOK 路由才有值。此值加上 cost 才是實際總支出
 * @param isByok           是否為 BYOK（自帶金鑰）路由
 */
public record ChatUsage(
        Integer promptTokens,
        Integer completionTokens,
        BigDecimal cost,
        BigDecimal upstreamInferenceCost,
        Boolean isByok
) {

    /** 無任何用量資訊時的預設值。 */
    public static ChatUsage empty() {
        return new ChatUsage(null, null, null, null, null);
    }

    /**
     * 實際總支出。
     *
     * 一般路由：cost 已是完整費用，upstream 為 0/null。
     * BYOK 路由：cost 只是 OpenRouter 手續費，需加上上游推論費。
     */
    public BigDecimal totalSpend() {
        if (cost == null && upstreamInferenceCost == null) {
            return null;
        }
        BigDecimal total = cost == null ? BigDecimal.ZERO : cost;
        if (upstreamInferenceCost != null) {
            total = total.add(upstreamInferenceCost);
        }
        return total;
    }
}
