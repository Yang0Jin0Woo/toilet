package com.example.toilet.service;

import com.example.toilet.domain.Toilet;
import com.example.toilet.dto.ToiletSnapshot;
import com.example.toilet.dto.ToiletView;
import com.example.toilet.repository.ToiletRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class ToiletService {

    private static final long MIN_LIST_TTL_MS = 1_000L;
    private enum UpsertResult { SKIPPED, INSERTED, UPDATED }

    private final ToiletRepository toiletRepository;

    private final Object listCacheLock = new Object();
    private volatile List<ToiletSnapshot> listCache;
    private volatile long listCacheUpdatedMs;

    private static final ThreadLocal<Long> LAST_AGG_MS = new ThreadLocal<>();

    @Value("${toilet.data.path}")
    private String toiletDataPath;

    @Value("${rating.aggregation.mode:group}")
    private String ratingAggregationMode;

    @Value("${list.cache.ttl-ms:600000}")
    private long listCacheTtlMs;

    @Value("${list.cache.enabled:true}")
    private boolean listCacheEnabled;

    @Value("${toilet.sync.enabled:false}")
    private boolean syncEnabled;

    @Value("${toilet.sync.url:}")
    private String syncUrl;

    private final AtomicBoolean syncRunning = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        try {
            if (listCacheTtlMs > 0 && listCacheTtlMs < MIN_LIST_TTL_MS) {
                log.warn("list.cache.ttl-ms too small; adjusted to {}ms (current {}ms)", listCacheTtlMs, MIN_LIST_TTL_MS);
                listCacheTtlMs = MIN_LIST_TTL_MS;
            }
            List<Toilet> initial = loadToiletsFromClasspath();
            long existingCount = toiletRepository.count();
            if (existingCount >= initial.size()) {
                return;
            }
            for (Toilet t : initial) {
                upsert(t);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void upsert(Toilet incoming) {
        UpsertResult result = upsertIfChanged(incoming);
        if (result != UpsertResult.SKIPPED) {
            evictListCache();
        }
    }

    private UpsertResult upsertIfChanged(Toilet incoming) {
        if (incoming == null || incoming.getExternalId() == null) {
            return UpsertResult.SKIPPED;
        }
        var opt = toiletRepository.findByExternalId(incoming.getExternalId());
        if (opt.isPresent()) {
            Toilet t = opt.get();
            if (isSame(t, incoming)) {
                return UpsertResult.SKIPPED;
            }
            t.setContsName(incoming.getContsName());
            t.setAddrNew(incoming.getAddrNew());
            t.setAddrOld(incoming.getAddrOld());
            t.setCoordX(incoming.getCoordX());
            t.setCoordY(incoming.getCoordY());
            t.setValue04(incoming.getValue04());
            t.setValue05(incoming.getValue05());
            toiletRepository.save(t);
            return UpsertResult.UPDATED;
        } else {
            toiletRepository.save(incoming); // initial insert
            return UpsertResult.INSERTED;
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

    private boolean isSame(Toilet current, Toilet incoming) {
        if (current == null || incoming == null) return false;
        return safeEq(current.getContsName(), incoming.getContsName())
                && safeEq(current.getAddrNew(), incoming.getAddrNew())
                && safeEq(current.getAddrOld(), incoming.getAddrOld())
                && safeEq(current.getCoordX(), incoming.getCoordX())
                && safeEq(current.getCoordY(), incoming.getCoordY())
                && safeEq(current.getValue04(), incoming.getValue04())
                && safeEq(current.getValue05(), incoming.getValue05());
    }

    private boolean safeEq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    @Scheduled(fixedDelayString = "${toilet.sync.fixed-delay-ms:3600000}")
    public void syncExternalData() {
        if (!syncEnabled) return;
        if (syncUrl == null || syncUrl.isBlank()) return;
        if (!syncRunning.compareAndSet(false, true)) {
            log.debug("toilet sync skipped: previous run still active");
            return;
        }
        long start = System.nanoTime();
        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        try {
            List<Toilet> incoming = fetchToiletsFromUrl(syncUrl);
            for (Toilet t : incoming) {
                UpsertResult result = upsertIfChanged(t);
                if (result == UpsertResult.INSERTED) inserted++;
                else if (result == UpsertResult.UPDATED) updated++;
                else skipped++;
            }
            if (inserted + updated > 0) {
                evictListCache();
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.info("toilet sync done: inserted={}, updated={}, skipped={}, total={}, {} ms",
                    inserted, updated, skipped, incoming.size(), elapsedMs);
        } catch (Exception e) {
            log.warn("toilet sync failed: {}", e.getMessage(), e);
        } finally {
            syncRunning.set(false);
        }
    }

    private List<Toilet> loadToiletsFromClasspath() throws Exception {
        InputStream inputStream =
                new ClassPathResource(toiletDataPath.substring("classpath:".length())).getInputStream();
        return parseToilets(inputStream);
    }

    private List<Toilet> fetchToiletsFromUrl(String url) throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        InputStream inputStream = restTemplate.execute(
                URI.create(url),
                org.springframework.http.HttpMethod.GET,
                null,
                response -> response.getBody()
        );
        if (inputStream == null) {
            throw new IllegalStateException("empty response body");
        }
        return parseToilets(inputStream);
    }

    private List<Toilet> parseToilets(InputStream inputStream) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode rootNode = objectMapper.readTree(inputStream);
        JsonNode dataNode = rootNode.get("DATA");
        List<Map<String, Object>> data =
                objectMapper.convertValue(dataNode, new TypeReference<List<Map<String, Object>>>() {});

        return data.stream()
                .map(item -> {
                    try {
                        Object coordXObj = item.get("coord_x");
                        Object coordYObj = item.get("coord_y");
                        if (coordXObj == null || coordYObj == null) return null;
                        String sx = coordXObj.toString().trim();
                        String sy = coordYObj.toString().trim();
                        if (sx.isEmpty() || sy.isEmpty()) return null;

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
                        return incoming;
                    } catch (Exception e) {
                        log.debug("Skipped item due to parse error: {}", item, e);
                        return null;
                    }
                })
                .filter(t -> t != null)
                .toList();
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
                        p.getValue05(),
                        p.getRatingSum(),
                        p.getRatingCount()
                ))
                .toList();
    }

    public List<ToiletView> getAllToiletViews(boolean withRatings) {
        List<ToiletSnapshot> snapshots = listCacheEnabled
                ? getAllToiletSnapshotsCached()
                : loadSnapshotsFromDb();
        if (!withRatings) {
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

        long aggStart = System.nanoTime();
        List<ToiletView> views = snapshots.stream()
                .map(t -> {
                    long cnt = t.ratingCount() == null ? 0L : t.ratingCount();
                    long sum = t.ratingSum() == null ? 0L : t.ratingSum();
                    double avg = cnt <= 0 ? 0.0 : (double) sum / (double) cnt;
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
        long aggElapsedMs = (System.nanoTime() - aggStart) / 1_000_000;
        setLastAggMs(aggElapsedMs);
        return views;
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
