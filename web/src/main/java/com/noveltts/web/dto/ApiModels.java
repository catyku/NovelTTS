package com.noveltts.web.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * API 回應 DTO 集合。
 */
public final class ApiModels {

    private ApiModels() {
    }

    public record ProviderDto(Long id, String provider, String name, String baseUrl,
                               String maskedApiKey, boolean hasApiKey,
                               Map<String, String> extraHeaders, int timeoutSeconds,
                               boolean enabled, boolean isDefault) {
    }

    public record ModelSettingDto(Long id, Long providerConfigId, String purpose, String modelId,
                                  Double temperature, Integer maxTokens, Map<String, Object> params,
                                  boolean enabled, boolean isDefault, String fallbackModelId) {
    }

    public record VoiceDto(Long id, String provider, String model, String voiceId, String displayName,
                           String gender, String language, String styleDesc, String sampleUrl,
                           boolean enabled, int sortOrder) {
    }

    public record NovelDto(Long id, String title, String author, String status,
                           int totalChapters, int totalChars, OffsetDateTime createdAt) {
    }

    public record ChapterDto(Long id, Long novelId, int chapterIndex, String title,
                             int charCount, String status, int segmentCount) {
    }

    public record CharacterDto(Long id, Long novelId, String name, String[] aliases,
                               String gender, String ageGroup, String personality,
                               String description, Long ttsVoiceId, String voiceId,
                               String ttsInstructions, String colorHex, int segmentCount) {
    }

    public record SegmentDto(Long id, Long chapterId, int seq, String type, Long characterId,
                             String speakerName, String text, String emotion, String styleHint,
                             String instructions, String voiceId, String ttsStatus,
                             Long audioId, Integer durationMs, String audioUrl) {
    }

    public record AudioDto(Long id, String kind, Long chapterId, Long segmentId, String fileName,
                           String format, Integer durationMs, Long byteSize, String provider,
                           String model, String voiceId, String generationId, String downloadUrl) {
    }

    public record JobDto(Long id, String type, Long refId, String status, int progress, int total,
                         String message, String errorMessage) {
    }

    public record ApiError(String error, String detail, String traceId) {
    }

    public record SimpleResult(boolean ok, String message) {
    }

    public record CountResult(int count) {
    }

    /**
     * 成本統計。
     *
     * @param totalCostUsd 總費用（USD）
     * @param textCostUsd  文字模型費用（OpenRouter 回報的實際扣款）
     * @param audioCostUsd 語音合成費用（依定價估算）
     * @param recordsWithCost 有費用紀錄的筆數
     * @param totalRecords  總紀錄筆數
     */
    public record CostSummary(
            java.math.BigDecimal totalCostUsd,
            java.math.BigDecimal textCostUsd,
            java.math.BigDecimal audioCostUsd,
            long recordsWithCost,
            long totalRecords
    ) {
    }
}
