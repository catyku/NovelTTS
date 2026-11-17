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

    /** 小說匯入。groupId 指定所屬群組，groupOrder 為群組內的集數順序。 */
    public record NovelForm(String title, String author, String content,
                            Long groupId, Integer groupOrder) {
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

    /** 小說群組（連載分集共用設定）。 */
    public record GroupForm(Long id, String title, String author, String description,
                            Long narratorVoiceId, String narratorInstructions,
                            Integer ttsGroupMaxChars, String status) {
    }

    /** 群組共用角色。 */
    public record GroupCharacterForm(Long id, String name, String[] aliases, String gender,
                                     String ageGroup, String personality, String description,
                                     Long ttsVoiceId, String ttsInstructions, String colorHex) {
    }

    /** 小說匯入（可指定所屬群組與集數順序）。 */
    public record GeneralSettingsForm(String globalInstructions, String narratorVoiceId,
                                      String defaultVoiceId, Boolean autoAssignVoice,
                                      Integer chapterMaxChars, Integer ttsConcurrency,
                                      Integer ttsGroupMaxChars) {
    }
}
