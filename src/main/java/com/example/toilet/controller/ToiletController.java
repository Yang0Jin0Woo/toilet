package com.example.toilet.controller;

import com.example.toilet.dto.ToiletView;
import com.example.toilet.service.RatingSseService;
import com.example.toilet.service.ToiletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ToiletController {

    private final ToiletService toiletService;
    private final RatingSseService ratingSseService;

    @GetMapping("/toilets")
    public ResponseEntity<List<ToiletView>> getToilets(
            @RequestParam(name = "withRatings", defaultValue = "true") boolean withRatings) {

        long startNanos = System.nanoTime();
        List<ToiletView> toilets = toiletService.getAllToiletViews(withRatings);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        if (!withRatings) {
            log.info("Toilet list retrieved (withRatings={}): {} items", withRatings, toilets.size());
        }
        log.info("GET /toilets withRatings={} -> {} items in {} ms", withRatings, toilets.size(), elapsedMs);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Total-Ms", Long.toString(elapsedMs));
        if (withRatings) {
            Long aggMs = toiletService.consumeLastAggMs();
            if (aggMs != null) {
                headers.add("X-Agg-Ms", Long.toString(aggMs));
            }
        }
        return new ResponseEntity<>(toilets, headers, HttpStatus.OK);
    }

    @GetMapping(value = "/sse/ratings", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ratingsStream(@RequestParam(name = "toiletId", required = false) Long toiletId) {
        return ratingSseService.subscribe(toiletId);
    }
}
