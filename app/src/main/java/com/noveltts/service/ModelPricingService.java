package com.noveltts.service;

import com.noveltts.domain.entity.ModelPricing;
import com.noveltts.domain.repository.ModelPricingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 模型定價管理與成本估算。
 *
 * <p>定價來源為 OpenRouter models API（{@code GET /api/v1/models}），
 * 同步後快取於 {@code model_pricing} 表。
 *
 * <p>兩種成本來源：
 * <ul>
 *   <li><b>實際回報</b>：Chat Completions 回應的 {@code usage.cost}，這是 OpenRouter
 *       實際扣款金額，優先採用。</li>
 *   <li><b>估算</b>：TTS 端點只回傳 raw audio，沒有費用欄位，只能用
 *       「定價 × 字元數」估算。這就是 {@code usage_log.estimated_cost} 的來源。</li>
 * </ul>
 */
@Service
public class ModelPricingService {

    private static final Logger log = LoggerFactory.getLogger(ModelPricingService.class);

    private static final String MODELS_URL = "https://openrouter.ai/api/v1/models";
    private static final String SPEECH_MODELS_URL =
            "https://openrouter.ai/api/v1/models?output_modalities=speech";

    private final ModelPricingRepository repository;
    private final RestClient.Builder restClientBuilder;

    public ModelPricingService(ModelPricingRepository repository, RestClient.Builder restClientBuilder) {
        this.repository = repository;
        this.restClientBuilder = restClientBuilder;
    }

    /**
     * 從 OpenRouter 同步模型定價。
     *
     * <p>一般模型與語音模型分別抓取 —— 語音模型只出現在
     * {@code ?output_modalities=speech} 的結果中。
     *
     * @return 同步的模型數
     */
    @Transactional
    public int syncFromOpenRouter() {
        RestClient client = restClientBuilder.build();
        int count = 0;

        try {
            count += sync(client, MODELS_URL, false);
        } catch (RestClientException e) {
            log.warn("同步一般模型定價失敗: {}", e.getMessage());
        }
        try {
            count += sync(client, SPEECH_MODELS_URL, true);
        } catch (RestClientException e) {
            log.warn("同步語音模型定價失敗: {}", e.getMessage());
        }

        log.info("已同步 {} 個模型的定價", count);
        return count;
    }

    @SuppressWarnings("unchecked")
    private int sync(RestClient client, String url, boolean speech) {
        Map<String, Object> response = client.get().uri(url).retrieve().body(Map.class);
        if (response == null) {
            return 0;
        }
        Object data = response.get("data");
        if (!(data instanceof List<?> list)) {
            return 0;
        }

        int count = 0;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object id = map.get("id");
            if (!(id instanceof String modelId)) {
                continue;
            }

            ModelPricing pricing = repository.findByModelId(modelId)
                    .orElseGet(() -> {
                        ModelPricing p = new ModelPricing();
                        p.setModelId(modelId);
                        return p;
                    });

            Object displayName = map.get("name");
            if (displayName instanceof String s) {
                pricing.setDisplayName(s);
            }

            Object pricingMap = map.get("pricing");
            if (pricingMap instanceof Map<?, ?> pm) {
                pricing.setPromptPrice(toDecimal(pm.get("prompt")));
                pricing.setCompletionPrice(toDecimal(pm.get("completion")));
                pricing.setAudioPrice(toDecimal(pm.get("audio")));
            }

            // 語音模型的 pricing 單位可能是「每字元」，此時 prompt 價格代表每字元費用。
            pricing.setSupportsSpeech(speech);
            pricing.setPricingUnit(speech ? "CHARACTER" : "TOKEN");
            pricing.setSyncedAt(OffsetDateTime.now());

            repository.save(pricing);
            count++;
        }
        return count;
    }

    /**
     * 估算語音合成的成本。
     *
     * <p>TTS 端點不回傳費用，只能依定價與字元數估算。
     *
     * @param modelId   模型 id（如 {@code google/gemini-3.8-flash-tts}）
     * @param charCount 輸入字元數
     * @return 估算費用（USD）；查不到定價時回傳 null
     */
    @Transactional(readOnly = true)
    public BigDecimal estimateSpeechCost(String modelId, Integer charCount) {
        if (modelId == null || charCount == null || charCount <= 0) {
            return null;
        }
        Optional<ModelPricing> found = repository.findByModelId(modelId);
        if (found.isEmpty()) {
            return null;
        }
        ModelPricing pricing = found.get();
        BigDecimal unitPrice = pricing.getPromptPrice();
        if (unitPrice == null || unitPrice.signum() == 0) {
            return null;
        }
        return unitPrice.multiply(BigDecimal.valueOf(charCount))
                .setScale(8, RoundingMode.HALF_UP);
    }

    /** 查詢模型定價。 */
    @Transactional(readOnly = true)
    public Optional<ModelPricing> find(String modelId) {
        return modelId == null ? Optional.empty() : repository.findByModelId(modelId);
    }

    private BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal d) {
            return d;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }
}
