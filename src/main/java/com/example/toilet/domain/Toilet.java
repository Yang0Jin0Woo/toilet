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

    private String contsName;
    private String addrNew;
    private String addrOld;

    @Column(name = "coord_x")
    private Double coordX;
    @Column(name = "coord_y")
    private Double coordY;

    private String value04;
    private String value05;

    @Column(nullable = false)
    private Long ratingSum = 0L;

    @Column(nullable = false)
    private Long ratingCount = 0L;

    @Version
    private Long version;

    @Transient
    private Double avgRating;
    @Transient
    private Long reviewCount;

    @Column(name = "external_id", unique = true)
    private String externalId;

    @Transient
    public String getMarkerType() {
        final String v4 = value04 == null ? "" : value04;
        final String v5 = value05 == null ? "" : value05;

        // 장애인 전용 여부
        if (v4.contains("장애인") || v5.contains("장애인")) {
            return "disabled";
        }

        // 남/여 전용 여부
        final boolean hasM = v4.contains("남") || v5.contains("남");
        final boolean hasF = v4.contains("여") || v5.contains("여");
        if (hasM && !hasF) return "male";
        if (!hasM && hasF) return "female";

        // 기본값
        return "unknown";
    }

    @Transient
    public double getAvgRatingComputed() {
        long cnt = ratingCount == null ? 0L : ratingCount;
        long sum = ratingSum == null ? 0L : ratingSum;
        return cnt <= 0 ? 0.0 : (double) sum / (double) cnt;
    }
}
