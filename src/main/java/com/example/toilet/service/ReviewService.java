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

    public List<Review> findByToilet(Long toiletId) {
        return reviewRepository.findActiveByToiletIdOrderByIdDesc(toiletId);
    }

    public double averageForToilet(Long toiletId) {
        var list = reviewRepository.findActiveByToiletIdOrderByIdDesc(toiletId);
        return list.stream().mapToInt(Review::getRating).average().orElse(0.0);
    }

    @Transactional
    public synchronized Review save(Review r) {
        Review saved = reviewRepository.save(r);
        return saved;
    }


    @Transactional
    public synchronized Review update(Long reviewId, int newRating, String newComment) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid reviewId: " + reviewId));
        review.setRating(newRating);
        review.setComment(newComment);
        Review saved = reviewRepository.save(review);
        return saved;
    }

    @Transactional
    public synchronized void delete(Long reviewId) {
        reviewRepository.findById(reviewId).ifPresent(r -> {
            reviewRepository.delete(r);
        });
    }

    @Transactional
    public synchronized boolean report(Long reviewId, int blockThreshold) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid reviewId: " + reviewId));

        int next = (review.getReportCount() == null ? 0 : review.getReportCount()) + 1;
        if (next >= blockThreshold) {
            reviewRepository.delete(review);
            return true;
        } else {
            review.setReportCount(next);
            reviewRepository.save(review);
            return false;
        }
    }
}
