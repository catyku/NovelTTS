package com.noveltts.ai;

import com.noveltts.ai.dto.ChatMessage;
import com.noveltts.ai.dto.ChatResult;
import com.noveltts.ai.dto.ChatUsage;
import com.noveltts.ai.dto.ModelInfo;
import com.noveltts.ai.dto.SpeechResult;
import com.noveltts.ai.dto.TtsRequest;
import com.noveltts.config.CryptoService;
import com.noveltts.domain.entity.AiProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenRouter 統一客戶端。
 * - 分析：POST {baseUrl}/chat/completions
 * - 合成：POST {baseUrl}/audio/speech（回傳 raw audio 位元組流）
 * - 模型：GET  {baseUrl}/models
 */
@Component
public class OpenRouterClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterClient.class);

    private final RestClient.Builder builder;
    private final CryptoService cryptoService;

    public OpenRouterClient(RestClient.Builder builder, CryptoService cryptoService) {
        this.builder = builder;
        this.cryptoService = cryptoService;
    }

    /**
     * 無狀態閘道版：API Key 由呼叫端在本次請求中提供，伺服器不保存。
     */
    public ChatResult chat(GatewayProvider config, String model, List<ChatMessage> messages,
                           Double temperature, Integer maxTokens, Map<String, Object> extraParams) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList());
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        if (extraParams != null && !extraParams.isEmpty()) {
            body.putAll(extraParams);
        }
        body.put("response_format", Map.of("type", "json_object"));

        Map<String, Object> response = exchange(config, "/chat/completions", body, Map.class);
        String content = extractContent(response);
        ChatUsage usage = extractUsage(response);
        return new ChatResult(content, usage);
    }

    /**
     * 無狀態閘道版語音合成：回傳 raw PCM 位元組。
     */
    public SpeechResult speech(GatewayProvider config, TtsRequest request) {
        RestClient client = buildClient(config);
        ResponseEntity<byte[]> response = client.post()
                .uri("/audio/speech")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(byte[].class);

        byte[] body = response.getBody();
        if (body == null || body.length == 0) {
            throw new IllegalStateException("TTS 回應內容為空");
        }
        String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        String generationId = response.getHeaders().getFirst("X-Generation-Id");
        return new SpeechResult(body, contentType, generationId);
    }

    /**
     * 無狀態閘道版模型清單。
     */
    @SuppressWarnings("unchecked")
    public List<ModelInfo> listModels(GatewayProvider config) {
        RestClient client = buildClient(config);
        Map<String, Object> response = client.get()
                .uri(uriBuilder -> uriBuilder.path("/models")
                        .queryParam("output_modalities", "speech")
                        .build())
                .retrieve()
                .body(Map.class);
        return parseModels(response);
    }

    public ChatResult chat(AiProviderConfig config, String model, List<ChatMessage> messages,
                           Double temperature, Integer maxTokens, Map<String, Object> extraParams) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList());
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        if (extraParams != null && !extraParams.isEmpty()) {
            body.putAll(extraParams);
        }
        body.put("response_format", Map.of("type", "json_object"));

        Map<String, Object> response = exchange(config, "/chat/completions", body, Map.class);

        String content = extractContent(response);
        ChatUsage usage = extractUsage(response);
        return new ChatResult(content, usage);
    }

    public SpeechResult speech(AiProviderConfig config, TtsRequest request) {
        String apiKey = cryptoService.resolveApiKey(config);
        RestClient client = buildClient(config, apiKey);

        ResponseEntity<byte[]> response = client.post()
                .uri("/audio/speech")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(byte[].class);

        byte[] body = response.getBody();
        if (body == null || body.length == 0) {
            throw new IllegalStateException("TTS 回應內容為空");
        }
        String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        String generationId = response.getHeaders().getFirst("X-Generation-Id");
        return new SpeechResult(body, contentType, generationId);
    }

    @SuppressWarnings("unchecked")
    public List<ModelInfo> listModels(AiProviderConfig config) {
        String apiKey = cryptoService.resolveApiKey(config);
        RestClient client = buildClient(config, apiKey);
        Map<String, Object> response = client.get()
                .uri(uriBuilder -> uriBuilder.path("/models")
                        .queryParam("output_modalities", "speech")
                        .build())
                .retrieve()
                .body(Map.class);
        return parseModels(response);
    }

    /** 解析 OpenRouter models 回應。 */
    @SuppressWarnings("unchecked")
    private List<ModelInfo> parseModels(Map<String, Object> response) {
        List<ModelInfo> result = new ArrayList<>();
        if (response == null) {
            return result;
        }
        Object data = response.get("data");
        if (data instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add(toModelInfo((Map<String, Object>) map));
                }
            }
        }
        return result;
    }

    /** 測試 provider 連線是否可用。 */
    public String testConnection(AiProviderConfig config) {
        String apiKey = cryptoService.resolveApiKey(config);
        RestClient client = buildClient(config, apiKey);
        Map<String, Object> response = client.get()
                .uri("/models")
                .retrieve()
                .body(Map.class);
        return response == null ? "回應為空" : "連線成功";
    }

    @SuppressWarnings("unchecked")
    private ModelInfo toModelInfo(Map<String, Object> map) {
        List<String> voices = new ArrayList<>();
        Object v = map.get("supported_voices");
        if (v instanceof List<?> list) {
            for (Object o : list) {
                voices.add(String.valueOf(o));
            }
        }
        List<String> modalities = new ArrayList<>();
        Object m = map.get("output_modalities");
        if (m instanceof List<?> list) {
            for (Object o : list) {
                modalities.add(String.valueOf(o));
            }
        }
        Map<String, Object> pricing = Map.of();
        Object p = map.get("pricing");
        if (p instanceof Map<?, ?> pm) {
            pricing = (Map<String, Object>) pm;
        }
        return new ModelInfo(
                String.valueOf(map.get("id")),
                map.get("name") == null ? null : String.valueOf(map.get("name")),
                voices, modalities, pricing);
    }

    @SuppressWarnings("unchecked")
    /** 無狀態閘道版 POST 呼叫：API Key 由本次請求提供。 */
    private Map<String, Object> exchange(GatewayProvider config, String path,
                                         Map<String, Object> body, Class<?> type) {
        RestClient client = buildClient(config);
        try {
            Map<String, Object> response = client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                throw new IllegalStateException("AI 回應為空");
            }
            return response;
        } catch (RestClientResponseException e) {
            String detail = e.getResponseBodyAsString();
            throw new AiCallException(e.getStatusCode().value(),
                    "OpenRouter 呼叫失敗 (HTTP " + e.getStatusCode().value() + "): "
                            + (detail == null || detail.isBlank() ? e.getMessage() : detail), e);
        } catch (RestClientException e) {
            throw new AiCallException(0, "OpenRouter 呼叫失敗: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> exchange(AiProviderConfig config, String path,
                                          Map<String, Object> body, Class<?> type) {
        String apiKey = cryptoService.resolveApiKey(config);
        RestClient client = buildClient(config, apiKey);
        try {
            Map<String, Object> response = client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                throw new IllegalStateException("AI 回應為空");
            }
            return response;
        } catch (RestClientResponseException e) {
            // 附上上游回應 body，方便診斷（例如 OpenRouter 的錯誤訊息）
            String detail = e.getResponseBodyAsString();
            throw new AiCallException(e.getStatusCode().value(),
                    "OpenRouter 呼叫失敗 (HTTP " + e.getStatusCode().value() + "): "
                            + (detail == null || detail.isBlank() ? e.getMessage() : detail), e);
        } catch (RestClientException e) {
            throw new AiCallException(0, "OpenRouter 呼叫失敗: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> response) {
        Object choices = response.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> map) {
                Object message = map.get("message");
                if (message instanceof Map<?, ?> mm) {
                    Object content = mm.get("content");
                    if (content != null) {
                        return String.valueOf(content);
                    }
                }
            }
        }
        throw new IllegalStateException("AI 回應格式不符（缺少 choices[0].message.content）: " + response);
    }

    /**
     * 解析 OpenRouter 回應的 usage。
     *
     * 費用欄位（USD）：
     * - {@code usage.cost}：OpenRouter 收取的總額。BYOK 路由時這只是轉接手續費。
     * - {@code usage.cost_details.upstream_inference_cost}：上游模型實際推論費。
     *   一般路由為 0 或 null，BYOK 路由才有值。
     *
     * 兩者相加才是實際總支出，詳見 {@link ChatUsage#totalSpend()}。
     */
    private ChatUsage extractUsage(Map<String, Object> response) {
        Object usage = response.get("usage");
        if (usage instanceof Map<?, ?> map) {
            Object prompt = map.get("prompt_tokens");
            Object completion = map.get("completion_tokens");
            return new ChatUsage(
                    prompt instanceof Number n ? n.intValue() : null,
                    completion instanceof Number n ? n.intValue() : null,
                    toDecimal(map.get("cost")),
                    extractUpstreamCost(map.get("cost_details")),
                    toBoolean(map.get("is_byok")));
        }
        return ChatUsage.empty();
    }

    private BigDecimal extractUpstreamCost(Object costDetails) {
        if (costDetails instanceof Map<?, ?> map) {
            return toDecimal(map.get("upstream_inference_cost"));
        }
        return null;
    }

    /** OpenRouter 可能回傳數字或數字字串，兩者都要能解析。 */
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
                return null;
            }
        }
        return null;
    }

    private Boolean toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return null;
    }

    private RestClient buildClient(GatewayProvider config) {
        RestClient.Builder b = builder.clone().baseUrl(config.baseUrl());
        b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + (config.apiKey() == null ? "" : config.apiKey()));
        for (Map.Entry<String, String> e : config.extraHeaders().entrySet()) {
            b.defaultHeader(e.getKey(), e.getValue());
        }
        return b.build();
    }

    private RestClient buildClient(AiProviderConfig config, String apiKey) {
        RestClient.Builder b = builder.clone().baseUrl(config.getBaseUrl());
        b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + (apiKey == null ? "" : apiKey));
        for (Map.Entry<String, String> e : config.getExtraHeaders().entrySet()) {
            b.defaultHeader(e.getKey(), e.getValue());
        }
        return b.build();
    }
}
