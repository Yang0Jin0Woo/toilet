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
    private Integer rating;           // 1~5
    @Column(length = 1000)
    @Size(max = 1000)
    private String comment;

    /**
     * 신고 누적 카운트. 임계치 도달 시 blocked=true로 전환한다.
     */
    @Column(nullable = false, columnDefinition = "int default 0")
    private Integer reportCount = 0;

    /**
     * 차단된 리뷰는 평균 집계와 목록에서 제외된다.
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private Boolean blocked = false;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
        if (reportCount == null) reportCount = 0;
        if (blocked == null) blocked = false;
    }

    @PostLoad
    public void postLoad() {
        if (reportCount == null) reportCount = 0;
        if (blocked == null) blocked = false;
    }
}
