package com.example.toilet.repository;

import com.example.toilet.domain.Toilet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ToiletRepository extends JpaRepository<Toilet, Long> {
    Optional<Toilet> findByExternalId(String externalId);
}
