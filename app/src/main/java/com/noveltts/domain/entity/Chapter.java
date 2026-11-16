package com.noveltts.domain.entity;

import com.noveltts.domain.enums.ChapterStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "chapter")
@Getter
@Setter
public class Chapter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "novel_id", nullable = false)
    private Novel novel;

    @Column(name = "chapter_index", nullable = false)
    private Integer chapterIndex;

    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "char_count", nullable = false)
    private Integer charCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ChapterStatus status = ChapterStatus.PENDING;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    /**
     * 顯示用標題（無標題時給預設值）。
     * 放進 entity 讓 Thymeleaf 不必在屬性表達式裡做 elvis 運算——
     * Thymeleaf 不允許在運算式內嵌 ${} 變數。
     */
    @Transient
    public String getDisplayTitle() {
        return (title == null || title.isBlank()) ? "（無標題）" : title;
    }
}
