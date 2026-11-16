package com.noveltts.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "tts_voice")
@Getter
@Setter
public class TtsVoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String provider = "openrouter";

    @Column(nullable = false)
    private String model;

    @Column(name = "voice_id", nullable = false)
    private String voiceId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    private String gender;

    private String language;

    @Column(name = "style_desc", columnDefinition = "text")
    private String styleDesc;

    @Column(name = "sample_url")
    private String sampleUrl;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now();
    }
}
