package com.example.toilet.controller;

import com.example.toilet.domain.Review;
import com.example.toilet.domain.Toilet;
import com.example.toilet.service.ReviewService;
import com.example.toilet.service.ToiletService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class ReviewController {

    private final ReviewService reviewService;
    private final ToiletService toiletService;
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public ReviewController(ReviewService reviewService, ToiletService toiletService) {
        this.reviewService = reviewService;
        this.toiletService = toiletService;
    }

    /** 특정 화장실 리뷰 목록/등록 화면 */
    @GetMapping("/reviews")
    public String reviews(@RequestParam("toiletId") Long toiletId, Model model) {
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
        return "map/reviews";
    }

    /** 리뷰 저장 후 해당 화장실 리뷰 목록으로 이동 */
    @PostMapping("/reviews")
    public String create(@RequestParam("toiletId") Long toiletId,
                         @RequestParam("rating") Integer rating,
                         @RequestParam(value = "comment", required = false) String comment) {

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
