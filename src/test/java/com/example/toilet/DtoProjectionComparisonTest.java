package com.example.toilet;

import com.example.toilet.domain.Review;
import com.example.toilet.domain.Toilet;
import com.example.toilet.dto.ToiletSnapshot;
import com.example.toilet.repository.ReviewRepository;
import com.example.toilet.repository.ToiletRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class DtoProjectionComparisonTest {
    private static final int RUNS = 100;
    private static final int RATING_SAMPLE = 4557;

    @Autowired
    private ToiletRepository toiletRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void compareEntityVsProjectionRegression() throws Exception {
        List<Toilet> toilets = toiletRepository.findAll();
        assertFalse(toilets.isEmpty(), "회귀 테스트를 위한 화장실 데이터가 비어 있습니다.");

        List<ToiletSnapshot> snapshots = fetchSnapshots();
        assertEquals(toilets.size(), snapshots.size(), "엔티티/프로젝션 목록 개수가 다릅니다.");

        Map<Long, Toilet> toiletsById = new HashMap<>(toilets.size());
        for (Toilet toilet : toilets) {
            toiletsById.put(toilet.getId(), toilet);
        }
        for (ToiletSnapshot snapshot : snapshots) {
            Toilet entity = toiletsById.get(snapshot.id());
            assertNotNull(entity, "프로젝션에만 존재하는 ID가 있습니다: " + snapshot.id());
            assertEquals(entity.getContsName(), snapshot.contsName());
            assertEquals(entity.getAddrNew(), snapshot.addrNew());
            assertEquals(entity.getAddrOld(), snapshot.addrOld());
            assertEquals(entity.getCoordX(), snapshot.coordX());
            assertEquals(entity.getCoordY(), snapshot.coordY());
            assertEquals(entity.getValue04(), snapshot.value04());
            assertEquals(entity.getValue05(), snapshot.value05());
            assertEquals(entity.getRatingSum(), snapshot.ratingSum());
            assertEquals(entity.getRatingCount(), snapshot.ratingCount());
        }

        List<Long> toiletIds = toilets.stream().map(Toilet::getId).limit(RATING_SAMPLE).toList();
        Map<Long, RatingTotal> entityAgg = aggregateRatingsByEntity(toiletIds);
        Map<Long, RatingTotal> projectionAgg = aggregateRatingsByProjection(toiletIds);
        assertEquals(entityAgg.keySet(), projectionAgg.keySet(), "평점 집계 대상 화장실 ID 집합이 다릅니다.");

        for (Long toiletId : entityAgg.keySet()) {
            RatingTotal entity = entityAgg.get(toiletId);
            RatingTotal projection = projectionAgg.get(toiletId);
            assertNotNull(projection, "프로젝션 집계 누락 ID: " + toiletId);
            assertEquals(entity.cnt, projection.cnt, "리뷰 개수가 다릅니다. toiletId=" + toiletId);
            assertTrue(Math.abs(entity.avg - projection.avg) < 0.000_001, "평균 평점이 다릅니다. toiletId=" + toiletId);
        }

        Metric listEntity = measureListEntity();
        Metric listProjection = measureListProjection();
        assertEquals(listEntity.rowCount, listProjection.rowCount, "목록 행 개수가 다릅니다.");
        assertEquals(1L, listEntity.sqlCount, "엔티티 목록 조회 SQL 수가 예상과 다릅니다.");
        assertEquals(1L, listProjection.sqlCount, "프로젝션 목록 조회 SQL 수가 예상과 다릅니다.");

        Metric ratingEntity = measureRatingEntity(toiletIds);
        Metric ratingProjection = measureRatingProjection(toiletIds);
        assertEquals(1L, ratingEntity.sqlCount, "엔티티 평점 조회 SQL 수가 예상과 다릅니다.");
        assertEquals(1L, ratingProjection.sqlCount, "프로젝션 평점 조회 SQL 수가 예상과 다릅니다.");
    }

    @Test
    @Tag("perf")
    void compareEntityVsProjectionPerf() throws Exception {
        List<Long> toiletIds = toiletRepository.findAll().stream()
                .map(Toilet::getId)
                .limit(RATING_SAMPLE)
                .toList();
        assertFalse(toiletIds.isEmpty(), "성능 비교를 위한 화장실 데이터가 비어 있습니다.");

        long[] listEntityTotalMs = new long[RUNS];
        long[] listEntityPayloadBytes = new long[RUNS];
        long[] listEntityMemBytes = new long[RUNS];
        long[] listEntitySqlCount = new long[RUNS];
        long[] listEntitySqlMs = new long[RUNS];
        long[] listEntityRows = new long[RUNS];

        long[] listProjectionTotalMs = new long[RUNS];
        long[] listProjectionPayloadBytes = new long[RUNS];
        long[] listProjectionMemBytes = new long[RUNS];
        long[] listProjectionSqlCount = new long[RUNS];
        long[] listProjectionSqlMs = new long[RUNS];
        long[] listProjectionRows = new long[RUNS];

        long[] ratingEntityTotalMs = new long[RUNS];
        long[] ratingEntityPayloadBytes = new long[RUNS];
        long[] ratingEntityMemBytes = new long[RUNS];
        long[] ratingEntitySqlCount = new long[RUNS];
        long[] ratingEntitySqlMs = new long[RUNS];
        long[] ratingEntityRows = new long[RUNS];

        long[] ratingProjectionTotalMs = new long[RUNS];
        long[] ratingProjectionPayloadBytes = new long[RUNS];
        long[] ratingProjectionMemBytes = new long[RUNS];
        long[] ratingProjectionSqlCount = new long[RUNS];
        long[] ratingProjectionSqlMs = new long[RUNS];
        long[] ratingProjectionRows = new long[RUNS];

        for (int i = 0; i < RUNS; i++) {
            Metric listEntity = measureListEntity();
            listEntityTotalMs[i] = listEntity.totalMs;
            listEntityPayloadBytes[i] = listEntity.payloadBytes;
            listEntityMemBytes[i] = listEntity.memBytes;
            listEntitySqlCount[i] = listEntity.sqlCount;
            listEntitySqlMs[i] = listEntity.sqlElapsedMs;
            listEntityRows[i] = listEntity.rowCount;

            Metric listProjection = measureListProjection();
            listProjectionTotalMs[i] = listProjection.totalMs;
            listProjectionPayloadBytes[i] = listProjection.payloadBytes;
            listProjectionMemBytes[i] = listProjection.memBytes;
            listProjectionSqlCount[i] = listProjection.sqlCount;
            listProjectionSqlMs[i] = listProjection.sqlElapsedMs;
            listProjectionRows[i] = listProjection.rowCount;

            Metric ratingEntity = measureRatingEntity(toiletIds);
            ratingEntityTotalMs[i] = ratingEntity.totalMs;
            ratingEntityPayloadBytes[i] = ratingEntity.payloadBytes;
            ratingEntityMemBytes[i] = ratingEntity.memBytes;
            ratingEntitySqlCount[i] = ratingEntity.sqlCount;
            ratingEntitySqlMs[i] = ratingEntity.sqlElapsedMs;
            ratingEntityRows[i] = ratingEntity.rowCount;

            Metric ratingProjection = measureRatingProjection(toiletIds);
            ratingProjectionTotalMs[i] = ratingProjection.totalMs;
            ratingProjectionPayloadBytes[i] = ratingProjection.payloadBytes;
            ratingProjectionMemBytes[i] = ratingProjection.memBytes;
            ratingProjectionSqlCount[i] = ratingProjection.sqlCount;
            ratingProjectionSqlMs[i] = ratingProjection.sqlElapsedMs;
            ratingProjectionRows[i] = ratingProjection.rowCount;
        }

        logSummary("LIST_ENTITY", listEntityTotalMs, listEntityPayloadBytes, listEntityMemBytes,
                listEntitySqlCount, listEntitySqlMs, listEntityRows);
        logSummary("LIST_PROJECTION", listProjectionTotalMs, listProjectionPayloadBytes, listProjectionMemBytes,
                listProjectionSqlCount, listProjectionSqlMs, listProjectionRows);
        logSummary("RATING_ENTITY", ratingEntityTotalMs, ratingEntityPayloadBytes, ratingEntityMemBytes,
                ratingEntitySqlCount, ratingEntitySqlMs, ratingEntityRows);
        logSummary("RATING_PROJECTION", ratingProjectionTotalMs, ratingProjectionPayloadBytes, ratingProjectionMemBytes,
                ratingProjectionSqlCount, ratingProjectionSqlMs, ratingProjectionRows);
    }

    private Metric measureListEntity() throws Exception {
        gcAndSleep();
        entityManager.clear();
        long beforeMem = usedMemory();
        SlowQueryTestConfig.resetSqlCounters();

        long start = System.nanoTime();
        List<Toilet> toilets = toiletRepository.findAll();
        long totalMs = nanosToMs(System.nanoTime() - start);

        long afterMem = usedMemory();
        long payloadBytes = objectMapper.writeValueAsBytes(toilets).length;

        return new Metric(
                totalMs,
                payloadBytes,
                Math.max(0L, afterMem - beforeMem),
                SlowQueryTestConfig.getSqlToiletListCount(),
                SlowQueryTestConfig.getSqlToiletListElapsedMs(),
                toilets.size()
        );
    }

    private Metric measureListProjection() throws Exception {
        gcAndSleep();
        entityManager.clear();
        long beforeMem = usedMemory();
        SlowQueryTestConfig.resetSqlCounters();

        long start = System.nanoTime();
        List<ToiletSnapshot> snapshots = fetchSnapshots();
        long totalMs = nanosToMs(System.nanoTime() - start);

        long afterMem = usedMemory();
        long payloadBytes = objectMapper.writeValueAsBytes(snapshots).length;

        return new Metric(
                totalMs,
                payloadBytes,
                Math.max(0L, afterMem - beforeMem),
                SlowQueryTestConfig.getSqlToiletListCount(),
                SlowQueryTestConfig.getSqlToiletListElapsedMs(),
                snapshots.size()
        );
    }

    private Metric measureRatingEntity(List<Long> ids) throws Exception {
        gcAndSleep();
        entityManager.clear();
        long beforeMem = usedMemory();
        SlowQueryTestConfig.resetSqlCounters();

        long start = System.nanoTime();
        List<Review> reviews = entityManager.createQuery(
                        "select r from Review r join fetch r.toilet t " +
                                "where t.id in :ids " +
                                "and (r.blocked = false or r.blocked is null)",
                        Review.class)
                .setParameter("ids", ids)
                .getResultList();
        long totalMs = nanosToMs(System.nanoTime() - start);

        long afterMem = usedMemory();
        List<ReviewPayload> payload = new ArrayList<>(reviews.size());
        for (Review review : reviews) {
            payload.add(new ReviewPayload(
                    review.getId(),
                    review.getToilet() == null ? null : review.getToilet().getId(),
                    review.getRating(),
                    review.getComment(),
                    review.getReportCount(),
                    review.getBlocked()
            ));
        }
        long payloadBytes = objectMapper.writeValueAsBytes(payload).length;

        return new Metric(
                totalMs,
                payloadBytes,
                Math.max(0L, afterMem - beforeMem),
                SlowQueryTestConfig.getSqlRatingAggCount(),
                SlowQueryTestConfig.getSqlRatingAggElapsedMs(),
                reviews.size()
        );
    }

    private Metric measureRatingProjection(List<Long> ids) throws Exception {
        gcAndSleep();
        entityManager.clear();
        long beforeMem = usedMemory();
        SlowQueryTestConfig.resetSqlCounters();

        long start = System.nanoTime();
        List<ReviewRepository.ToiletRatingAgg> aggs = reviewRepository.aggregateByToiletIds(ids);
        long totalMs = nanosToMs(System.nanoTime() - start);

        long afterMem = usedMemory();
        List<RatingAggView> payload = new ArrayList<>(aggs.size());
        for (ReviewRepository.ToiletRatingAgg agg : aggs) {
            double avg = agg.getAvg() == null ? 0.0 : agg.getAvg();
            long cnt = agg.getCnt() == null ? 0L : agg.getCnt();
            payload.add(new RatingAggView(agg.getToiletId(), avg, cnt));
        }
        long payloadBytes = objectMapper.writeValueAsBytes(payload).length;

        return new Metric(
                totalMs,
                payloadBytes,
                Math.max(0L, afterMem - beforeMem),
                SlowQueryTestConfig.getSqlRatingAggCount(),
                SlowQueryTestConfig.getSqlRatingAggElapsedMs(),
                aggs.size()
        );
    }

    private List<ToiletSnapshot> fetchSnapshots() {
        return toiletRepository.findAllSnapshots().stream()
                .map(p -> new ToiletSnapshot(
                        p.getId(),
                        p.getContsName(),
                        p.getAddrNew(),
                        p.getAddrOld(),
                        p.getCoordX(),
                        p.getCoordY(),
                        p.getValue04(),
                        p.getValue05(),
                        p.getRatingSum(),
                        p.getRatingCount()
                ))
                .toList();
    }

    private Map<Long, RatingTotal> aggregateRatingsByEntity(List<Long> ids) {
        List<Review> reviews = entityManager.createQuery(
                        "select r from Review r join fetch r.toilet t " +
                                "where t.id in :ids " +
                                "and (r.blocked = false or r.blocked is null)",
                        Review.class)
                .setParameter("ids", ids)
                .getResultList();
        Map<Long, RatingTotal> result = new HashMap<>();
        for (Review review : reviews) {
            Long toiletId = review.getToilet() == null ? null : review.getToilet().getId();
            if (toiletId == null) {
                continue;
            }
            RatingTotal prev = result.get(toiletId);
            long prevCnt = prev == null ? 0L : prev.cnt;
            double prevAvg = prev == null ? 0.0 : prev.avg;
            int rating = review.getRating() == null ? 0 : review.getRating();
            long nextCnt = prevCnt + 1;
            double nextAvg = ((prevAvg * prevCnt) + rating) / (double) nextCnt;
            result.put(toiletId, new RatingTotal(nextAvg, nextCnt));
        }
        return result;
    }

    private Map<Long, RatingTotal> aggregateRatingsByProjection(List<Long> ids) {
        List<ReviewRepository.ToiletRatingAgg> aggs = reviewRepository.aggregateByToiletIds(ids);
        Map<Long, RatingTotal> result = new HashMap<>(aggs.size());
        for (ReviewRepository.ToiletRatingAgg agg : aggs) {
            double avg = agg.getAvg() == null ? 0.0 : agg.getAvg();
            long cnt = agg.getCnt() == null ? 0L : agg.getCnt();
            result.put(agg.getToiletId(), new RatingTotal(avg, cnt));
        }
        return result;
    }

    private void logSummary(String label,
                            long[] totalMs,
                            long[] payloadBytes,
                            long[] memBytes,
                            long[] sqlCounts,
                            long[] sqlElapsedMs,
                            long[] rows) {
        Stats totalStats = stats(totalMs);
        Stats payloadStats = stats(payloadBytes);
        Stats memStats = stats(memBytes);
        Stats sqlMsStats = stats(sqlElapsedMs);

        long sqlCountPerReq = perRequestValue(sqlCounts);
        long rowCountPerReq = perRequestValue(rows);

        log.info("PROJECTION_COMPARE ({}, totalMs min~p95(avg)={}~{}({}) | payloadBytes min~p95(avg)={}~{}({}) | memBytes min~p95(avg)={}~{}({}) | rows={} | sqlCount={} | sqlMs min~p95(avg)={}~{}({}))",
                label,
                totalStats.min, totalStats.p95, totalStats.avg,
                payloadStats.min, payloadStats.p95, payloadStats.avg,
                memStats.min, memStats.p95, memStats.avg,
                rowCountPerReq,
                sqlCountPerReq,
                sqlMsStats.min, sqlMsStats.p95, sqlMsStats.avg);
    }

    private Stats stats(long[] values) {
        if (values.length == 0) {
            return new Stats(0, 0, 0);
        }
        long[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
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
        long sum = 0L;
        for (long v : values) {
            sum += v;
        }
        return values.length == 0 ? 0L : sum / values.length;
    }

    private long perRequestValue(long[] values) {
        if (values.length == 0) {
            return 0L;
        }
        return values[0];
    }

    private long nanosToMs(long nanos) {
        return nanos / 1_000_000;
    }

    private void gcAndSleep() {
        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long usedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private record Metric(long totalMs, long payloadBytes, long memBytes, long sqlCount, long sqlElapsedMs, long rowCount) {
    }

    private record Stats(long avg, long min, long p95) {
    }

    private record ReviewPayload(Long id, Long toiletId, Integer rating, String comment, Integer reportCount, Boolean blocked) {
    }

    private record RatingAggView(Long toiletId, double avg, long cnt) {
    }

    private record RatingTotal(double avg, long cnt) {
    }
}
