package com.noveltts.domain.entity;

import com.noveltts.domain.enums.JobStatus;
import com.noveltts.domain.enums.JobType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "job_task")
@Getter
@Setter
public class JobTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private JobType type;

    @Column(name = "ref_id")
    private Long refId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JobStatus status = JobStatus.PENDING;

    @Column(nullable = false)
    private Integer progress = 0;

    @Column(nullable = false)
    private Integer total = 0;

    @Column(columnDefinition = "text")
    private String message;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    /**
     * 完成百分比（0-100）。放進 entity 讓 Thymeleaf 不必在屬性表達式裡做三元運算——
     * Thymeleaf 不允許在運算式內嵌 ${} 變數。
     */
    @Transient
    public int getPercent() {
        if (total == null || total <= 0) {
            return 0;
        }
        return (int) Math.round(progress * 100.0 / total);
    }
}
