package com.example.toilet.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
public class Review {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "toilet_id")
    private Toilet toilet;

    @Min(1) @Max(5)
    @Column(nullable = false)
    private Integer rating;           // 1~5
    @Column(length = 1000)
    @Size(max = 1000)
    private String comment;

    @Column(name = "report_count", nullable = false)
    private Integer reportCount = 0;

    @Column(name = "blocked", nullable = false)
    private Boolean blocked = false;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
        if (reportCount == null) reportCount = 0;
        if (blocked == null) blocked = false;
    }

    @PostLoad
    public void postLoad() {
        if (reportCount == null) reportCount = 0;
        if (blocked == null) blocked = false;
    }
}
