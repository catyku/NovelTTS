package com.noveltts.domain.entity;

import com.noveltts.domain.enums.SegmentType;
import com.noveltts.domain.enums.TtsStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "dialogue_segment")
@Getter
@Setter
public class DialogueSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chapter_id", nullable = false)
    private Chapter chapter;

    @Column(nullable = false)
    private Integer seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SegmentType type = SegmentType.NARRATION;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id")
    private NovelCharacter character;

    @Column(name = "speaker_name")
    private String speakerName;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    private String emotion;

    @Column(name = "style_hint")
    private String styleHint;

    @Column(columnDefinition = "text")
    private String instructions;

    @Column(name = "voice_id")
    private String voiceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tts_status", nullable = false, length = 16)
    private TtsStatus ttsStatus = TtsStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "audio_id")
    private AudioFile audio;

    @Column(name = "char_count", nullable = false)
    private Integer charCount = 0;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now();
    }
}
