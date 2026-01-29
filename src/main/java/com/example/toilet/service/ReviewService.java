package com.example.toilet.service;

import com.example.toilet.domain.Review;
import com.example.toilet.repository.ReviewRepository;
import com.example.toilet.repository.ToiletRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@AllArgsConstructor
@Transactional(readOnly = true)
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ToiletRepository toiletRepository;
    private final ToiletService toiletService;

    public List<Review> findByToilet(Long toiletId) {
        return reviewRepository.findActiveByToiletIdOrderByIdDesc(toiletId);
    }

    public double averageForToilet(Long toiletId) {
        return toiletRepository.findById(toiletId)
                .map(t -> t.getAvgRatingComputed())
                .orElse(0.0);
    }

    @Transactional
    public Review save(Review r) {
        if (r == null || r.getToilet() == null || r.getToilet().getId() == null) {
            throw new IllegalArgumentException("toiletId is required");
        }
        if (r.getRating() == null) {
            throw new IllegalArgumentException("rating is required");
        }
        Long toiletId = r.getToilet().getId();
        Review saved = reviewRepository.save(r);
        int updated = toiletRepository.applyRatingDelta(
                toiletId,
                saved.getRating(),
                1L
        );
        if (updated == 0) {
            throw new IllegalStateException("Failed to update toilet rating aggregate: " + toiletId);
        }
        toiletService.evictListCache();
        return saved;
    }


    @Transactional
    public Review update(Long reviewId, int newRating, String newComment) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid reviewId: " + reviewId));
        Long toiletId = review.getToilet() == null ? null : review.getToilet().getId();
        if (toiletId == null) {
            throw new IllegalStateException("toiletId is required for reviewId: " + reviewId);
        }
        int oldRating = review.getRating() == null ? 0 : review.getRating();
        review.setRating(newRating);
        review.setComment(newComment);
        Review saved = reviewRepository.save(review);
        long delta = (long) newRating - (long) oldRating;
        if (delta != 0) {
            int updated = toiletRepository.applyRatingDelta(
                    toiletId,
                    delta,
                    0L
            );
            if (updated == 0) {
                throw new IllegalStateException("Failed to update toilet rating aggregate: " + toiletId);
            }
        }
        toiletService.evictListCache();
        return saved;
    }

    @Transactional
    public void delete(Long reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid reviewId: " + reviewId));
        Long toiletId = review.getToilet() == null ? null : review.getToilet().getId();
        if (toiletId == null) {
            throw new IllegalStateException("toiletId is required for reviewId: " + reviewId);
        }
        long rating = review.getRating() == null ? 0L : review.getRating();
        reviewRepository.delete(review);
        int updated = toiletRepository.applyRatingDelta(
                toiletId,
                -rating,
                -1L
        );
        if (updated == 0) {
            throw new IllegalStateException("Failed to update toilet rating aggregate: " + toiletId);
        }
        toiletService.evictListCache();
    }

    @Transactional
    public boolean report(Long reviewId, int blockThreshold) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid reviewId: " + reviewId));
        Long toiletId = review.getToilet() == null ? null : review.getToilet().getId();
        if (toiletId == null) {
            throw new IllegalStateException("toiletId is required for reviewId: " + reviewId);
        }
        long rating = review.getRating() == null ? 0L : review.getRating();

        int updated = reviewRepository.incrementReportCount(reviewId);
        if (updated == 0) {
            throw new IllegalArgumentException("Invalid reviewId: " + reviewId);
        }
        int deleted = reviewRepository.deleteIfReportCountGte(reviewId, blockThreshold);
        if (deleted > 0) {
            int aggUpdated = toiletRepository.applyRatingDelta(
                    toiletId,
                    -rating,
                    -1L
            );
            if (aggUpdated == 0) {
                throw new IllegalStateException("Failed to update toilet rating aggregate: " + toiletId);
            }
            toiletService.evictListCache();
            return true;
        }
        return false;
    }
}
