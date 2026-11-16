package com.noveltts.domain.repository;

import com.noveltts.domain.entity.AiProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiProviderConfigRepository extends JpaRepository<AiProviderConfig, Long> {

    List<AiProviderConfig> findAllByOrderByIdAsc();

    Optional<AiProviderConfig> findFirstByEnabledTrueAndIsDefaultTrueOrderByIdAsc();

    Optional<AiProviderConfig> findFirstByEnabledTrueOrderByIdAsc();
}
