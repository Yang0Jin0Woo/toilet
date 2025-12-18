package com.example.toilet.controller;

import com.example.toilet.domain.Review;
import com.example.toilet.domain.Toilet;
import com.example.toilet.service.ReviewService;
import com.example.toilet.service.ToiletService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

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

    public static class ReviewForm {
        @NotNull
        private Long toiletId;
        @NotNull @Min(1) @Max(5)
        private Integer rating;
        @Size(max = 1000)
        private String comment;

        public Long getToiletId() { return toiletId; }
        public void setToiletId(Long toiletId) { this.toiletId = toiletId; }
        public Integer getRating() { return rating; }
        public void setRating(Integer rating) { this.rating = rating; }
        public String getComment() { return comment; }
        public void setComment(String comment) { this.comment = comment; }
    }

    private final ReviewService reviewService;
    private final ToiletService toiletService;
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @GetMapping("/reviews")
    public String reviews(@RequestParam("toiletId") Long toiletId, Model model) {
        long startNanos = System.nanoTime();

        Toilet toilet = toiletService.findById(toiletId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid toiletId: " + toiletId));

        // 전달된 에러 메시지가 있으면 표시
        Object error = model.asMap().get("errorMessage");
        if (error != null) {
            model.addAttribute("errorMessage", error.toString());
        }

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
        log.info("마커→리뷰 이동 완료: {} ms (toiletId={}, 리뷰수={})",
                elapsedMs, toiletId, list.size());
        return "map/reviews";
    }

    @PostMapping("/reviews")
    public String create(@Valid ReviewForm form,
                         org.springframework.validation.BindingResult bindingResult,
                         RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", "입력값을 확인해주세요. 별점은 1~5, 리뷰는 1000자 이내입니다.");
            return "redirect:/reviews?toiletId=" + form.getToiletId();
        }

        Toilet toilet = toiletService.findById(form.getToiletId())
                .orElseThrow(() -> new IllegalArgumentException("Invalid toiletId: " + form.getToiletId()));

        Review r = new Review();
        r.setToilet(toilet);
        r.setRating(form.getRating());
        r.setComment(form.getComment());
        reviewService.save(r);

        return "redirect:/reviews?toiletId=" + form.getToiletId();
    }
}
