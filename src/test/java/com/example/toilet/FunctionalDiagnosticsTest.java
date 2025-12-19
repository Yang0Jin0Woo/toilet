package com.example.toilet;

import com.example.toilet.domain.Review;
import com.example.toilet.domain.Toilet;
import com.example.toilet.repository.ReviewRepository;
import com.example.toilet.repository.ToiletRepository;
import com.example.toilet.service.ToiletService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "slow.query.threshold.ms=100"
        }
)
@Import(SlowQueryTestConfig.class)
@Slf4j
class FunctionalDiagnosticsTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ToiletService toiletService;

    @Autowired
    private ToiletRepository toiletRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @Test
    void runAllDiagnosticsInOneRun() {
        TestData data = ensureTestData();
        try {
            logControllerDiagnostics();
            logServiceDiagnostics();
            logRepositoryDiagnostics(data.toiletId);
        } finally {
            cleanupTestData(data);
        }
    }

    private void logControllerDiagnostics() {
        long startNanos = System.nanoTime();
        ResponseEntity<String> response = restTemplate.getForEntity("/toilets?withRatings=true", String.class);
        long testMs = (System.nanoTime() - startNanos) / 1_000_000;

        String totalMs = response.getHeaders().getFirst("X-Total-Ms");
        String aggMs = response.getHeaders().getFirst("X-Agg-Ms");

        log.info("DIAG controller: status={}, testMs={}, X-Total-Ms={}, X-Agg-Ms={}",
                response.getStatusCode(), testMs, totalMs, aggMs);
    }

    private void logServiceDiagnostics() {
        long startNanos = System.nanoTime();
        List<Toilet> toilets = toiletService.findAllWithRatings();
        long serviceMs = (System.nanoTime() - startNanos) / 1_000_000;

        log.info("DIAG service: toilets={}, serviceMs={}", toilets.size(), serviceMs);
    }

    private void logRepositoryDiagnostics(Long toiletId) {
        if (toiletId == null) {
            log.warn("DIAG repository: skipped (no toiletId)");
            return;
        }

        long startNanos = System.nanoTime();
        var groupAggs = reviewRepository.aggregateByToiletIds(List.of(toiletId));
        long groupMs = (System.nanoTime() - startNanos) / 1_000_000;

        var singleAgg = reviewRepository.aggregateByToiletId(toiletId);

        log.info("DIAG repository: toiletId={}, groupAggs={}, groupMs={}, singleAvg={}, singleCnt={}",
                toiletId,
                groupAggs.size(),
                groupMs,
                singleAgg == null ? null : singleAgg.getAvg(),
                singleAgg == null ? null : singleAgg.getCnt());
    }

    private TestData ensureTestData() {
        var page = reviewRepository.findAll(PageRequest.of(0, 1));
        if (!page.isEmpty()) {
            Review review = page.getContent().get(0);
            return new TestData(review.getToilet().getId(), false, List.of());
        }

        Toilet toilet = new Toilet();
        toilet.setContsName("TEST_TOILET");
        toilet.setAddrNew("TEST_ADDR_NEW");
        toilet.setAddrOld("TEST_ADDR_OLD");
        toilet.setCoordX(127.0);
        toilet.setCoordY(37.0);
        toilet.setValue04("M");
        toilet.setValue05("F");
        toilet.setExternalId("TEST_" + UUID.randomUUID());
        Toilet savedToilet = toiletRepository.save(toilet);

        List<Long> reviewIds = new ArrayList<>();
        reviewIds.add(saveReview(savedToilet, 4, "TEST_REVIEW_1").getId());
        reviewIds.add(saveReview(savedToilet, 2, "TEST_REVIEW_2").getId());

        return new TestData(savedToilet.getId(), true, reviewIds);
    }

    private Review saveReview(Toilet toilet, int rating, String comment) {
        Review review = new Review();
        review.setToilet(toilet);
        review.setRating(rating);
        review.setComment(comment);
        return reviewRepository.save(review);
    }

    private void cleanupTestData(TestData data) {
        if (!data.created) return;

        if (!data.reviewIds.isEmpty()) {
            reviewRepository.deleteAllById(data.reviewIds);
        }
        if (data.toiletId != null) {
            toiletRepository.deleteById(data.toiletId);
        }
    }

    private static final class TestData {
        private final Long toiletId;
        private final boolean created;
        private final List<Long> reviewIds;

        private TestData(Long toiletId, boolean created, List<Long> reviewIds) {
            this.toiletId = toiletId;
            this.created = created;
            this.reviewIds = reviewIds;
        }
    }
}
