package com.example.toilet.repository;

import com.example.toilet.domain.Toilet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ToiletRepository extends JpaRepository<Toilet, Long> {
    Optional<Toilet> findByExternalId(String externalId);

    interface ToiletSnapshotProjection {
        Long getId();
        String getContsName();
        String getAddrNew();
        String getAddrOld();
        Double getCoordX();
        Double getCoordY();
        String getValue04();
        String getValue05();
        Long getRatingSum();
        Long getRatingCount();
    }

    @Query("""
           select t.id as id,
                  t.contsName as contsName,
                  t.addrNew as addrNew,
                  t.addrOld as addrOld,
                  t.coordX as coordX,
                  t.coordY as coordY,
                  t.value04 as value04,
                  t.value05 as value05,
                  t.ratingSum as ratingSum,
                  t.ratingCount as ratingCount
           from Toilet t
           """)
    List<ToiletSnapshotProjection> findAllSnapshots();

    @Modifying
    @Query("""
           update Toilet t
           set t.ratingSum = coalesce(t.ratingSum, 0) + :deltaSum,
               t.ratingCount = coalesce(t.ratingCount, 0) + :deltaCount
           where t.id = :toiletId
           """)
    int applyRatingDelta(@Param("toiletId") Long toiletId,
                         @Param("deltaSum") long deltaSum,
                         @Param("deltaCount") long deltaCount);
}



