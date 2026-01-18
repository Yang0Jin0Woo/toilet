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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ToiletService {

    private static final long MIN_LIST_TTL_MS = 1_000L;

    private final ToiletRepository toiletRepository;
    private final ReviewRepository reviewRepository;

    private final Object listCacheLock = new Object();
    private volatile List<ToiletSnapshot> listCache;
    private volatile long listCacheUpdatedMs;

    private static final ThreadLocal<Long> LAST_AGG_MS = new ThreadLocal<>();

    @Value("${toilet.data.path}")
    private String toiletDataPath;

    @Value("${rating.aggregation.mode:group}")
    private String ratingAggregationMode;

    @Value("${list.cache.ttl-ms:300000}")
    private long listCacheTtlMs;

    @Value("${list.cache.enabled:true}")
    private boolean listCacheEnabled;

    @PostConstruct
    public void init() {
        try {
            if (listCacheTtlMs > 0 && listCacheTtlMs < MIN_LIST_TTL_MS) {
                log.warn("list.cache.ttl-ms too small; adjusted to {}ms (current {}ms)", listCacheTtlMs, MIN_LIST_TTL_MS);
                listCacheTtlMs = MIN_LIST_TTL_MS;
            }
            ObjectMapper objectMapper = new ObjectMapper();
            InputStream inputStream =
                    new ClassPathResource(toiletDataPath.substring("classpath:".length())).getInputStream();
            JsonNode rootNode = objectMapper.readTree(inputStream);
            JsonNode dataNode = rootNode.get("DATA");

            List<Map<String, Object>> data =
                    objectMapper.convertValue(dataNode, new TypeReference<List<Map<String, Object>>>() {});
            long existingCount = toiletRepository.count();
            if (existingCount >= data.size()) {
                return;
            }

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

        RatingAggMode mode = resolveAggMode();
        AggResult result;
        synchronized (this) {
            result = buildViewsWithoutRatingCache(toilets, ids, mode);
        }
        setLastAggMs(result.aggMs);
        long totalElapsedMs = (System.nanoTime() - totalStart) / 1_000_000;
        log.info("Ratings computed (mode={}, toilets={}, aggMs={}, totalMs={})",
                mode, ids.size(), result.aggMs, totalElapsedMs);
        return result.views;
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
