package com.noveltts.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * OpenRouter 模型定價快取。
 *
 * 價格單位為 USD / token。TTS 模型在部分供應商以「每字元」計價，
 * 由 {@code pricingUnit} 區分。
 */
@Entity
@Table(name = "model_pricing")
@Getter
@Setter
public class ModelPricing {

    @Id
    @Column(name = "model_id", length = 128)
    private String modelId;

    @Column(name = "display_name", length = 255)
    private String displayName;

    /** 每 token 輸入價格（USD）。 */
    @Column(name = "prompt_price", nullable = false, precision = 20, scale = 12)
    private BigDecimal promptPrice = BigDecimal.ZERO;

    /** 每 token 輸出價格（USD）。 */
    @Column(name = "completion_price", nullable = false, precision = 20, scale = 12)
    private BigDecimal completionPrice = BigDecimal.ZERO;

    /** 每 token 音訊價格（USD），TTS 模型使用。 */
    @Column(name = "audio_price", precision = 20, scale = 12)
    private BigDecimal audioPrice;

    /** 計價單位：TOKEN / CHARACTER / SECOND。 */
    @Column(name = "pricing_unit", nullable = false, length = 16)
    private String pricingUnit = "TOKEN";

    /** 是否為語音輸出模型。 */
    @Column(name = "supports_speech", nullable = false)
    private Boolean supportsSpeech = false;

    @Column(name = "synced_at", nullable = false)
    private OffsetDateTime syncedAt = OffsetDateTime.now();

    @PreUpdate
    void touch() {
        this.syncedAt = OffsetDateTime.now();
    }
}
