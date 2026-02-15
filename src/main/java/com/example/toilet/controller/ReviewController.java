package com.example.toilet.controller;

import com.example.toilet.domain.Review;
import com.example.toilet.domain.Toilet;
import com.example.toilet.service.ReviewService;
import com.example.toilet.service.ToiletService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@AllArgsConstructor
@Slf4j
@Validated
public class ReviewController {
    private static final String SKIP_VIEW_COUNT_ONCE = "skipViewCountOnce";
    private static final int VIEW_COUNT_WINDOW_SECONDS = 180;
    private static final long VIEW_COUNT_WINDOW_MS = VIEW_COUNT_WINDOW_SECONDS * 1000L;
    private static final int VIEW_HISTORY_COOKIE_TTL_SECONDS = 600;
    private static final String VIEW_HISTORY_COOKIE_NAME = "review_view_history";
    private static final int VIEW_HISTORY_MAX_ENTRIES = 40;
    private static final int VIEW_HISTORY_COOKIE_MAX_CHARS = 2000;
    private static final int VIEW_RATE_LIMIT = 30;
    private static final long VIEW_RATE_LIMIT_WINDOW_MS = 60_000L;

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

    public static class UpdateForm extends ReviewForm {
        @NotNull
        private Long reviewId;
        public Long getReviewId() { return reviewId; }
        public void setReviewId(Long reviewId) { this.reviewId = reviewId; }
    }

    private final ReviewService reviewService;
    private final ToiletService toiletService;
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Seoul");

