package com.example.toilet;

import com.example.toilet.repository.ToiletRepository;
import com.example.toilet.service.ReviewService;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class ReviewPageViewConcurrencyTest {
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private ToiletRepository toiletRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final int TIME_OUT = 10;

    @Test
    void 백명이동시에조회_예상조회수100_실패() throws Exception {
        final Long toiletId = 화장실아이디조회();
        final int threadCount = 100;
        ExecutorService service = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);

        조회수초기화(toiletId);

        for (int i = 0; i < threadCount; i++) {
            service.execute(() -> {
                ready.countDown();
                try {
                    start.await();
                    reviewService.increaseReviewPageViewCount(toiletId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        ready.await(TIME_OUT, TimeUnit.SECONDS);
        start.countDown();
        service.shutdown();
        boolean terminated = service.awaitTermination(TIME_OUT, TimeUnit.SECONDS);
        final long viewCount = reviewService.getReviewPageViewCount(toiletId);

        Assertions.assertThat(terminated).isTrue();
        Assertions.assertThat(viewCount)
                .withFailMessage("동시성 제어 미적용으로 예상 조회수(100)와 불일치 발생: actual=%s", viewCount)
                .isEqualTo(100L);
    }

    private void 조회수초기화(Long toiletId) {
        jdbcTemplate.update(
                "insert into review_page_view (toilet_id, view_count) values (?, 0) " +
                        "on duplicate key update view_count = 0",
                toiletId
        );
    }

    private Long 화장실아이디조회() {
        return toiletRepository.findAll().stream()
                .findFirst()
                .map(t -> t.getId())
                .orElseThrow(() -> new IllegalStateException("화장실 테이블이 비어있음"));
    }
}
