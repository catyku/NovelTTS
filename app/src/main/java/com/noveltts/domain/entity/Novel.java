package com.noveltts.domain.entity;

import com.noveltts.domain.enums.NovelStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "novel")
@Getter
@Setter
public class Novel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    private String author;

    @Column(name = "source_filename")
    private String sourceFilename;

    @Column(name = "source_text", columnDefinition = "text")
    private String sourceText;

    @Column(nullable = false)
    private String language = "zh-TW";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private NovelStatus status = NovelStatus.PENDING;

    @Column(name = "total_chapters", nullable = false)
    private Integer totalChapters = 0;

    @Column(name = "total_chars", nullable = false)
    private Integer totalChars = 0;

    /** 所屬群組；null 表示獨立小說。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private NovelGroup group;

    /** 群組內的集數順序（第一集、第二集…）。 */
    @Column(name = "group_order")
    private Integer groupOrder;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now();
    }
}
