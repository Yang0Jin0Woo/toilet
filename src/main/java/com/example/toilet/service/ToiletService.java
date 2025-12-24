package com.example.toilet.service;

import com.example.toilet.domain.Toilet;
import com.example.toilet.dto.ToiletSnapshot;
import com.example.toilet.dto.ToiletView;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ToiletService {

    /**
     * Cache entry: toiletId -> sum/count (+timestamp) for fast avg lookup.
     */
    public record RatingAgg(double sum, long count, long lastUpdatedMs) {
        public double avg() { return count == 0 ? 0.0 : sum / count; }
        public boolean isStale(long now, long ttlMs) {
            return ttlMs > 0 && now - lastUpdatedMs >= ttlMs;
        }
    }

    // Minimum cache TTL (ms)
    private static final long MIN_TTL_MS = 1_000L;
    private static final long MIN_LIST_TTL_MS = 1_000L;

    private final ConcurrentMap<Long, RatingAgg> ratingCache = new ConcurrentHashMap<>();

    private final ToiletRepository toiletRepository;
    private final ReviewRepository reviewRepository;

    private final Object listCacheLock = new Object();
    private volatile List<ToiletSnapshot> listCache;
    private volatile long listCacheUpdatedMs;

    private final Object ratingCacheLock = new Object();

    private static final ThreadLocal<Long> LAST_AGG_MS = new ThreadLocal<>();

    @Value("${toilet.data.path}")
    private String toiletDataPath;

    @Value("${rating.cache.ttl-ms:300000}")
    private long ratingCacheTtlMs;

    @Value("${rating.cache.enabled:true}")
    private boolean ratingCacheEnabled;

    @Value("${rating.aggregation.mode:group}")
    private String ratingAggregationMode;

    @Value("${list.cache.ttl-ms:300000}")
    private long listCacheTtlMs;

    @Value("${list.cache.enabled:true}")
    private boolean listCacheEnabled;

    @PostConstruct
    public void init() {
        try {
            if (ratingCacheTtlMs > 0 && ratingCacheTtlMs < MIN_TTL_MS) {
                log.warn("rating.cache.ttl-ms too small; adjusted to {}ms (current {}ms)", ratingCacheTtlMs, MIN_TTL_MS);
                ratingCacheTtlMs = MIN_TTL_MS;
            }
            if (listCacheTtlMs > 0 && listCacheTtlMs < MIN_LIST_TTL_MS) {
                log.warn("list.cache.ttl-ms too small; adjusted to {}ms (current {}ms)", listCacheTtlMs, MIN_LIST_TTL_MS);
                listCacheTtlMs = MIN_LIST_TTL_MS;
            }
            if (toiletRepository.count() > 0) {
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
                    System.err.println("Skipped item due to parse error: " + item);
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
            toiletRepository.save(incoming); // initial insert
        }
        evictListCache();
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

    public List<ToiletSnapshot> getAllToiletSnapshotsCached() {
        if (!listCacheEnabled) {
            return loadSnapshotsFromDb();
        }
        long now = System.currentTimeMillis();
        List<ToiletSnapshot> cached = listCache;
        if (cached != null && !isListCacheStale(now)) {
            return cached;
        }
        synchronized (listCacheLock) {
            cached = listCache;
            if (cached == null || isListCacheStale(now)) {
                List<ToiletSnapshot> fresh = loadSnapshotsFromDb();
                listCache = fresh;
                listCacheUpdatedMs = now;
                return fresh;
            }
            return cached;
        }
    }

    private boolean isListCacheStale(long now) {
        return listCacheTtlMs > 0 && now - listCacheUpdatedMs >= listCacheTtlMs;
    }

    public void evictListCache() {
        listCache = null;
        listCacheUpdatedMs = 0L;
    }

    private List<ToiletSnapshot> loadSnapshotsFromDb() {
        return toiletRepository.findAllSnapshots().stream()
                .map(p -> new ToiletSnapshot(
                        p.getId(),
                        p.getContsName(),
                        p.getAddrNew(),
                        p.getAddrOld(),
                        p.getCoordX(),
                        p.getCoordY(),
                        p.getValue04(),
                        p.getValue05()
                ))
                .toList();
    }

    private enum RatingAggMode { GROUP, PER_TOILET }

    private RatingAggMode resolveAggMode() {
        return "per_toilet".equalsIgnoreCase(ratingAggregationMode)
                ? RatingAggMode.PER_TOILET
                : RatingAggMode.GROUP;
    }

    /**
     * Combine list cache snapshots with rating cache for withRatings=true.
     */
    public List<ToiletView> findAllWithRatings() {
        long totalStart = System.nanoTime();

        List<ToiletSnapshot> toilets = listCacheEnabled
                ? getAllToiletSnapshotsCached()
                : loadSnapshotsFromDb();
        if (toilets.isEmpty()) {
            setLastAggMs(0);
            return List.of();
        }

        List<Long> ids = toilets.stream().map(ToiletSnapshot::id).toList();

        long now = System.currentTimeMillis();
        RatingAggMode mode = resolveAggMode();

        if (!ratingCacheEnabled) {
            AggResult result = buildViewsWithoutRatingCache(toilets, ids, mode);
            setLastAggMs(result.aggMs);
            long totalElapsedMs = (System.nanoTime() - totalStart) / 1_000_000;
            log.info("Ratings computed (mode={}, cacheEnabled={}, toilets={}, aggMs={}, totalMs={})",
                    mode, ratingCacheEnabled, ids.size(), result.aggMs, totalElapsedMs);
            return result.views;
        }

        long aggElapsedMs = refreshRatingsWithCache(ids, now, mode);
        setLastAggMs(aggElapsedMs);

        List<ToiletView> views = toilets.stream()
                .map(t -> {
                    RatingAgg agg = ratingCache.get(t.id());
                    double avg = agg != null ? agg.avg() : 0.0;
                    long cnt = agg != null ? agg.count() : 0L;
                    return new ToiletView(
                            t.id(),
                            t.contsName(),
                            t.addrNew(),
                            t.addrOld(),
                            t.coordX(),
                            t.coordY(),
                            t.value04(),
                            t.value05(),
                            avg,
                            cnt
                    );
                })
                .toList();

        long totalElapsedMs = (System.nanoTime() - totalStart) / 1_000_000;
        log.info("{} toilets rating aggregation finished (aggMs={}, totalMs={}, cacheSize={})",
                ids.size(), aggElapsedMs, totalElapsedMs, ratingCache.size());
        log.info("Ratings computed (mode={}, cacheEnabled={}, toilets={}, aggMs={}, totalMs={}, cacheSize={})",
                mode, ratingCacheEnabled, ids.size(), aggElapsedMs, totalElapsedMs, ratingCache.size());
        return views;
    }

    public List<ToiletView> getAllToiletViews(boolean withRatings) {
        if (withRatings) {
            return findAllWithRatings();
        }
        List<ToiletSnapshot> snapshots = listCacheEnabled
                ? getAllToiletSnapshotsCached()
                : loadSnapshotsFromDb();
        return snapshots.stream()
                .map(t -> new ToiletView(
                        t.id(),
                        t.contsName(),
                        t.addrNew(),
                        t.addrOld(),
                        t.coordX(),
                        t.coordY(),
                        t.value04(),
                        t.value05(),
                        0.0,
                        0L
                ))
                .toList();
    }

    private AggResult buildViewsWithoutRatingCache(List<ToiletSnapshot> toilets,
                                                   List<Long> ids,
                                                   RatingAggMode mode) {
        long aggStart = System.nanoTime();
        Map<Long, AggSnapshot> map;
        if (mode == RatingAggMode.PER_TOILET) {
            map = ids.stream()
                    .collect(Collectors.toMap(id -> id, id -> {
                        var a = reviewRepository.aggregateByToiletId(id);
                        double avg = a != null && a.getAvg() != null ? a.getAvg() : 0.0;
                        long cnt = a != null && a.getCnt() != null ? a.getCnt() : 0L;
                        return new AggSnapshot(avg, cnt);
                    }));
        } else {
            map = reviewRepository.aggregateByToiletIds(ids).stream()
                    .collect(Collectors.toMap(ReviewRepository.ToiletRatingAgg::getToiletId, a -> {
                        double avg = a.getAvg() != null ? a.getAvg() : 0.0;
                        long cnt = a.getCnt() != null ? a.getCnt() : 0L;
                        return new AggSnapshot(avg, cnt);
                    }));
        }
        long aggElapsedMs = (System.nanoTime() - aggStart) / 1_000_000;
        List<ToiletView> views = toilets.stream()
                .map(t -> {
                    var a = map.get(t.id());
                    double avg = a != null ? a.avg : 0.0;
                    long cnt = a != null ? a.cnt : 0L;
                    return new ToiletView(
                            t.id(),
                            t.contsName(),
                            t.addrNew(),
                            t.addrOld(),
                            t.coordX(),
                            t.coordY(),
                            t.value04(),
                            t.value05(),
                            avg,
                            cnt
                    );
                })
                .toList();
        return new AggResult(views, aggElapsedMs);
    }

    private long refreshRatingsWithCache(List<Long> ids, long now, RatingAggMode mode) {
        List<Long> missing = ids.stream()
                .filter(id -> {
                    RatingAgg agg = ratingCache.get(id);
                    return agg == null || agg.isStale(now, ratingCacheTtlMs);
                })
                .toList();
        if (missing.isEmpty()) {
            return 0L;
        }

        long aggStart = System.nanoTime();
        synchronized (ratingCacheLock) {
            if (mode == RatingAggMode.PER_TOILET) {
                for (Long id : missing) {
                    var a = reviewRepository.aggregateByToiletId(id);
                    double avg = a != null && a.getAvg() != null ? a.getAvg() : 0.0;
                    long cnt = a != null && a.getCnt() != null ? a.getCnt() : 0L;
                    ratingCache.put(id, new RatingAgg(avg * cnt, cnt, now));
                }
            } else {
                var aggs = reviewRepository.aggregateByToiletIds(missing);
                for (var a : aggs) {
                    double avg = a.getAvg() != null ? a.getAvg() : 0.0;
                    long cnt = a.getCnt() != null ? a.getCnt() : 0L;
                    ratingCache.put(a.getToiletId(), new RatingAgg(avg * cnt, cnt, now));
                }
            }
            missing.forEach(id -> ratingCache.putIfAbsent(id, new RatingAgg(0.0, 0, now)));
        }
        return (System.nanoTime() - aggStart) / 1_000_000;
    }

    /**
     * Update cache after a new review insert.
     */
    public void applyReviewDelta(Long toiletId, int ratingDelta) {
        applyReviewDelta(toiletId, ratingDelta, 1);
    }

    /**
     * Update cache with rating/count delta (countDelta may be negative for delete).
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
     * Update cache when a review rating is changed.
     */
    public void applyReviewUpdate(Long toiletId, int oldRating, int newRating) {
        if (toiletId == null) return;
        int delta = newRating - oldRating;
        if (delta == 0) return;
        applyReviewDelta(toiletId, delta, 0);
    }

    /**
     * Evict a single toilet rating cache entry.
     * The next request will reload it from DB.
     */
    public void evictRating(Long toiletId) {
        if (toiletId != null) {
            ratingCache.remove(toiletId);
        }
    }

    /**
     * Refresh a single toilet rating cache from DB.
     * Used after review insert/update/delete.
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

    private record AggResult(List<ToiletView> views, long aggMs) {}
    private record AggSnapshot(double avg, long cnt) {}
}

