package com.example.toilet;

import com.example.toilet.domain.Review;
import com.example.toilet.domain.Toilet;
import com.example.toilet.repository.ReviewRepository;
import com.example.toilet.repository.ToiletRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

@SpringBootTest(properties = {
        "rating.cache.enabled=false",
        "list.cache.enabled=false",
        "rating.cache.ttl-ms=0",
        "list.cache.ttl-ms=0",
        "sql.log.enabled=false",
        "sql.log.group-by-only=false",
        "sql.log.count-enabled=false",
        "logging.level.com.example.toilet.service.ToiletService=WARN",
        "spring.jpa.properties.hibernate.default_batch_fetch_size=100"
})
@Import(SlowQueryTestConfig.class)
@Slf4j
class GroupAggComparisonTest {
    private static final int RUNS = 100;
    private static final int TOILET_SAMPLE_SIZE = 4624;
    private static final int BATCH_FETCH_SIZE = 100;

    private enum Strategy {
        N_PLUS_ONE,
        FETCH_JOIN,
        BATCH_FETCH,
        DTO_PROJECTION
    }

    @Autowired
    private ToiletRepository toiletRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void 동일한목록조회요구사항전략비교() {
        List<Long> toiletIds = toiletRepository.findAll().stream()
                .map(Toilet::getId)
                .limit(TOILET_SAMPLE_SIZE)
                .toList();
        if (toiletIds.isEmpty()) {
            log.warn("화장실 데이터가 없어 목록 조회 전략 비교를 건너뜁니다.");
            return;
        }

        long[] fetchListCounts = new long[RUNS];
        long[] fetchAggCounts = new long[RUNS];
        long[] fetchDbCounts = new long[RUNS];
        long[] fetchRowCounts = new long[RUNS];
        long[] fetchAggMs = new long[RUNS];
        long[] fetchTotalMs = new long[RUNS];

        long[] nPlusOneListCounts = new long[RUNS];
        long[] nPlusOneAggCounts = new long[RUNS];
        long[] nPlusOneDbCounts = new long[RUNS];
        long[] nPlusOneRowCounts = new long[RUNS];
        long[] nPlusOneAggMs = new long[RUNS];
        long[] nPlusOneTotalMs = new long[RUNS];

        long[] batchListCounts = new long[RUNS];
        long[] batchAggCounts = new long[RUNS];
        long[] batchDbCounts = new long[RUNS];
        long[] batchRowCounts = new long[RUNS];
        long[] batchAggMs = new long[RUNS];
        long[] batchTotalMs = new long[RUNS];

        long[] dtoListCounts = new long[RUNS];
        long[] dtoAggCounts = new long[RUNS];
        long[] dtoDbCounts = new long[RUNS];
        long[] dtoRowCounts = new long[RUNS];
        long[] dtoAggMs = new long[RUNS];
        long[] dtoTotalMs = new long[RUNS];

        Random random = new Random(20260220L);
        List<Strategy> executionOrder = new ArrayList<>(List.of(Strategy.values()));

        for (int i = 0; i < RUNS; i++) {
            Collections.shuffle(executionOrder, random);

            Measurement nPlusOne = null;
            Measurement fetchJoin = null;
            Measurement batchFetch = null;
            Measurement dtoProjection = null;

            for (Strategy strategy : executionOrder) {
                switch (strategy) {
                    case N_PLUS_ONE -> {
                        nPlusOne = measureNPlusOnePerToilet(toiletIds);
                        storeTiming(nPlusOne.timing, i, nPlusOneListCounts, nPlusOneAggCounts, nPlusOneDbCounts, nPlusOneRowCounts, nPlusOneAggMs, nPlusOneTotalMs);
                    }
                    case FETCH_JOIN -> {
                        fetchJoin = measureFetchJoin(toiletIds);
                        storeTiming(fetchJoin.timing, i, fetchListCounts, fetchAggCounts, fetchDbCounts, fetchRowCounts, fetchAggMs, fetchTotalMs);
                    }
                    case BATCH_FETCH -> {
                        batchFetch = measureBatchFetch(toiletIds);
                        storeTiming(batchFetch.timing, i, batchListCounts, batchAggCounts, batchDbCounts, batchRowCounts, batchAggMs, batchTotalMs);
                    }
                    case DTO_PROJECTION -> {
                        dtoProjection = measureDtoProjection(toiletIds);
                        storeTiming(dtoProjection.timing, i, dtoListCounts, dtoAggCounts, dtoDbCounts, dtoRowCounts, dtoAggMs, dtoTotalMs);
                    }
                }
            }

            org.junit.jupiter.api.Assertions.assertNotNull(nPlusOne, "N+1 result should not be null");
            org.junit.jupiter.api.Assertions.assertNotNull(fetchJoin, "FETCH_JOIN result should not be null");
            org.junit.jupiter.api.Assertions.assertNotNull(batchFetch, "BATCH_FETCH result should not be null");
            org.junit.jupiter.api.Assertions.assertNotNull(dtoProjection, "DTO_PROJECTION result should not be null");

            assertSameRows(nPlusOne.rowsById, fetchJoin.rowsById, "FETCH_JOIN");
            assertSameRows(nPlusOne.rowsById, batchFetch.rowsById, "BATCH_FETCH");
            assertSameRows(nPlusOne.rowsById, dtoProjection.rowsById, "DTO_PROJECTION");
        }

        log.info("목록 조회 전략 비교 설정: runs={}, sampleToilets={}, batchFetchSize={}",
                RUNS, toiletIds.size(), BATCH_FETCH_SIZE);
        logSummary("N+1_PROBLEM", nPlusOneListCounts, nPlusOneAggCounts, nPlusOneDbCounts, nPlusOneRowCounts, nPlusOneAggMs, nPlusOneTotalMs);
        logSummary("FETCH_JOIN", fetchListCounts, fetchAggCounts, fetchDbCounts, fetchRowCounts, fetchAggMs, fetchTotalMs);
        logSummary("BATCH_FETCH", batchListCounts, batchAggCounts, batchDbCounts, batchRowCounts, batchAggMs, batchTotalMs);
        logSummary("DTO_PROJECTION", dtoListCounts, dtoAggCounts, dtoDbCounts, dtoRowCounts, dtoAggMs, dtoTotalMs);
    }

