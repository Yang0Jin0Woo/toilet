package com.example.toilet.repository;

import com.example.toilet.domain.Toilet;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToiletRepository extends JpaRepository<Toilet, Long> {
}
