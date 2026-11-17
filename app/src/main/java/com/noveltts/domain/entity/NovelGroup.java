package com.noveltts.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 小說群組：同一本連載小說的多集共用設定。
 *
 * 群組層級維護共用角色（{@link GroupCharacter}）與旁白音色，
 * 各集（{@link Novel}）分析時會比對並套用，確保跨集音色一致。
 */
@Entity
@Table(name = "novel_group")
@Getter
@Setter
public class NovelGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    private String author;

    @Column(columnDefinition = "text")
    private String description;

    /** 群組共用旁白音色：所有集的旁白都用同一個音色。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "narrator_voice_id")
    private TtsVoice narratorVoice;

    /** 群組共用旁白語氣（傳給 TTS 的 instructions）。 */
    @Column(name = "narrator_instructions", columnDefinition = "text")
    private String narratorInstructions;

    /** 單集 TTS 分組字數上限；null 表示用全域預設。 */
    @Column(name = "tts_group_max_chars")
    private Integer ttsGroupMaxChars;

    @Column(nullable = false, length = 24)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now();
    }
}
