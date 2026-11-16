package com.noveltts.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * OpenRouter models API 回應中需要的部分。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelInfo(
        String id,
        String name,
        List<String> supported_voices,
        List<String> output_modalities,
        Map<String, Object> pricing
) {
}
