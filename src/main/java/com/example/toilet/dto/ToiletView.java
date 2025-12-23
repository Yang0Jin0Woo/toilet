package com.example.toilet.dto;

public record ToiletView(
        Long id,
        String contsName,
        String addrNew,
        String addrOld,
        Double coordX,
        Double coordY,
        String value04,
        String value05,
        double avgRating,
        long reviewCount
) {}
