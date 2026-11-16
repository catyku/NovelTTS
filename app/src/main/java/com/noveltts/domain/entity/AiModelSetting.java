package com.noveltts.domain.entity;

import com.noveltts.domain.enums.ModelPurpose;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "ai_model_setting")
@Getter
@Setter
public class AiModelSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_config_id", nullable = false)
    private AiProviderConfig providerConfig;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ModelPurpose purpose;

    @Column(name = "model_id", nullable = false)
    private String modelId;

    /**
     * 備援模型 id。
     *
     * <p>主要模型呼叫失敗（429/5xx）時改用此模型重試。留空表示不啟用備援。
     */
    @Column(name = "fallback_model_id", length = 128)
    private String fallbackModelId;

    @Column(precision = 3, scale = 2)
    private BigDecimal temperature;

    @Column(name = "max_tokens")
    private Integer maxTokens;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> params = new HashMap<>();

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now();
    }
}
