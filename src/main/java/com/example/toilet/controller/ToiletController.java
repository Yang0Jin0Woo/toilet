package com.example.toilet.controller;

import com.example.toilet.domain.Toilet;
import com.example.toilet.service.ToiletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ToiletController {

    private final ToiletService toiletService;

    @GetMapping("/toilets")
    public ResponseEntity<List<Toilet>> getToilets(
            @RequestParam(name = "withRatings", defaultValue = "true") boolean withRatings) {

        List<Toilet> toilets = withRatings
                ? toiletService.findAllWithRatings()
                : toiletService.getAllToilets();

        if (!withRatings) {
            log.info("Toilet list retrieved (withRatings={}): {} items", withRatings, toilets.size());
        }
        return ResponseEntity.ok(toilets);
    }
}
