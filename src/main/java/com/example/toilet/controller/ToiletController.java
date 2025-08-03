package com.example.toilet.controller;

import com.example.toilet.domain.Toilet;
import com.example.toilet.service.ToiletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ToiletController {

    private final ToiletService toiletService;

    @GetMapping("/toilets")
    public ResponseEntity<List<Toilet>> getAllToilets() {
        List<Toilet> toilets = toiletService.getAllToilets();
        log.info("🔍 ToiletController.getAllToilets() 호출 → 조회된 개수 = {}", toilets.size());
        return ResponseEntity.ok(toilets);
    }
}
