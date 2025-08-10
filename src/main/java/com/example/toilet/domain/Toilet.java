package com.example.toilet.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class Toilet {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 마커 기본 정보
    private String contsName;
    private String addrNew;
    private String addrOld;

    // 좌표
    @Column(name = "coord_x")
    private Double coordX;
    @Column(name = "coord_y")
    private Double coordY;

    private String value04;         // 남/녀화장실 현황
    private String value05;         // 장애인화장실 현황

    @Transient
    private Double avgRating;     // 평균 별점 (조회용)
    @Transient
    private Long reviewCount;     // 리뷰 수 (조회용)

    @Transient
    public String getMarkerType() {
        final String v4 = value04 == null ? "" : value04;
        final String v5 = value05 == null ? "" : value05;

        // 장애인
        if (v4.contains("장애") || v5.contains("장애")) return "disabled";

        // 단일 성별 - 색 분기
        final boolean hasM = v4.contains("남");
        final boolean hasF = v4.contains("여");
        if (hasM && !hasF) return "male";
        if (!hasM && hasF) return "female";

        // 3) 혼합/불명 - 일반
        return "unknown";
    }
}
