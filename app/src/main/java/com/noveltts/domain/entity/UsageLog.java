package com.noveltts.domain.entity;

import com.noveltts.domain.enums.UsageKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "usage_log")
@Getter
@Setter
public class UsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String provider;

    private String model;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UsageKind kind = UsageKind.TEXT;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "char_count")
    private Integer charCount;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "estimated_cost", precision = 12, scale = 6)
    private BigDecimal estimatedCost;

    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
