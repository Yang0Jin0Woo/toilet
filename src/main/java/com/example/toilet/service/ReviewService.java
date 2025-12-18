package com.example.toilet.service;

import com.example.toilet.domain.Review;
import com.example.toilet.repository.ReviewRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@AllArgsConstructor
@Transactional(readOnly = true)
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ToiletService toiletService;

    public List<Review> findByToilet(Long toiletId) {
        return reviewRepository.findActiveByToiletIdOrderByIdDesc(toiletId);
    }

    public double averageForToilet(Long toiletId) {
        var list = reviewRepository.findActiveByToiletIdOrderByIdDesc(toiletId);
        return list.stream().mapToInt(Review::getRating).average().orElse(0.0);
    }

    @Transactional
    public Review save(Review r) {
        Review saved = reviewRepository.save(r);
        // 캐시 업데이트 → 집계 쿼리 재실행 방지
        toiletService.applyReviewDelta(saved.getToilet().getId(), saved.getRating());
        return saved;
    }

    /**
     * 리뷰 평점/내용을 수정하고,
     * 캐시된 집계 정보와의 일관성을 유지한다.
     */
    @Transactional
    public Review update(Long reviewId, int newRating, String newComment) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid reviewId: " + reviewId));
        int oldRating = review.getRating();
        review.setRating(newRating);
        review.setComment(newComment);
        Review saved = reviewRepository.save(review);
        toiletService.applyReviewUpdate(review.getToilet().getId(), oldRating, newRating);
        return saved;
    }

    /**
     * 리뷰를 삭제하고,
     * 캐시에 저장된 합계 및 개수 정보를 감소시킨다.
     */
    @Transactional
    public void delete(Long reviewId) {
        reviewRepository.findById(reviewId).ifPresent(r -> {
            reviewRepository.delete(r);
            toiletService.applyReviewDelta(r.getToilet().getId(), -r.getRating(), -1);
        });
    }

    /**
     * 신고 수를 증가시켜 임계치 도달 시 차단한다.
     */
    @Transactional
    public boolean report(Long reviewId, int blockThreshold) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid reviewId: " + reviewId));

        int next = (review.getReportCount() == null ? 0 : review.getReportCount()) + 1;
        if (next >= blockThreshold) {
            // 임계치 초과 시 리뷰를 삭제하고 캐시를 O(1)로 갱신
            toiletService.applyReviewDelta(review.getToilet().getId(), -review.getRating(), -1);
            reviewRepository.delete(review);
            return true; // deleted
        } else {
            review.setReportCount(next);
            reviewRepository.save(review);
            return false; // not deleted
        }
    }
}
