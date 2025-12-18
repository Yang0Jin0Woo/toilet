package com.example.toilet.controller;

import com.example.toilet.domain.Review;
import com.example.toilet.domain.Toilet;
import com.example.toilet.service.ReviewService;
import com.example.toilet.service.ToiletService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@AllArgsConstructor
@Slf4j
@Validated
public class ReviewController {

    private final ReviewService reviewService;
    private final ToiletService toiletService;
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @GetMapping("/reviews")
    public String reviews(@RequestParam("toiletId") Long toiletId, Model model) {
        long startNanos = System.nanoTime();

        Toilet toilet = toiletService.findById(toiletId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid toiletId: " + toiletId));

        double avg = reviewService.averageForToilet(toiletId);
        var raw = reviewService.findByToilet(toiletId);

        List<Map<String, Object>> list = raw.stream()
                .map(r -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("rating", r.getRating());
                    m.put("comment", r.getComment());
                    m.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().format(FMT) : "");
                    return m;
                })
                .collect(Collectors.toList());

        model.addAttribute("toilet", toilet);
        model.addAttribute("avgRating", avg);
        model.addAttribute("reviews", list);

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("Marker->reviews navigation finished: {} ms (toiletId={}, reviews={})",
                elapsedMs, toiletId, list.size());
        return "map/reviews";
    }

    @PostMapping("/reviews")
    public String create(@RequestParam("toiletId") Long toiletId,
                         @RequestParam("rating") @Min(1) @Max(5) Integer rating,
                         @RequestParam(value = "comment", required = false) @Size(max = 1000) String comment) {

        Toilet toilet = toiletService.findById(toiletId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid toiletId: " + toiletId));

        Review r = new Review();
        r.setToilet(toilet);
        r.setRating(rating);
        r.setComment(comment);
        reviewService.save(r);

        return "redirect:/reviews?toiletId=" + toiletId;
    }
}
