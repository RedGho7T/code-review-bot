package com.groviate.telegramcodereviewbot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(
        name = "mr_review_status",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_mr_review_status_project_mr",
                columnNames = {"project_id", "mr_iid"}
        )
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Integer projectId;

    @Column(name = "mr_iid", nullable = false)
    private Integer mrIid;

    @Column(name = "head_sha", nullable = false, length = 64)
    private String headSha;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReviewState status;

    @Column(name = "attempts", nullable = false)
    private Integer attempts;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (attempts == null) attempts = 0;
        if (status == null) status = ReviewState.PENDING;
        if (version == null) version = 0L;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public enum ReviewState {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED
    }
}