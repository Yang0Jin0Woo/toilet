package com.example.toilet;

import com.example.toilet.domain.Review;
import com.example.toilet.domain.Toilet;
import com.example.toilet.repository.ReviewRepository;
import com.example.toilet.repository.ToiletRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest(properties = {
        "rating.cache.enabled=false",
        "list.cache.enabled=false",
        "rating.cache.ttl-ms=0",
        "list.cache.ttl-ms=0",
        "sql.log.enabled=false",
        "sql.log.group-by-only=false",
        "sql.log.count-enabled=false",
        "logging.level.com.example.toilet.service.ToiletService=WARN"
})
@Import(SlowQueryTestConfig.class)
@Slf4j
class GroupAggComparisonTest {
    private static final int RUNS = 100;
    private static final int TOILET_SAMPLE_SIZE = 4624;

    @Autowired
    private ToiletRepository toiletRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void compareGroupAggregation() {
        List<Long> toiletIds = toiletRepository.findAll().stream()
                .map(Toilet::getId)
                .limit(TOILET_SAMPLE_SIZE)
                .toList();
        if (toiletIds.isEmpty()) {
            log.warn("No toilets found; skipping aggregation comparison.");
            return;
        }

        long[] perListCounts = new long[RUNS];
        long[] perAggCounts = new long[RUNS];
        long[] perAggMs = new long[RUNS];
        long[] perTotalMs = new long[RUNS];

        long[] groupListCounts = new long[RUNS];
        long[] groupAggCounts = new long[RUNS];
        long[] groupAggMs = new long[RUNS];
        long[] groupTotalMs = new long[RUNS];

        long[] fetchListCounts = new long[RUNS];
        long[] fetchAggCounts = new long[RUNS];
        long[] fetchAggMs = new long[RUNS];
        long[] fetchTotalMs = new long[RUNS];

        for (int i = 0; i < RUNS; i++) {
            Timing per = measurePerToilet(toiletIds);
            perListCounts[i] = per.listCount;
            perAggCounts[i] = per.ratingAggCount;
            perAggMs[i] = per.aggMs;
            perTotalMs[i] = per.totalMs;

            Timing group = measureGroupBy(toiletIds);
            groupListCounts[i] = group.listCount;
            groupAggCounts[i] = group.ratingAggCount;
            groupAggMs[i] = group.aggMs;
            groupTotalMs[i] = group.totalMs;

            Timing fetchJoin = measureFetchJoin(toiletIds);
            fetchListCounts[i] = fetchJoin.listCount;
            fetchAggCounts[i] = fetchJoin.ratingAggCount;
            fetchAggMs[i] = fetchJoin.aggMs;
            fetchTotalMs[i] = fetchJoin.totalMs;
        }

        log.info("GROUP_COMPARE_CONFIG runs={} sampleToilets={}", RUNS, toiletIds.size());
        logSummary("PER_TOILET", perListCounts, perAggCounts, perAggMs, perTotalMs);
        logSummary("GROUP_BY", groupListCounts, groupAggCounts, groupAggMs, groupTotalMs);
        logSummary("FETCH_JOIN", fetchListCounts, fetchAggCounts, fetchAggMs, fetchTotalMs);
    }

    private Timing measurePerToilet(List<Long> toiletIds) {
        entityManager.clear();
        SlowQueryTestConfig.resetSqlCounters();

        long start = System.nanoTime();
        long aggStart = System.nanoTime();
        Map<Long, RatingTotal> aggByToilet = new HashMap<>(toiletIds.size());
        for (Long toiletId : toiletIds) {
            ReviewRepository.SingleRatingTotalAgg agg = reviewRepository.aggregateTotalsByToiletId(toiletId);
            long sum = agg == null || agg.getSum() == null ? 0L : agg.getSum();
            long cnt = agg == null || agg.getCnt() == null ? 0L : agg.getCnt();
            aggByToilet.put(toiletId, new RatingTotal(sum, cnt));
        }
        if (aggByToilet.size() != toiletIds.size()) {
            throw new IllegalStateException("per_toilet aggregation result mismatch");
        }
        long aggMsValue = (System.nanoTime() - aggStart) / 1_000_000;
        long totalMs = (System.nanoTime() - start) / 1_000_000;

        long listCount = SlowQueryTestConfig.getSqlToiletListCount();
        long ratingAggCount = SlowQueryTestConfig.getSqlRatingAggCount();

        return new Timing(totalMs, aggMsValue, listCount, ratingAggCount);
    }

