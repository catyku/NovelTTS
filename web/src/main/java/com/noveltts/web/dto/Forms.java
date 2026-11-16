package com.noveltts.web.dto;

import java.util.Map;

/**
 * API 請求表單 DTO。
 */
public final class Forms {

    private Forms() {
    }

    public record ProviderForm(Long id, String provider, String name, String baseUrl,
                                String apiKey, Map<String, String> extraHeaders,
                                Integer timeoutSeconds, Boolean enabled, Boolean isDefault) {
    }

    public record ModelSettingForm(Long id, Long providerConfigId, String purpose, String modelId,
                                    Double temperature, Integer maxTokens,
                                    Map<String, Object> params, Boolean enabled, Boolean isDefault,
                                    String fallbackModelId) {
    }

    public record VoiceForm(Long id, String provider, String model, String voiceId, String displayName,
                            String gender, String language, String styleDesc, String sampleUrl,
                            Boolean enabled, Integer sortOrder) {
    }

    public record NovelForm(String title, String author, String content) {
    }

    public record ChapterForm(String title, String content) {
    }

    public record CharacterForm(String name, String[] aliases, String gender, String ageGroup,
                                String personality, String description, Long ttsVoiceId,
                                String ttsInstructions, String colorHex) {
    }

    public record SegmentForm(String text, Long characterId, String voiceId, String instructions,
                              String emotion, String styleHint) {
    }

    public record GeneralSettingsForm(String globalInstructions, String narratorVoiceId,
                                      String defaultVoiceId, Boolean autoAssignVoice,
                                      Integer chapterMaxChars, Integer ttsConcurrency,
                                      Integer ttsGroupMaxChars) {
    }
}
