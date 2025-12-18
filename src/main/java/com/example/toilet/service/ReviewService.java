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
        return reviewRepository.findByToiletIdOrderByIdDesc(toiletId);
    }

    public double averageForToilet(Long toiletId) {
        var list = reviewRepository.findByToiletIdOrderByIdDesc(toiletId);
        return list.stream().mapToInt(Review::getRating).average().orElse(0.0);
    }

    @Transactional
    public Review save(Review r) {
        Review saved = reviewRepository.save(r);
        // incrementally update cache to avoid re-aggregating
        toiletService.applyReviewDelta(saved.getToilet().getId(), saved.getRating());
        return saved;
    }
}
