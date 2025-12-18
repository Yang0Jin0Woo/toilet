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
     * Ratings cache: toiletId -> sum/count (+timestamp) for O(1) avg reuse across requests.
     */
    public record RatingAgg(double sum, long count, long lastUpdatedMs) {
        public double avg() { return count == 0 ? 0.0 : sum / count; }
        public boolean isStale(long now, long ttlMs) {
            return ttlMs > 0 && now - lastUpdatedMs >= ttlMs;
        }
    }

    private static final long MIN_TTL_MS = 1_000L;

    private final ConcurrentMap<Long, RatingAgg> ratingCache = new ConcurrentHashMap<>();

    private final ToiletRepository toiletRepository;
    private final ReviewRepository reviewRepository;

    @Value("${toilet.data.path}")
    private String toiletDataPath;

    @Value("${rating.cache.ttl-ms:300000}")
    private long ratingCacheTtlMs;

    @PostConstruct
    public void init() {
        try {
            if (ratingCacheTtlMs > 0 && ratingCacheTtlMs < MIN_TTL_MS) {
                log.warn("rating.cache.ttl-ms {}ms is too small; clamping to {}ms", ratingCacheTtlMs, MIN_TTL_MS);
                ratingCacheTtlMs = MIN_TTL_MS;
            }
            if (toiletRepository.count() > 0) {
                // System.out.println("초기 데이터 입력 작업 생략됨. 화장실=" + toiletRepository.count());
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
            toiletRepository.save(incoming); // 최초 insert
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

    /**
     * Returns toilets with avg/count, preferring cached aggregates.
     * Cache is populated lazily on misses using a single group-by query.
     */
    public List<Toilet> findAllWithRatings() {
        long totalStart = System.nanoTime();

        List<Toilet> toilets = toiletRepository.findAll();
        if (toilets.isEmpty()) return toilets;

        List<Long> ids = toilets.stream().map(Toilet::getId).collect(java.util.stream.Collectors.toList());

        long now = System.currentTimeMillis();

        // Load missing or stale ids into cache with one group-by query.
        List<Long> missing = ids.stream()
                .filter(id -> {
                    RatingAgg agg = ratingCache.get(id);
                    return agg == null || agg.isStale(now, ratingCacheTtlMs);
                })
                .toList();
        long aggElapsedMs = 0;
        if (!missing.isEmpty()) {
            long aggStart = System.nanoTime();
            var aggs = reviewRepository.aggregateByToiletIds(missing);
            aggElapsedMs = (System.nanoTime() - aggStart) / 1_000_000;

            for (var a : aggs) {
                double avg = a.getAvg() != null ? a.getAvg() : 0.0;
                long cnt = a.getCnt() != null ? a.getCnt() : 0L;
                ratingCache.put(a.getToiletId(), new RatingAgg(avg * cnt, cnt, now));
            }
            // ensure toilets with no reviews still get zeroed cache entry
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
        log.info("Aggregated review averages for {} toilets (aggregate query {} ms, total {} ms, cacheSize={})",
                ids.size(), aggElapsedMs, totalElapsedMs, ratingCache.size());
        return toilets;
    }

    /**
     * Update cache when a new review is added. If cache is empty, we lazily
     * initialize an entry instead of forcing a DB round trip.
     */
    public void applyReviewDelta(Long toiletId, int ratingDelta) {
        applyReviewDelta(toiletId, ratingDelta, 1);
    }

    /**
     * Generic cache delta updater (supports delete via negative countDelta).
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
     * Update cache for review rating change without altering count.
     */
    public void applyReviewUpdate(Long toiletId, int oldRating, int newRating) {
        if (toiletId == null) return;
        int delta = newRating - oldRating;
        if (delta == 0) return;
        applyReviewDelta(toiletId, delta, 0);
    }

    /**
     * Evict a single toilet's cached rating (forces reload on next request).
     */
    public void evictRating(Long toiletId) {
        if (toiletId != null) {
            ratingCache.remove(toiletId);
        }
    }

    /**
     * Refresh a single toilet's rating directly from DB (e.g., after delete/update).
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

    public Optional<Toilet> findById(Long id) { return toiletRepository.findById(id); }
}