    private Measurement measureNPlusOnePerToilet(List<Long> toiletIds) {
        entityManager.clear();
        SlowQueryTestConfig.resetSqlCounters();

        long start = System.nanoTime();
        long aggStart = System.nanoTime();
        List<ListRow> rows = toiletIds.stream()
                .map(toiletId -> {
                    ReviewRepository.SingleRatingTotalAgg agg = reviewRepository.aggregateTotalsByToiletId(toiletId);
                    long sum = agg == null || agg.getSum() == null ? 0L : agg.getSum();
                    long cnt = agg == null || agg.getCnt() == null ? 0L : agg.getCnt();
                    return new ListRow(toiletId, sum, cnt);
                })
                .toList();
        long aggMsValue = (System.nanoTime() - aggStart) / 1_000_000;
        long totalMs = (System.nanoTime() - start) / 1_000_000;

        if (rows.size() != toiletIds.size()) {
            throw new IllegalStateException("n_plus_one row count mismatch");
        }
        long listCount = SlowQueryTestConfig.getSqlToiletListCount();
        long ratingAggCount = SlowQueryTestConfig.getSqlRatingAggCount();
        long dbCount = SlowQueryTestConfig.getSqlStatementCount();
        Timing timing = new Timing(totalMs, aggMsValue, listCount, ratingAggCount, dbCount, rows.size());
        return new Measurement(timing, toRowMap(rows));
    }

    private Measurement measureFetchJoin(List<Long> toiletIds) {
        entityManager.clear();
        SlowQueryTestConfig.resetSqlCounters();

        long start = System.nanoTime();
        long aggStart = System.nanoTime();
        Map<Long, RatingTotal> aggByToilet = aggregateByFetchJoin(toiletIds);
        List<ListRow> rows = buildRows(loadToilets(toiletIds), aggByToilet);
        long aggMsValue = (System.nanoTime() - aggStart) / 1_000_000;
        long totalMs = (System.nanoTime() - start) / 1_000_000;

        if (rows.size() != toiletIds.size()) {
            throw new IllegalStateException("fetch_join row count mismatch");
        }
        long listCount = SlowQueryTestConfig.getSqlToiletListCount();
        long ratingAggCount = SlowQueryTestConfig.getSqlRatingAggCount();
        long dbCount = SlowQueryTestConfig.getSqlStatementCount();
        Timing timing = new Timing(totalMs, aggMsValue, listCount, ratingAggCount, dbCount, rows.size());
        return new Measurement(timing, toRowMap(rows));
    }

