package com.example.toilet.repository;

import com.example.toilet.domain.Toilet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
    }

    @Query("""
           select t.id as id,
                  t.contsName as contsName,
                  t.addrNew as addrNew,
                  t.addrOld as addrOld,
                  t.coordX as coordX,
                  t.coordY as coordY,
                  t.value04 as value04,
                  t.value05 as value05
           from Toilet t
           """)
    List<ToiletSnapshotProjection> findAllSnapshots();
}



