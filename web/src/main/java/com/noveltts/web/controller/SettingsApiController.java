package com.noveltts.web.controller;

import com.noveltts.web.exception.NotFoundException;
import com.noveltts.ai.OpenRouterClient;
import com.noveltts.ai.dto.ModelInfo;
import com.noveltts.config.CryptoService;
import com.noveltts.config.NovelTtsProperties;
import com.noveltts.domain.entity.AiModelSetting;
import com.noveltts.domain.entity.AiProviderConfig;
import com.noveltts.domain.entity.TtsVoice;
import com.noveltts.domain.enums.ModelPurpose;
import com.noveltts.domain.repository.AiModelSettingRepository;
import com.noveltts.domain.repository.AiProviderConfigRepository;
import com.noveltts.domain.repository.TtsVoiceRepository;
import com.noveltts.domain.repository.UsageLogRepository;
import com.noveltts.service.AiSettingsService;
import com.noveltts.service.ModelPricingService;
import com.noveltts.web.dto.ApiModels;
import com.noveltts.web.dto.Forms;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsApiController {

    private final AiProviderConfigRepository providerRepository;
    private final AiModelSettingRepository modelRepository;
    private final TtsVoiceRepository voiceRepository;
    private final AiSettingsService aiSettingsService;
    private final CryptoService cryptoService;
    private final NovelTtsProperties properties;
    private final OpenRouterClient openRouterClient;
    private final UsageLogRepository usageLogRepository;
    private final ModelPricingService modelPricingService;

    public SettingsApiController(AiProviderConfigRepository providerRepository,
                                 AiModelSettingRepository modelRepository,
                                 TtsVoiceRepository voiceRepository,
                                 AiSettingsService aiSettingsService,
                                 CryptoService cryptoService,
                                 NovelTtsProperties properties,
                                 OpenRouterClient openRouterClient,
                                 UsageLogRepository usageLogRepository,
                                 ModelPricingService modelPricingService) {
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
        this.voiceRepository = voiceRepository;
        this.aiSettingsService = aiSettingsService;
        this.cryptoService = cryptoService;
        this.properties = properties;
        this.openRouterClient = openRouterClient;
        this.usageLogRepository = usageLogRepository;
        this.modelPricingService = modelPricingService;
    }

    // ---------- cost ----------

    /**
     * 成本統計。
     *
     * <p>文字模型（TEXT）的費用來自 OpenRouter 回報的實際扣款金額；
     * 語音合成（AUDIO）的費用是依模型定價與字元數的估算值。
     */
    @GetMapping("/cost")
    public ApiModels.CostSummary costSummary() {
        long total = usageLogRepository.countAll();
        long withCost = usageLogRepository.countWithCost();
        return new ApiModels.CostSummary(
                usageLogRepository.sumEstimatedCost(),
                usageLogRepository.sumTextCost(),
                usageLogRepository.sumAudioCost(),
                withCost,
                total);
    }

    /** 從 OpenRouter 同步模型定價（用於 TTS 成本估算）。 */
    @PostMapping("/pricing/sync")
    public ResponseEntity<ApiModels.CountResult> syncPricing() {
        return ResponseEntity.ok(new ApiModels.CountResult(modelPricingService.syncFromOpenRouter()));
    }

    // ---------- provider ----------

    @GetMapping("/provider")
    public List<ApiModels.ProviderDto> listProviders() {
        return providerRepository.findAllByOrderByIdAsc().stream()
                .map(this::toDto)
                .toList();
    }

    @PostMapping("/provider")
    @Transactional
    public ResponseEntity<ApiModels.ProviderDto> saveProvider(@RequestBody Forms.ProviderForm form) {
        AiProviderConfig config;
        if (form.id() != null) {
            config = providerRepository.findById(form.id())
                    .orElseThrow(() -> new NotFoundException("找不到 Provider " + form.id()));
        } else {
            config = new AiProviderConfig();
        }
        config.setProvider(form.provider() == null ? "openrouter" : form.provider());
        config.setName(form.name() == null ? "OpenRouter" : form.name());
        config.setBaseUrl(form.baseUrl() == null ? "https://openrouter.ai/api/v1" : form.baseUrl());
        if (form.extraHeaders() != null) {
            config.setExtraHeaders(form.extraHeaders());
        }
        if (form.timeoutSeconds() != null) {
            config.setTimeoutSeconds(form.timeoutSeconds());
        }
        if (form.enabled() != null) {
            config.setEnabled(form.enabled());
        }
        if (form.isDefault() != null) {
            if (form.isDefault()) {
                providerRepository.findAllByOrderByIdAsc().forEach(p -> p.setIsDefault(false));
                providerRepository.saveAll(providerRepository.findAllByOrderByIdAsc());
            }
            config.setIsDefault(form.isDefault());
        }
        // API Key：留空代表不變更
        if (form.apiKey() != null && !form.apiKey().isBlank()) {
            config.setApiKeyEncrypted(cryptoService.encrypt(form.apiKey()));
        }
        AiProviderConfig saved = providerRepository.save(config);
        return ResponseEntity.ok(toDto(saved));
    }

    @DeleteMapping("/provider/{id}")
    public ResponseEntity<Void> deleteProvider(@PathVariable Long id) {
        providerRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/provider/{id}/test")
    public ResponseEntity<ApiModels.SimpleResult> testProvider(@PathVariable Long id) {
        AiProviderConfig config = providerRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到 Provider " + id));
        try {
            String message = openRouterClient.testConnection(config);
            return ResponseEntity.ok(new ApiModels.SimpleResult(true, message));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ApiModels.SimpleResult(false, e.getMessage()));
        }
    }

    // ---------- model ----------

    @GetMapping("/models")
    public List<ApiModels.ModelSettingDto> listModels(@RequestParam(defaultValue = "ANALYSIS") String purpose) {
        return aiSettingsService.listModels(ModelPurpose.valueOf(purpose)).stream()
                .map(this::toDto)
                .toList();
    }

    @PostMapping("/models")
    @Transactional
    public ResponseEntity<ApiModels.ModelSettingDto> saveModel(@RequestBody Forms.ModelSettingForm form) {
        AiModelSetting setting;
        if (form.id() != null) {
            setting = modelRepository.findById(form.id())
                    .orElseThrow(() -> new NotFoundException("找不到模型設定 " + form.id()));
        } else {
            setting = new AiModelSetting();
        }
        AiProviderConfig provider = providerRepository.findById(form.providerConfigId())
                .orElseThrow(() -> new NotFoundException("找不到 Provider " + form.providerConfigId()));
        setting.setProviderConfig(provider);
        setting.setPurpose(ModelPurpose.valueOf(form.purpose()));
        setting.setModelId(form.modelId());
        if (form.fallbackModelId() != null) {
            setting.setFallbackModelId(form.fallbackModelId().isBlank() ? null : form.fallbackModelId());
        }
        if (form.temperature() != null) {
            setting.setTemperature(java.math.BigDecimal.valueOf(form.temperature()));
        }
        setting.setMaxTokens(form.maxTokens());
        if (form.params() != null) {
            setting.setParams(form.params());
        }
        if (form.enabled() != null) {
            setting.setEnabled(form.enabled());
        }
        if (form.isDefault() != null) {
            if (form.isDefault()) {
                for (AiModelSetting s : modelRepository.findByPurposeOrderByIdAsc(setting.getPurpose())) {
                    s.setIsDefault(false);
                    modelRepository.save(s);
                }
            }
            setting.setIsDefault(form.isDefault());
        }
        return ResponseEntity.ok(toDto(modelRepository.save(setting)));
    }

    @DeleteMapping("/models/{id}")
    public ResponseEntity<Void> deleteModel(@PathVariable Long id) {
        modelRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ---------- voice ----------

    @GetMapping("/voices")
    public List<ApiModels.VoiceDto> listVoices() {
        return voiceRepository.findAllByOrderBySortOrderAscDisplayNameAsc().stream()
                .map(this::toDto)
                .toList();
    }

    @PostMapping("/voices")
    @Transactional
    public ResponseEntity<ApiModels.VoiceDto> saveVoice(@RequestBody Forms.VoiceForm form) {
        TtsVoice voice;
        if (form.id() != null) {
            voice = voiceRepository.findById(form.id())
                    .orElseThrow(() -> new NotFoundException("找不到音色 " + form.id()));
        } else {
            voice = new TtsVoice();
        }
        if (form.provider() != null) {
            voice.setProvider(form.provider());
        }
        voice.setModel(form.model() == null ? "google/gemini-3.8-flash-tts" : form.model());
        voice.setVoiceId(form.voiceId());
        voice.setDisplayName(form.displayName() == null ? form.voiceId() : form.displayName());
        voice.setGender(form.gender());
        voice.setLanguage(form.language());
        voice.setStyleDesc(form.styleDesc());
        voice.setSampleUrl(form.sampleUrl());
        if (form.enabled() != null) {
            voice.setEnabled(form.enabled());
        }
        if (form.sortOrder() != null) {
            voice.setSortOrder(form.sortOrder());
        }
        return ResponseEntity.ok(toDto(voiceRepository.save(voice)));
    }

    @DeleteMapping("/voices/{id}")
    public ResponseEntity<Void> deleteVoice(@PathVariable Long id) {
        voiceRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** 從 OpenRouter 拉取可用 TTS 模型及其音色。 */
    @PostMapping("/voices/sync")
    public ResponseEntity<ApiModels.CountResult> syncVoices(@RequestBody Map<String, String> body) {
        String model = body.get("model");
        if (model == null || model.isBlank()) {
            model = "google/gemini-3.8-flash-tts";
        }
        AiProviderConfig provider = aiSettingsService.requireProvider();
        List<ModelInfo> models = openRouterClient.listModels(provider);
        int count = 0;
        for (ModelInfo info : models) {
            if (info.supported_voices() == null || info.supported_voices().isEmpty()) {
                continue;
            }
            for (String voiceId : info.supported_voices()) {
                if (voiceRepository.findAll().stream().anyMatch(v ->
                        v.getModel().equals(info.id()) && v.getVoiceId().equals(voiceId))) {
                    continue;
                }
                TtsVoice voice = new TtsVoice();
                voice.setProvider(provider.getProvider());
                voice.setModel(info.id());
                voice.setVoiceId(voiceId);
                voice.setDisplayName(voiceId);
                voice.setSortOrder(count);
                voiceRepository.save(voice);
                count++;
            }
        }
        return ResponseEntity.ok(new ApiModels.CountResult(count));
    }

    // ---------- general ----------

    @GetMapping("/general")
    public Forms.GeneralSettingsForm generalSettings() {
        return new Forms.GeneralSettingsForm(
                properties.getGlobalInstructions(),
                properties.getNarratorVoiceId(),
                properties.getDefaultVoiceId(),
                properties.isAutoAssignVoice(),
                properties.getChapterMaxChars(),
                properties.getTtsConcurrency(),
                properties.getTtsGroupMaxChars());
    }

    @PutMapping("/general")
    public Forms.GeneralSettingsForm updateGeneral(@RequestBody Forms.GeneralSettingsForm form) {
        if (form.globalInstructions() != null) {
            properties.setGlobalInstructions(form.globalInstructions());
        }
        if (form.narratorVoiceId() != null) {
            properties.setNarratorVoiceId(form.narratorVoiceId());
        }
        if (form.defaultVoiceId() != null) {
            properties.setDefaultVoiceId(form.defaultVoiceId());
        }
        if (form.autoAssignVoice() != null) {
            properties.setAutoAssignVoice(form.autoAssignVoice());
        }
        if (form.chapterMaxChars() != null) {
            properties.setChapterMaxChars(form.chapterMaxChars());
        }
        if (form.ttsConcurrency() != null) {
            properties.setTtsConcurrency(form.ttsConcurrency());
        }
        if (form.ttsGroupMaxChars() != null) {
            properties.setTtsGroupMaxChars(form.ttsGroupMaxChars());
        }
        return generalSettings();
    }

    // ---------- mapping ----------

    private ApiModels.ProviderDto toDto(AiProviderConfig c) {
        String plain = null;
        if (c.getApiKeyEncrypted() != null && cryptoService.isMasterKeyConfigured()) {
            try {
                plain = cryptoService.decrypt(c.getApiKeyEncrypted());
            } catch (Exception ignored) {
                // 解密失敗時不顯示
            }
        }
        return new ApiModels.ProviderDto(c.getId(), c.getProvider(), c.getName(), c.getBaseUrl(),
                cryptoService.maskKey(plain), plain != null && !plain.isBlank(),
                c.getExtraHeaders(), c.getTimeoutSeconds(), c.getEnabled(), c.getIsDefault());
    }

    private ApiModels.ModelSettingDto toDto(AiModelSetting s) {
        return new ApiModels.ModelSettingDto(s.getId(), s.getProviderConfig().getId(),
                s.getPurpose().name(), s.getModelId(),
                s.getTemperature() == null ? null : s.getTemperature().doubleValue(),
                s.getMaxTokens(), s.getParams(), s.getEnabled(), s.getIsDefault(),
                s.getFallbackModelId());
    }

    private ApiModels.VoiceDto toDto(TtsVoice v) {
        return new ApiModels.VoiceDto(v.getId(), v.getProvider(), v.getModel(), v.getVoiceId(),
                v.getDisplayName(), v.getGender(), v.getLanguage(), v.getStyleDesc(),
                v.getSampleUrl(), v.getEnabled(), v.getSortOrder());
    }
}