    private Measurement measureBatchFetch(List<Long> toiletIds) {
        entityManager.clear();
        SlowQueryTestConfig.resetSqlCounters();

        long start = System.nanoTime();
        long aggStart = System.nanoTime();
        Map<Long, RatingTotal> aggByToilet = aggregateByBatchFetch(toiletIds);
        List<ListRow> rows = buildRows(loadToilets(toiletIds), aggByToilet);
        long aggMsValue = (System.nanoTime() - aggStart) / 1_000_000;
        long totalMs = (System.nanoTime() - start) / 1_000_000;

        if (rows.size() != toiletIds.size()) {
            throw new IllegalStateException("batch_fetch row count mismatch");
        }
        long listCount = SlowQueryTestConfig.getSqlToiletListCount();
        long ratingAggCount = SlowQueryTestConfig.getSqlRatingAggCount();
        long dbCount = SlowQueryTestConfig.getSqlStatementCount();
        Timing timing = new Timing(totalMs, aggMsValue, listCount, ratingAggCount, dbCount, rows.size());
        return new Measurement(timing, toRowMap(rows));
    }

    private Measurement measureDtoProjection(List<Long> toiletIds) {
        entityManager.clear();
        SlowQueryTestConfig.resetSqlCounters();

        Set<Long> idSet = Set.copyOf(toiletIds);
        long start = System.nanoTime();
        long aggStart = System.nanoTime();
        Map<Long, ListRow> rowsById = new HashMap<>(toiletIds.size());
        for (ToiletRepository.ToiletSnapshotProjection snapshot : toiletRepository.findAllSnapshots()) {
            Long toiletId = snapshot.getId();
            if (!idSet.contains(toiletId)) {
                continue;
            }
            long sum = snapshot.getRatingSum() == null ? 0L : snapshot.getRatingSum();
            long cnt = snapshot.getRatingCount() == null ? 0L : snapshot.getRatingCount();
            rowsById.put(toiletId, new ListRow(toiletId, sum, cnt));
        }
        for (Long toiletId : toiletIds) {
            if (!rowsById.containsKey(toiletId)) {
                throw new IllegalStateException("dto_projection result missing toiletId=" + toiletId);
            }
        }
        long aggMsValue = (System.nanoTime() - aggStart) / 1_000_000;
        long totalMs = (System.nanoTime() - start) / 1_000_000;

        long listCount = SlowQueryTestConfig.getSqlToiletListCount();
        long ratingAggCount = SlowQueryTestConfig.getSqlRatingAggCount();
        long dbCount = SlowQueryTestConfig.getSqlStatementCount();
        Timing timing = new Timing(totalMs, aggMsValue, listCount, ratingAggCount, dbCount, rowsById.size());
        return new Measurement(timing, rowsById);
    }

    private Map<Long, RatingTotal> aggregateByFetchJoin(List<Long> toiletIds) {
        List<Review> reviews = entityManager.createQuery(
                        "select r from Review r join fetch r.toilet t " +
                                "where t.id in :ids and (r.blocked = false or r.blocked is null)",
                        Review.class)
                .setParameter("ids", toiletIds)
                .getResultList();
        Map<Long, RatingTotal> aggByToilet = new HashMap<>();
        for (Review review : reviews) {
            Toilet toilet = review.getToilet();
            Long toiletId = toilet == null ? null : toilet.getId();
            if (toiletId == null) {
                continue;
            }
            RatingTotal prev = aggByToilet.get(toiletId);
            long prevSum = prev == null ? 0L : prev.sum;
            long prevCnt = prev == null ? 0L : prev.cnt;
            int rating = review.getRating() == null ? 0 : review.getRating();
            aggByToilet.put(toiletId, new RatingTotal(prevSum + rating, prevCnt + 1L));
        }
        return aggByToilet;
    }

    private Map<Long, RatingTotal> aggregateByBatchFetch(List<Long> toiletIds) {
        List<Review> reviews = entityManager.createQuery(
                        "select r from Review r " +
                                "where r.toilet.id in :ids and (r.blocked = false or r.blocked is null)",
                        Review.class)
                .setParameter("ids", toiletIds)
                .getResultList();
        Map<Long, RatingTotal> aggByToilet = new HashMap<>();
        for (Review review : reviews) {
            Toilet toilet = review.getToilet();
            if (toilet == null) {
                continue;
            }
            Hibernate.initialize(toilet);
            Long toiletId = toilet.getId();
            if (toiletId == null) {
                continue;
            }
            RatingTotal prev = aggByToilet.get(toiletId);
            long prevSum = prev == null ? 0L : prev.sum;
            long prevCnt = prev == null ? 0L : prev.cnt;
            int rating = review.getRating() == null ? 0 : review.getRating();
            aggByToilet.put(toiletId, new RatingTotal(prevSum + rating, prevCnt + 1L));
        }
        return aggByToilet;
    }

