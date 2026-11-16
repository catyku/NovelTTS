package com.noveltts.domain.repository;

import com.noveltts.domain.entity.ModelPricing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ModelPricingRepository extends JpaRepository<ModelPricing, String> {

    Optional<ModelPricing> findByModelId(String modelId);
}