    private Timing measureGroupBy(List<Long> toiletIds) {
        entityManager.clear();
        SlowQueryTestConfig.resetSqlCounters();

        long start = System.nanoTime();
        long aggStart = System.nanoTime();
        List<ReviewRepository.ToiletRatingAgg> aggs = reviewRepository.aggregateByToiletIds(toiletIds);
        Map<Long, RatingTotal> aggByToilet = new HashMap<>(aggs.size());
        for (ReviewRepository.ToiletRatingAgg agg : aggs) {
            long sum = 0L;
            long cnt = agg.getCnt() == null ? 0L : agg.getCnt();
            if (agg.getAvg() != null && cnt > 0L) {
                sum = Math.round(agg.getAvg() * (double) cnt);
            }
            aggByToilet.put(agg.getToiletId(), new RatingTotal(sum, cnt));
        }
        long aggMsValue = (System.nanoTime() - aggStart) / 1_000_000;
        long totalMs = (System.nanoTime() - start) / 1_000_000;

        long listCount = SlowQueryTestConfig.getSqlToiletListCount();
        long ratingAggCount = SlowQueryTestConfig.getSqlRatingAggCount();
        return new Timing(totalMs, aggMsValue, listCount, ratingAggCount);
    }

    private Timing measureFetchJoin(List<Long> toiletIds) {
        entityManager.clear();
        SlowQueryTestConfig.resetSqlCounters();

        long start = System.nanoTime();
        long aggStart = System.nanoTime();
        List<Review> reviews = entityManager.createQuery(
                        "select r from Review r join fetch r.toilet t " +
                                "where t.id in :ids and (r.blocked = false or r.blocked is null)",
                        Review.class)
                .setParameter("ids", toiletIds)
                .getResultList();
        Map<Long, RatingTotal> aggByToilet = new HashMap<>();
        for (Review review : reviews) {
            Long toiletId = review.getToilet() == null ? null : review.getToilet().getId();
            if (toiletId == null) {
                continue;
            }
            RatingTotal prev = aggByToilet.get(toiletId);
            long prevSum = prev == null ? 0L : prev.sum;
            long prevCnt = prev == null ? 0L : prev.cnt;
            int rating = review.getRating() == null ? 0 : review.getRating();
            aggByToilet.put(toiletId, new RatingTotal(prevSum + rating, prevCnt + 1L));
        }
        long aggMsValue = (System.nanoTime() - aggStart) / 1_000_000;
        long totalMs = (System.nanoTime() - start) / 1_000_000;

        long listCount = SlowQueryTestConfig.getSqlToiletListCount();
        long ratingAggCount = SlowQueryTestConfig.getSqlRatingAggCount();
        return new Timing(totalMs, aggMsValue, listCount, ratingAggCount);
    }

    private void logSummary(String label,
                            long[] listCounts,
                            long[] ratingAggCounts,
                            long[] aggMsValues,
                            long[] totalMsValues) {
        long listPerReq = perRequestValue(listCounts);
        long aggPerReq = perRequestValue(ratingAggCounts);
        Stats aggMsStats = stats(aggMsValues);
        Stats totalMsStats = stats(totalMsValues);

        log.info("GROUP_COMPARE ({}, listCount|ratingAggCount|aggMs avg|min~P95|totalMs avg|min~P95:{}|{}|{}|{}~{}|{}|{}~{})",
                label,
                listPerReq,
                aggPerReq,
                aggMsStats.avg, aggMsStats.min, aggMsStats.p95,
                totalMsStats.avg, totalMsStats.min, totalMsStats.p95);
    }

    private Stats stats(long[] values) {
        if (values.length == 0) {
            return new Stats(0, 0, 0);
        }
        long[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        long min = sorted[0];
        long p95 = percentile(sorted, 0.95);
        long avg = avg(sorted);
        return new Stats(avg, min, p95);
    }

    private long percentile(long[] sorted, double p) {
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.length) idx = sorted.length - 1;
        return sorted[idx];
    }

    private long avg(long[] values) {
        long sum = 0;
        for (long v : values) {
            sum += v;
        }
        return values.length == 0 ? 0 : sum / values.length;
    }

    private long perRequestValue(long[] values) {
        if (values.length == 0) {
            return 0;
        }
        return values[0];
    }

    private static final class Timing {
        private final long totalMs;
        private final long aggMs;
        private final long listCount;
        private final long ratingAggCount;

        private Timing(long totalMs, long aggMs, long listCount, long ratingAggCount) {
            this.totalMs = totalMs;
            this.aggMs = aggMs;
            this.listCount = listCount;
            this.ratingAggCount = ratingAggCount;
        }
    }

    private record RatingTotal(long sum, long cnt) {
    }

    private static final class Stats {
        private final long avg;
        private final long min;
        private final long p95;

        private Stats(long avg, long min, long p95) {
            this.avg = avg;
            this.min = min;
            this.p95 = p95;
        }
    }

}
