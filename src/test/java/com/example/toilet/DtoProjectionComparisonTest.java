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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;

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
    private static final int RUNS = 30;
    private static final int RATING_SAMPLE = 4557;

    @Autowired
    private ToiletRepository toiletRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void compareEntityVsProjection() throws Exception {
        List<Long> toiletIds = toiletRepository.findAll().stream()
                .map(Toilet::getId)
                .limit(RATING_SAMPLE)
                .toList();
        if (toiletIds.isEmpty()) {
            log.warn("No toilets found; skipping comparison test.");
            return;
        }

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
        List<ToiletSnapshot> snapshots = toiletRepository.findAllSnapshots().stream()
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

        log.info("PROJECTION_COMPARE ({}, totalMs avg|min~p95={}~{}~{} | payloadBytes avg|min~p95={}~{}~{} | memBytes avg|min~p95={}~{}~{} | rows={} | sqlCount={} | sqlMs avg|min~p95={}~{}~{})",
                label,
                totalStats.avg, totalStats.min, totalStats.p95,
                payloadStats.avg, payloadStats.min, payloadStats.p95,
                memStats.avg, memStats.min, memStats.p95,
                rowCountPerReq,
                sqlCountPerReq,
                sqlMsStats.avg, sqlMsStats.min, sqlMsStats.p95);
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
}
