package com.noveltts.service;

import com.noveltts.domain.entity.AiModelSetting;
import com.noveltts.domain.entity.AiProviderConfig;
import com.noveltts.domain.enums.ModelPurpose;
import com.noveltts.domain.repository.AiModelSettingRepository;
import com.noveltts.domain.repository.AiProviderConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AiSettingsService {

    private final AiProviderConfigRepository providerRepository;
    private final AiModelSettingRepository modelRepository;

    public AiSettingsService(AiProviderConfigRepository providerRepository,
                              AiModelSettingRepository modelRepository) {
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
    }

    @Transactional(readOnly = true)
    public AiProviderConfig requireProvider() {
        return providerRepository.findFirstByEnabledTrueAndIsDefaultTrueOrderByIdAsc()
                .or(() -> providerRepository.findFirstByEnabledTrueOrderByIdAsc())
                .orElseThrow(() -> new IllegalStateException(
                        "尚未設定 AI Provider，請先到設定頁 (/settings) 填寫 OpenRouter API Key"));
    }

    @Transactional(readOnly = true)
    public AiModelSetting requireModel(ModelPurpose purpose) {
        AiModelSetting setting = modelRepository
                .findFirstByPurposeAndEnabledTrueAndIsDefaultTrueOrderByIdAsc(purpose)
                .or(() -> modelRepository.findFirstByPurposeAndEnabledTrueOrderByIdAsc(purpose))
                .orElseThrow(() -> new IllegalStateException(
                        "尚未設定 " + purpose + " 模型，請先到設定頁 (/settings) 選擇模型"));
        return setting;
    }

    @Transactional(readOnly = true)
    public List<AiProviderConfig> listProviders() {
        return providerRepository.findAllByOrderByIdAsc();
    }

    @Transactional(readOnly = true)
    public List<AiModelSetting> listModels(ModelPurpose purpose) {
        return modelRepository.findByPurposeOrderByIdAsc(purpose);
    }
}