    private static final Set<String> BANNED_KEYWORDS = Set.of("욕설", "비속어", "광고", "불건전", "도배");
    private static final int SPAM_LIMIT = 3;
    private static final long SPAM_WINDOW_MS = 60_000L;
    private static final int REPORT_BLOCK_THRESHOLD = 10;
    private final Cache<String, Deque<Long>> rateLimitBuckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(20))
            .maximumSize(200_000)
            .build();
    private final Cache<String, Deque<Long>> viewRateLimitBuckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(20))
            .maximumSize(200_000)
            .build();

    private boolean hasBannedWord(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase();
        return BANNED_KEYWORDS.stream().anyMatch(k -> lower.contains(k.toLowerCase()));
    }

    private String clientKey(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) {
            int idx = ip.indexOf(',');
            if (idx > 0) ip = ip.substring(0, idx).trim();
        } else {
            ip = request.getRemoteAddr();
        }
        String ua = request.getHeader("User-Agent");
        return ip + "|" + (ua == null ? "" : ua);
    }

    private boolean isRateLimited(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> deque = rateLimitBuckets.get(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst() > SPAM_WINDOW_MS) {
                deque.pollFirst();
            }
            if (deque.size() >= SPAM_LIMIT) {
                return true;
            }
            deque.addLast(now);
            return false;
        }
    }

    private boolean isViewRateLimited(String clientKey, Long toiletId) {
        if (toiletId == null) {
            return false;
        }
        String key = clientKey + "|" + toiletId;
        long now = System.currentTimeMillis();
        Deque<Long> deque = viewRateLimitBuckets.get(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst() > VIEW_RATE_LIMIT_WINDOW_MS) {
                deque.pollFirst();
            }
            if (deque.size() >= VIEW_RATE_LIMIT) {
                return true;
            }
            deque.addLast(now);
            return false;
        }
    }

    @GetMapping("/reviews")
    public String reviews(@RequestParam("toiletId") Long toiletId,
                          HttpServletRequest request,
                          HttpServletResponse response,
                          Model model) {
        long startNanos = System.nanoTime();

        Toilet toilet = toiletService.findById(toiletId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid toiletId: " + toiletId));
        // FlashAttribute -> ModelAttribute로 1회 바인딩되는 스킵 플래그 사용
        Object skipOnce = request.getAttribute(SKIP_VIEW_COUNT_ONCE);
        boolean skipViewCount = (skipOnce instanceof Boolean) && (Boolean) skipOnce;
        boolean shouldIncreaseViewCount = !skipViewCount
                && !hasRecentViewCookie(request, toiletId)
                && !isViewRateLimited(clientKey(request), toiletId);
        long pageViewCount = shouldIncreaseViewCount
                ? reviewService.increaseReviewPageViewCount(toiletId)
                : reviewService.getReviewPageViewCount(toiletId);
        if (shouldIncreaseViewCount) {
            addRecentViewCookie(request, response, toiletId, request.isSecure());
        }

        Object error = model.asMap().get("errorMessage");
        if (error != null) model.addAttribute("errorMessage", error.toString());
        Object info = model.asMap().get("infoMessage");
        if (info != null) model.addAttribute("infoMessage", info.toString());

        double avg = reviewService.averageForToilet(toiletId);
        var raw = reviewService.findByToilet(toiletId);

        List<Map<String, Object>> list = raw.stream()
                .map(r -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", r.getId());
                    m.put("rating", r.getRating());
                    m.put("comment", r.getComment());
                    m.put("createdAt", r.getCreatedAt() != null
                            ? r.getCreatedAt().atOffset(ZoneOffset.UTC)
                                    .atZoneSameInstant(DISPLAY_ZONE)
                                    .format(FMT)
                            : "");
                    m.put("reportCount", r.getReportCount());
                    return m;
                })
                .collect(Collectors.toList());

        model.addAttribute("toilet", toilet);
        model.addAttribute("avgRating", avg);
        model.addAttribute("reviews", list);
        model.addAttribute("pageViewCount", pageViewCount);

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("마커→리뷰 이동 소요: {} ms (toiletId={}, 리뷰수={})",
                elapsedMs, toiletId, list.size());
        return "map/reviews";
    }

    @PostMapping("/reviews")
    public String create(@Valid ReviewForm form,
                         org.springframework.validation.BindingResult bindingResult,
                         RedirectAttributes redirectAttributes,
                         HttpServletRequest request) {

        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", "입력값을 확인해주세요. 별점은 1~5, 리뷰는 1000자 이내입니다.");
            return reviewsRedirectUrlWithoutCount(form.getToiletId(), redirectAttributes);
        }
        if (hasBannedWord(form.getComment())) {
            redirectAttributes.addFlashAttribute("errorMessage", "금지어가 포함된 리뷰는 등록할 수 없습니다.");
            return reviewsRedirectUrlWithoutCount(form.getToiletId(), redirectAttributes);
        }
        if (isRateLimited(clientKey(request))) {
            redirectAttributes.addFlashAttribute("errorMessage", "도배가 감지되었습니다. 잠시 후 다시 시도해주세요.");
            return reviewsRedirectUrlWithoutCount(form.getToiletId(), redirectAttributes);
        }

        Toilet toilet = toiletService.findById(form.getToiletId())
                .orElseThrow(() -> new IllegalArgumentException("Invalid toiletId: " + form.getToiletId()));

        Review r = new Review();
        r.setToilet(toilet);
        r.setRating(form.getRating());
        r.setComment(form.getComment());
        reviewService.save(r);

        redirectAttributes.addFlashAttribute("infoMessage", "리뷰가 등록되었습니다.");
        return reviewsRedirectUrlWithoutCount(form.getToiletId(), redirectAttributes);
    }

    @PostMapping("/reviews/update")
    public String update(@Valid UpdateForm form,
                         org.springframework.validation.BindingResult bindingResult,
                         RedirectAttributes redirectAttributes,
                         HttpServletRequest request) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", "입력값을 확인해주세요. 별점은 1~5, 리뷰는 1000자 이내입니다.");
            return reviewsRedirectUrlWithoutCount(form.getToiletId(), redirectAttributes);
        }
        if (hasBannedWord(form.getComment())) {
            redirectAttributes.addFlashAttribute("errorMessage", "금지어가 포함된 리뷰는 수정할 수 없습니다.");
            return reviewsRedirectUrlWithoutCount(form.getToiletId(), redirectAttributes);
        }
        if (isRateLimited(clientKey(request))) {
            redirectAttributes.addFlashAttribute("errorMessage", "도배가 감지되었습니다. 잠시 후 다시 시도해주세요.");
            return reviewsRedirectUrlWithoutCount(form.getToiletId(), redirectAttributes);
        }

        reviewService.update(form.getReviewId(), form.getRating(), form.getComment());
        redirectAttributes.addFlashAttribute("infoMessage", "리뷰가 수정되었습니다.");
        return reviewsRedirectUrlWithoutCount(form.getToiletId(), redirectAttributes);
    }

    @PostMapping("/reviews/delete")
    public String delete(@RequestParam("reviewId") Long reviewId,
                         @RequestParam("toiletId") Long toiletId,
                         RedirectAttributes redirectAttributes) {
        reviewService.delete(reviewId);
        redirectAttributes.addFlashAttribute("infoMessage", "리뷰가 삭제되었습니다.");
        return reviewsRedirectUrlWithoutCount(toiletId, redirectAttributes);
    }

    @PostMapping("/reviews/report")
    public String report(@RequestParam("reviewId") Long reviewId,
                         @RequestParam("toiletId") Long toiletId,
                         RedirectAttributes redirectAttributes) {
        try {
            boolean deleted = reviewService.report(reviewId, REPORT_BLOCK_THRESHOLD);
            if (deleted) {
                redirectAttributes.addFlashAttribute("infoMessage", "신고 임계치 초과로 리뷰가 삭제되었습니다.");
            } else {
                redirectAttributes.addFlashAttribute("infoMessage", "신고가 접수되었습니다.");
            }
        } catch (Exception e) {
            log.warn("리뷰 신고 실패 reviewId={}", reviewId, e);
            redirectAttributes.addFlashAttribute("errorMessage", "신고 처리 중 오류가 발생했습니다.");
        }
        return reviewsRedirectUrlWithoutCount(toiletId, redirectAttributes);
    }

    private String reviewsRedirectUrlWithoutCount(Long toiletId, RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute(SKIP_VIEW_COUNT_ONCE, true);
        return "redirect:/reviews?toiletId=" + toiletId;
    }

    private boolean hasRecentViewCookie(HttpServletRequest request, Long toiletId) {
        if (toiletId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Map<Long, Long> history = readViewHistory(request, now);
        Long seenAt = history.get(toiletId);
        return seenAt != null && now - seenAt <= VIEW_COUNT_WINDOW_MS;
    }

    private void addRecentViewCookie(HttpServletRequest request, HttpServletResponse response, Long toiletId, boolean secure) {
        if (toiletId == null) return;
        long now = System.currentTimeMillis();
        Map<Long, Long> history = readViewHistory(request, now);
        history.put(toiletId, now);

        List<Map.Entry<Long, Long>> entries = new ArrayList<>(history.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        StringBuilder value = new StringBuilder();
        int count = 0;
        for (Map.Entry<Long, Long> e : entries) {
            if (count >= VIEW_HISTORY_MAX_ENTRIES) break;
            if (value.length() > 0) value.append('_');
            value.append(e.getKey()).append('.').append(e.getValue());
            count++;
        }
        if (value.length() > VIEW_HISTORY_COOKIE_MAX_CHARS) {
            value.setLength(0);
            value.append(toiletId).append('.').append(now);
        }

        Cookie cookie = new Cookie(VIEW_HISTORY_COOKIE_NAME, value.toString());
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setPath("/");
        cookie.setMaxAge(VIEW_HISTORY_COOKIE_TTL_SECONDS);
        response.addCookie(cookie);
    }

    private Map<Long, Long> readViewHistory(HttpServletRequest request, long now) {
        Map<Long, Long> result = new HashMap<>();
        Cookie[] cookies = request == null ? null : request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return result;
        }
        String raw = null;
        for (Cookie cookie : cookies) {
            if (VIEW_HISTORY_COOKIE_NAME.equals(cookie.getName())) {
                raw = cookie.getValue();
                break;
            }
        }
        if (raw == null || raw.isBlank()) {
            return result;
        }
        String[] tokens = raw.split("_");
        for (String token : tokens) {
            if (token == null || token.isBlank()) continue;
            int dot = token.indexOf('.');
            if (dot <= 0 || dot >= token.length() - 1) continue;
            try {
                long id = Long.parseLong(token.substring(0, dot));
                long ts = Long.parseLong(token.substring(dot + 1));
                if (now - ts <= VIEW_COUNT_WINDOW_MS) {
                    result.put(id, Math.max(ts, result.getOrDefault(id, 0L)));
                }
            } catch (NumberFormatException ignored) {
                // skip malformed entries
            }
        }
        return result;
    }
}
