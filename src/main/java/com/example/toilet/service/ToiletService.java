package com.example.toilet.service;

import com.example.toilet.domain.Toilet;
import com.example.toilet.repository.ReviewRepository;
import com.example.toilet.repository.ToiletRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class ToiletService {

    /**
     * 캐시 적용: toiletId → sum/count (+timestamp)
     * 평균 집계 O(N) → O(1) 개선
     */
    public record RatingAgg(double sum, long count, long lastUpdatedMs) {
        public double avg() { return count == 0 ? 0.0 : sum / count; }
        public boolean isStale(long now, long ttlMs) {
            return ttlMs > 0 && now - lastUpdatedMs >= ttlMs;
        }
    }

    // 캐시 TTL 최소 보장값 (1초)
    private static final long MIN_TTL_MS = 1_000L;

    private final ConcurrentMap<Long, RatingAgg> ratingCache = new ConcurrentHashMap<>();

    private final ToiletRepository toiletRepository;
    private final ReviewRepository reviewRepository;

    private static final ThreadLocal<Long> LAST_AGG_MS = new ThreadLocal<>();

    @Value("${toilet.data.path}")
    private String toiletDataPath;

    @Value("${rating.cache.ttl-ms:300000}")
    private long ratingCacheTtlMs;

    @Value("${rating.cache.enabled:true}")
    private boolean ratingCacheEnabled;

    @Value("${rating.aggregation.mode:group}")
    private String ratingAggregationMode;

    // TTL이 너무 짧을 경우 최소값으로 보정
    @PostConstruct
    public void init() {
        try {
            if (ratingCacheTtlMs > 0 && ratingCacheTtlMs < MIN_TTL_MS) {
                log.warn("rating.cache.ttl-ms 값이 너무 작아 {}ms로 보정합니다 (현재 {}ms)", ratingCacheTtlMs, MIN_TTL_MS);
                ratingCacheTtlMs = MIN_TTL_MS;
            }
            if (toiletRepository.count() > 0) {
                // System.out.println("초기 데이터 입력 작업 생략됨(이미 데이터 존재 경우). 화장실=" + toiletRepository.count());
                return;
            }

            ObjectMapper objectMapper = new ObjectMapper();
            InputStream inputStream =
                    new ClassPathResource(toiletDataPath.substring("classpath:".length())).getInputStream();
            JsonNode rootNode = objectMapper.readTree(inputStream);
            JsonNode dataNode = rootNode.get("DATA");

            List<Map<String, Object>> data =
                    objectMapper.convertValue(dataNode, new TypeReference<List<Map<String, Object>>>() {});

            for (Map<String, Object> item : data) {
                try {
                    Object coordXObj = item.get("coord_x");
                    Object coordYObj = item.get("coord_y");
                    if (coordXObj == null || coordYObj == null) continue;
                    String sx = coordXObj.toString().trim();
                    String sy = coordYObj.toString().trim();
                    if (sx.isEmpty() || sy.isEmpty()) continue;

                    Toilet incoming = new Toilet();
                    incoming.setContsName((String) item.get("conts_name"));
                    incoming.setAddrNew((String) item.get("addr_new"));
                    incoming.setAddrOld((String) item.get("addr_old"));
                    incoming.setCoordX(Double.parseDouble(sx));
                    incoming.setCoordY(Double.parseDouble(sy));
                    incoming.setValue04((String) item.get("value_04"));
                    incoming.setValue05((String) item.get("value_05"));

                    String externalId = buildExternalId(
                            incoming.getContsName(),
                            incoming.getAddrNew(),
                            incoming.getCoordX(),
                            incoming.getCoordY()
                    );
                    incoming.setExternalId(externalId);

                    upsert(incoming);

                } catch (Exception ignore) {
                    System.err.println("잘못된 데이터로 인해 건너뜀: " + item);
                    ignore.printStackTrace();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void upsert(Toilet incoming) {
        var opt = toiletRepository.findByExternalId(incoming.getExternalId());
        if (opt.isPresent()) {
            Toilet t = opt.get();
            t.setContsName(incoming.getContsName());
            t.setAddrNew(incoming.getAddrNew());
            t.setAddrOld(incoming.getAddrOld());
            t.setCoordX(incoming.getCoordX());
            t.setCoordY(incoming.getCoordY());
            t.setValue04(incoming.getValue04());
            t.setValue05(incoming.getValue05());
            toiletRepository.save(t);
        } else {
            toiletRepository.save(incoming); // 최초 삽입
        }
    }

    private static String buildExternalId(String name, String addrNew, Double x, Double y) {
        String key = (name == null ? "" : name.trim()) + "|"
                + (addrNew == null ? "" : addrNew.trim()) + "|"
                + String.format(java.util.Locale.US, "%.6f,%.6f", x, y);
        try {
            var md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "FALLBACK_" + key.replace(' ', '_');
        }
    }

    public List<Toilet> getAllToilets() { return toiletRepository.findAll(); }

    private enum RatingAggMode { GROUP, PER_TOILET }

    private RatingAggMode resolveAggMode() {
        return "per_toilet".equalsIgnoreCase(ratingAggregationMode)
                ? RatingAggMode.PER_TOILET
                : RatingAggMode.GROUP;
    }

    private long applyGroupRatingsNoCache(List<Toilet> toilets, List<Long> ids) {
        long aggStart = System.nanoTime();
        var aggs = reviewRepository.aggregateByToiletIds(ids);
        long aggElapsedMs = (System.nanoTime() - aggStart) / 1_000_000;

        Map<Long, ReviewRepository.ToiletRatingAgg> map = aggs.stream()
                .collect(Collectors.toMap(ReviewRepository.ToiletRatingAgg::getToiletId, a -> a));

        setLastAggMs(aggElapsedMs);

        for (Toilet t : toilets) {
            var a = map.get(t.getId());
            double avg = a != null && a.getAvg() != null ? a.getAvg() : 0.0;
            long cnt = a != null && a.getCnt() != null ? a.getCnt() : 0L;
            t.setAvgRating(avg);
            t.setReviewCount(cnt);
        }
        return aggElapsedMs;
    }

    private long applyPerToiletRatingsNoCache(List<Toilet> toilets) {
        long aggStart = System.nanoTime();
        for (Toilet t : toilets) {
            var a = reviewRepository.aggregateByToiletId(t.getId());
            double avg = a != null && a.getAvg() != null ? a.getAvg() : 0.0;
            long cnt = a != null && a.getCnt() != null ? a.getCnt() : 0L;
            t.setAvgRating(avg);
            t.setReviewCount(cnt);
        }
        return (System.nanoTime() - aggStart) / 1_000_000;
    }

    private long applyPerToiletRatingsWithCache(List<Toilet> toilets, List<Long> ids, long now) {
        List<Long> missing = ids.stream()
                .filter(id -> {
                    RatingAgg agg = ratingCache.get(id);
                    return agg == null || agg.isStale(now, ratingCacheTtlMs);
                })
                .toList();

        long aggElapsedMs = 0;
        if (!missing.isEmpty()) {
            long aggStart = System.nanoTime();
            for (Long id : missing) {
                var a = reviewRepository.aggregateByToiletId(id);
                double avg = a != null && a.getAvg() != null ? a.getAvg() : 0.0;
                long cnt = a != null && a.getCnt() != null ? a.getCnt() : 0L;
                ratingCache.put(id, new RatingAgg(avg * cnt, cnt, now));
            }
            aggElapsedMs = (System.nanoTime() - aggStart) / 1_000_000;
        }

        for (Toilet t : toilets) {
            RatingAgg agg = ratingCache.get(t.getId());
            double avg = agg != null ? agg.avg() : 0.0;
            long cnt = agg != null ? agg.count() : 0L;
            t.setAvgRating(avg);
            t.setReviewCount(cnt);
        }
        return aggElapsedMs;
    }

    /**
     * 평균 평점/리뷰 수를 포함한 화장실 목록 반환
     * 캐시된 집계 결과를 우선 사용하며,
     * 캐시 미스 시 단일 GROUP BY 쿼리로 캐시를 지연 로딩
     */
    public List<Toilet> findAllWithRatings() {
        long totalStart = System.nanoTime();

        List<Toilet> toilets = toiletRepository.findAll();
        if (toilets.isEmpty()) {
            setLastAggMs(0);
            return toilets;
        }

        List<Long> ids = toilets.stream().map(Toilet::getId).collect(java.util.stream.Collectors.toList());

        long now = System.currentTimeMillis();
        RatingAggMode mode = resolveAggMode();

        if (!ratingCacheEnabled) {
            long aggElapsedMs = mode == RatingAggMode.GROUP
                    ? applyGroupRatingsNoCache(toilets, ids)
                    : applyPerToiletRatingsNoCache(toilets);
            setLastAggMs(aggElapsedMs);
            long totalElapsedMs = (System.nanoTime() - totalStart) / 1_000_000;
            log.info("Ratings computed (mode={}, cacheEnabled={}, toilets={}, aggMs={}, totalMs={})",
                    mode, ratingCacheEnabled, ids.size(), aggElapsedMs, totalElapsedMs);
            return toilets;
        }

        if (mode == RatingAggMode.PER_TOILET) {
            long aggElapsedMs = applyPerToiletRatingsWithCache(toilets, ids, now);
            setLastAggMs(aggElapsedMs);
            long totalElapsedMs = (System.nanoTime() - totalStart) / 1_000_000;
            log.info("Ratings computed (mode={}, cacheEnabled={}, toilets={}, aggMs={}, totalMs={}, cacheSize={})",
                    mode, ratingCacheEnabled, ids.size(), aggElapsedMs, totalElapsedMs, ratingCache.size());
            return toilets;
        }

        // 캐시에 없거나 TTL이 만료된 화장실 ID 목록 추출
        List<Long> missing = ids.stream()
                .filter(id -> {
                    RatingAgg agg = ratingCache.get(id);
                    return agg == null || agg.isStale(now, ratingCacheTtlMs);
                })
                .toList();
        long aggElapsedMs = 0;

        // 누락되거나 만료된 ID들에 대해 단일 그룹 집계 쿼리 수행
        if (!missing.isEmpty()) {
            long aggStart = System.nanoTime();
            var aggs = reviewRepository.aggregateByToiletIds(missing);
            aggElapsedMs = (System.nanoTime() - aggStart) / 1_000_000;

            for (var a : aggs) {
                double avg = a.getAvg() != null ? a.getAvg() : 0.0;
                long cnt = a.getCnt() != null ? a.getCnt() : 0L;
                ratingCache.put(a.getToiletId(), new RatingAgg(avg * cnt, cnt, now));
            }
            // 리뷰가 없는 화장실도 캐시에 0값으로 등록
            missing.forEach(id -> ratingCache.putIfAbsent(id, new RatingAgg(0.0, 0, now)));
        }

        for (Toilet t : toilets) {
            RatingAgg agg = ratingCache.get(t.getId());
            double avg = agg != null ? agg.avg() : 0.0;
            long cnt = agg != null ? agg.count() : 0L;
            t.setAvgRating(avg);
            t.setReviewCount(cnt);
        }

        long totalElapsedMs = (System.nanoTime() - totalStart) / 1_000_000;
        log.info("{}개 화장실 평점 집계 완료 (집계 쿼리 {} ms, 총 {} ms, 캐시크기={})",
                ids.size(), aggElapsedMs, totalElapsedMs, ratingCache.size());
        log.info("Ratings computed (mode={}, cacheEnabled={}, toilets={}, aggMs={}, totalMs={}, cacheSize={})",
                mode, ratingCacheEnabled, ids.size(), aggElapsedMs, totalElapsedMs, ratingCache.size());
        return toilets;
    }

    /**
     * 새로운 리뷰가 추가될 때, 캐시 갱신
     * 캐시가 비어 있는 경우에도 DB 조회 없이
     * 즉시 초기 엔트리를 생성
     */
    public void applyReviewDelta(Long toiletId, int ratingDelta) {
        applyReviewDelta(toiletId, ratingDelta, 1);
    }

    /**
     * 범용 캐시 증분 갱신 메서드
     * countDelta가 음수인 경우, 삭제 지원
     */
    public void applyReviewDelta(Long toiletId, int ratingDelta, long countDelta) {
        if (toiletId == null) return;
        long now = System.currentTimeMillis();
        ratingCache.compute(toiletId, (id, agg) -> {
            double baseSum = agg == null ? 0.0 : agg.sum();
            long baseCnt = agg == null ? 0 : agg.count();
            long newCnt = Math.max(0, baseCnt + countDelta);
            double newSum = Math.max(0.0, baseSum + ratingDelta);
            if (newCnt == 0) newSum = 0.0;
            return new RatingAgg(newSum, newCnt, now);
        });
    }

    /**
     * 리뷰 평점 수정 시 캐시 갱신
     * (리뷰 개수는 변경하지 않음)
     */
    public void applyReviewUpdate(Long toiletId, int oldRating, int newRating) {
        if (toiletId == null) return;
        int delta = newRating - oldRating;
        if (delta == 0) return;
        applyReviewDelta(toiletId, delta, 0);
    }

    /**
     * 특정 화장실의 평점 캐시를 제거
     * 다음 요청 시 DB에서 재집계
     */
    public void evictRating(Long toiletId) {
        if (toiletId != null) {
            ratingCache.remove(toiletId);
        }
    }

    /**
     * 특정 화장실의 평점을 DB에서 직접 다시 조회하여 캐시 갱신
     * (리뷰 삭제/수정 이후 사용)
     */
    public void refreshRatingFromDb(Long toiletId) {
        if (toiletId == null) return;
        long now = System.currentTimeMillis();
        var aggs = reviewRepository.aggregateByToiletIds(List.of(toiletId));
        if (aggs.isEmpty()) {
            ratingCache.put(toiletId, new RatingAgg(0.0, 0, now));
            return;
        }
        var a = aggs.get(0);
        double avg = a.getAvg() != null ? a.getAvg() : 0.0;
        long cnt = a.getCnt() != null ? a.getCnt() : 0L;
        ratingCache.put(toiletId, new RatingAgg(avg * cnt, cnt, now));
    }

    public Long consumeLastAggMs() {
        Long v = LAST_AGG_MS.get();
        LAST_AGG_MS.remove();
        return v;
    }

    private void setLastAggMs(long ms) {
        LAST_AGG_MS.set(ms);
    }

    public Optional<Toilet> findById(Long id) { return toiletRepository.findById(id); }
}