    private List<Toilet> loadToilets(List<Long> toiletIds) {
        return entityManager.createQuery(
                        "select t from Toilet t where t.id in :ids",
                        Toilet.class)
                .setParameter("ids", toiletIds)
                .getResultList();
    }

    private List<ListRow> buildRows(List<Toilet> toilets, Map<Long, RatingTotal> aggByToilet) {
        return toilets.stream()
                .map(toilet -> {
                    RatingTotal total = aggByToilet.get(toilet.getId());
                    long sum = total == null ? 0L : total.sum;
                    long cnt = total == null ? 0L : total.cnt;
                    return new ListRow(toilet.getId(), sum, cnt);
                })
                .toList();
    }

    private Map<Long, ListRow> toRowMap(List<ListRow> rows) {
        Map<Long, ListRow> rowsById = new HashMap<>(rows.size());
        for (ListRow row : rows) {
            Long toiletId = row.toiletId();
            if (toiletId == null) {
                throw new IllegalStateException("row contains null toiletId");
            }
            if (rowsById.put(toiletId, row) != null) {
                throw new IllegalStateException("duplicate toiletId in result: " + toiletId);
            }
        }
        return rowsById;
    }

    private void storeTiming(Timing timing,
                             int runIndex,
                             long[] listCounts,
                             long[] ratingAggCounts,
                             long[] dbCounts,
                             long[] rowCounts,
                             long[] aggMsValues,
                             long[] totalMsValues) {
        listCounts[runIndex] = timing.listCount;
        ratingAggCounts[runIndex] = timing.ratingAggCount;
        dbCounts[runIndex] = timing.dbCount;
        rowCounts[runIndex] = timing.rowCount;
        aggMsValues[runIndex] = timing.aggMs;
        totalMsValues[runIndex] = timing.totalMs;
    }

    private void assertSameRows(Map<Long, ListRow> expected,
                                Map<Long, ListRow> actual,
                                String label) {
        org.junit.jupiter.api.Assertions.assertEquals(
                expected.size(), actual.size(), label + " row size mismatch");
        for (Map.Entry<Long, ListRow> entry : expected.entrySet()) {
            Long toiletId = entry.getKey();
            ListRow expectedRow = entry.getValue();
            ListRow actualRow = actual.get(toiletId);
            org.junit.jupiter.api.Assertions.assertNotNull(
                    actualRow, label + " missing toiletId=" + toiletId);
            org.junit.jupiter.api.Assertions.assertEquals(
                    expectedRow.ratingSum(), actualRow.ratingSum(),
                    label + " ratingSum mismatch toiletId=" + toiletId);
            org.junit.jupiter.api.Assertions.assertEquals(
                    expectedRow.reviewCount(), actualRow.reviewCount(),
                    label + " reviewCount mismatch toiletId=" + toiletId);
        }
    }

    private void logSummary(String label,
                            long[] listCounts,
                            long[] ratingAggCounts,
                            long[] dbCounts,
                            long[] rowCounts,
                            long[] aggMsValues,
                            long[] totalMsValues) {
        long listPerReq = perRequestValue(listCounts);
        long aggPerReq = perRequestValue(ratingAggCounts);
        long dbPerReq = perRequestValue(dbCounts);
        long rowsPerReq = perRequestValue(rowCounts);
        Stats aggMsStats = stats(aggMsValues);
        Stats totalMsStats = stats(totalMsValues);

        log.info("목록 조회 전략 비교 ({}, 목록쿼리수|리뷰집계쿼리수|총DB쿼리수|행수|집계ms 평균|min~P95|전체ms 평균|min~P95:{}|{}|{}|{}|{}|{}~{}|{}|{}~{})",
                label,
                listPerReq,
                aggPerReq,
                dbPerReq,
                rowsPerReq,
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
        private final long dbCount;
        private final long rowCount;

        private Timing(long totalMs, long aggMs, long listCount, long ratingAggCount, long dbCount, long rowCount) {
            this.totalMs = totalMs;
            this.aggMs = aggMs;
            this.listCount = listCount;
            this.ratingAggCount = ratingAggCount;
            this.dbCount = dbCount;
            this.rowCount = rowCount;
        }
    }

    private static final class Measurement {
        private final Timing timing;
        private final Map<Long, ListRow> rowsById;

        private Measurement(Timing timing, Map<Long, ListRow> rowsById) {
            this.timing = timing;
            this.rowsById = rowsById;
        }
    }

    private record RatingTotal(long sum, long cnt) {
    }

    private record ListRow(Long toiletId, long ratingSum, long reviewCount) {
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
