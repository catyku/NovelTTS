package com.noveltts.domain.repository;

import com.noveltts.domain.entity.AiModelSetting;
import com.noveltts.domain.enums.ModelPurpose;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiModelSettingRepository extends JpaRepository<AiModelSetting, Long> {

    List<AiModelSetting> findByPurposeOrderByIdAsc(ModelPurpose purpose);

    Optional<AiModelSetting> findFirstByPurposeAndEnabledTrueAndIsDefaultTrueOrderByIdAsc(ModelPurpose purpose);

    Optional<AiModelSetting> findFirstByPurposeAndEnabledTrueOrderByIdAsc(ModelPurpose purpose);

    Optional<AiModelSetting> findByProviderConfigIdAndPurposeAndModelId(Long providerConfigId,
                                                                       ModelPurpose purpose,
                                                                       String modelId);
}
