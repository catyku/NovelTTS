package com.noveltts.domain.entity;

import com.noveltts.domain.enums.AudioKind;
import com.noveltts.domain.enums.AudioStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "audio_file")
@Getter
@Setter
public class AudioFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "novel_id", nullable = false)
    private Novel novel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chapter_id")
    private Chapter chapter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "segment_id")
    private DialogueSegment segment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AudioKind kind = AudioKind.SEGMENT;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(nullable = false)
    private String format = "mp3";

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "byte_size")
    private Long byteSize;

    private String provider;

    private String model;

    @Column(name = "voice_id")
    private String voiceId;

    @Column(name = "generation_id")
    private String generationId;

    @Column(name = "char_count")
    private Integer charCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AudioStatus status = AudioStatus.READY;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
