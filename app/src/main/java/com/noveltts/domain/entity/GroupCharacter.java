package com.noveltts.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 群組共用角色。
 *
 * 同一本連載小說的不同集會出現相同角色，此處集中維護其音色與語氣，
 * 讓各集分析時自動套用，達成跨集音色一致。
 */
@Entity
@Table(name = "group_character")
@Getter
@Setter
public class GroupCharacter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private NovelGroup group;

    @Column(nullable = false)
    private String name;

    /** 別名：比對各集分析出的角色名時一併使用。 */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] aliases = new String[0];

    private String gender;

    @Column(name = "age_group")
    private String ageGroup;

    @Column(columnDefinition = "text")
    private String personality;

    @Column(columnDefinition = "text")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tts_voice_id")
    private TtsVoice ttsVoice;

    @Column(name = "tts_instructions", columnDefinition = "text")
    private String ttsInstructions;

    @Column(name = "color_hex")
    private String colorHex = "#4C6EF5";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now();
    }
}
