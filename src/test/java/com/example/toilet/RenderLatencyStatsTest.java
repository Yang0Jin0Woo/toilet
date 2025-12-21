package com.example.toilet;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

class RenderLatencyStatsTest {

    private static final Logger log = LoggerFactory.getLogger(RenderLatencyStatsTest.class);
    private static final int REPEATS_PER_EVENT = 10;
    private static final int WARMUP_EVENTS = 1;
    private static volatile double DISTANCE_SINK = 0.0;

    @Test
    void logRenderLatencyStats() throws Exception {
        List<Point> points = loadPoints();
        if (points.isEmpty()) {
            log.warn("No points loaded; skip render latency stats.");
            return;
        }

        List<Event> events = buildEvents(points, points.size(), new int[]{3, 5, 6});

        ScenarioResult sortResult = runScenario("RENDER_SORT", events, points, true);
        logScenario(sortResult);

        ScenarioResult heapResult = runScenario("RENDER_HEAP", events, points, false);
        logScenario(heapResult);
    }

    private static ScenarioResult runScenario(String label, List<Event> events, List<Point> points, boolean useSort) {
        OperationCounter scenarioCounter = new OperationCounter();
        int warmupSkippedEvents = 0;
        int measuredEvents = 0;
        long scenarioDistanceOnlyNs = 0;
        long scenarioTotalNs = 0;
        Map<Integer, LevelStats> levelStats = new java.util.TreeMap<>();

        int eventIndex = 0;
        for (Event event : events) {
            eventIndex++;
            OperationCounter eventCounter = new OperationCounter();
            long distanceOnlyNs = measureDistanceOnly(points, event, eventCounter);
            long totalNs = measureAlgorithm(points, event, useSort, eventCounter);

            if (eventIndex <= WARMUP_EVENTS) {
                warmupSkippedEvents++;
                continue;
            }

            scenarioCounter.add(eventCounter);
            scenarioDistanceOnlyNs += distanceOnlyNs;
            scenarioTotalNs += totalNs;
            measuredEvents++;

            long overheadNs = totalNs - distanceOnlyNs;
            LevelStats current = levelStats.get(event.zoomLevel);
            if (current == null) {
                levelStats.put(event.zoomLevel, new LevelStats(totalNs, distanceOnlyNs, overheadNs, 1));
            } else {
                levelStats.put(event.zoomLevel, new LevelStats(
                        current.totalNs + totalNs,
                        current.distanceOnlyNs + distanceOnlyNs,
                        current.overheadNs + overheadNs,
                        current.samples + 1
                ));
            }
        }

        long overheadNs = scenarioTotalNs - scenarioDistanceOnlyNs;
        long overheadOps = scenarioCounter.overheadOps();
        long opsTotal = scenarioCounter.totalOps();

        return new ScenarioResult(label, scenarioTotalNs, scenarioDistanceOnlyNs, overheadNs,
                points.size(), events.size(), measuredEvents, warmupSkippedEvents, REPEATS_PER_EVENT,
                opsTotal, scenarioCounter.distanceCalls(), overheadOps, scenarioCounter.sortComparisons(), levelStats,
                scenarioCounter.heapComparisons(), scenarioCounter.heapSwaps(),
                scenarioCounter.finalSortComparisons(), scenarioCounter.listAllocs(),
                scenarioCounter.pointDistAllocs());
    }

    private static long measureDistanceOnly(List<Point> points, Event event, OperationCounter counter) {
        double sum = 0.0;
        long start = System.nanoTime();
        for (int r = 0; r < REPEATS_PER_EVENT; r++) {
            for (Point p : points) {
                sum += haversine(event.center.lat, event.center.lng, p.lat, p.lng);
            }
        }
        long elapsed = System.nanoTime() - start;
        DISTANCE_SINK += sum;
        return elapsed;
    }

    private static long measureAlgorithm(List<Point> points, Event event, boolean useSort, OperationCounter counter) {
        int selected = 0;
        long start = System.nanoTime();
        for (int r = 0; r < REPEATS_PER_EVENT; r++) {
            if (useSort) {
                selected = renderWithSort(points, event, counter);
            } else {
                selected = renderWithHeap(points, event, counter);
            }
        }
        long elapsed = System.nanoTime() - start;
        if (selected == 0) {
            System.identityHashCode(event);
        }
        return elapsed;
    }

    private static List<Point> loadPoints() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClassPathResource resource = new ClassPathResource("seoultoilet.json");
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = mapper.readTree(in);
            JsonNode dataNode = root.get("DATA");
            if (dataNode == null || !dataNode.isArray()) {
                return List.of();
            }
            List<Map<String, Object>> data = mapper.convertValue(
                    dataNode, new TypeReference<List<Map<String, Object>>>() {
                    }
            );
            List<Point> points = new ArrayList<>(data.size());
            for (Map<String, Object> item : data) {
                Double lat = toDouble(item.get("coord_y"));
                Double lng = toDouble(item.get("coord_x"));
                if (lat == null || lng == null) continue;
                points.add(new Point(lat, lng));
            }
            return points;
        }
    }

    private static Double toDouble(Object value) {
        if (value == null) return null;
        try {
            String text = value.toString().trim();
            if (text.isEmpty()) return null;
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Event> buildEvents(List<Point> points, int positionCount, int[] zoomLevels) {
        List<Event> events = new ArrayList<>();
        if (positionCount >= points.size()) {
            for (Point center : points) {
                for (int zoom : zoomLevels) {
                    events.add(new Event(center, zoom));
                }
            }
            return events;
        }

        Random random = new Random(42L);
        for (int i = 0; i < positionCount; i++) {
            Point center = points.get(random.nextInt(points.size()));
            for (int zoom : zoomLevels) {
                events.add(new Event(center, zoom));
            }
        }
        return events;
    }

    private static int renderWithSort(List<Point> points, Event event, OperationCounter counter) {
        List<Point> candidates = points;
        int limit = limitForZoom(event.zoomLevel);
        if (limit <= 0) return 0;

        counter.incListAlloc();
        List<PointDist> all = new ArrayList<>(candidates.size());
        for (Point p : candidates) {
            double dist = haversine(event.center.lat, event.center.lng, p.lat, p.lng);
            counter.incDistanceCall();
            counter.incPointDistAlloc();
            all.add(new PointDist(p, dist));
        }
        all.sort((a, b) -> {
            counter.incSortComparison();
            return Double.compare(a.dist, b.dist);
        });
        return renderMarkers(all);
    }

    private static int renderWithHeap(List<Point> points, Event event, OperationCounter counter) {
        List<Point> candidates = points;
        int limit = limitForZoom(event.zoomLevel);
        if (limit <= 0) return 0;

        BoundedMaxHeap heap = new BoundedMaxHeap(limit, counter);
        for (Point p : candidates) {
            double dist = haversine(event.center.lat, event.center.lng, p.lat, p.lng);
            counter.incDistanceCall();
            counter.incPointDistAlloc();
            heap.push(new PointDist(p, dist));
        }
        List<PointDist> selected = heap.toList();
        selected.sort((a, b) -> {
            counter.incFinalSortComparison();
            return Double.compare(a.dist, b.dist);
        });
        return renderMarkers(selected);
    }

    private static int renderMarkers(List<PointDist> selected) {
        int rendered = 0;
        for (PointDist pd : selected) {
            double key = pd.point.lat * 31.0 + pd.point.lng + pd.dist;
            rendered += Double.valueOf(key).hashCode() & 1;
        }
        return selected.size() + rendered - rendered;
    }

    private static int limitForZoom(int level) {
        return 60;
    }

    private static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return r * c;
    }

    private record Point(double lat, double lng) {
    }

    private record Event(Point center, int zoomLevel) {
    }

    private record PointDist(Point point, double dist) {
    }

    private static final class BoundedMaxHeap {
        private final List<PointDist> heap = new ArrayList<>();
        private final int limit;
        private final OperationCounter counter;

        private BoundedMaxHeap(int limit, OperationCounter counter) {
            this.limit = limit;
            this.counter = counter;
            this.counter.incListAlloc();
        }

        private void push(PointDist node) {
            heap.add(node);
            siftUp(heap.size() - 1);
            if (heap.size() > limit) {
                pop();
            }
        }

        private PointDist pop() {
            if (heap.isEmpty()) return null;
            PointDist top = heap.get(0);
            PointDist last = heap.remove(heap.size() - 1);
            if (!heap.isEmpty()) {
                heap.set(0, last);
                siftDown(0);
            }
            return top;
        }

        private List<PointDist> toList() {
            counter.incListAlloc();
            return new ArrayList<>(heap);
        }

        private void siftUp(int idx) {
            int i = idx;
            while (i > 0) {
                int p = (i - 1) >>> 1;
                counter.incHeapComparison();
                if (heap.get(p).dist >= heap.get(i).dist) break;
                counter.incHeapSwap();
                Collections.swap(heap, p, i);
                i = p;
            }
        }

        private void siftDown(int idx) {
            int n = heap.size();
            int i = idx;
            while (true) {
                int l = i * 2 + 1;
                int r = l + 1;
                int largest = i;
                if (l < n) {
                    counter.incHeapComparison();
                    if (heap.get(l).dist > heap.get(largest).dist) largest = l;
                }
                if (r < n) {
                    counter.incHeapComparison();
                    if (heap.get(r).dist > heap.get(largest).dist) largest = r;
                }
                if (largest == i) break;
                counter.incHeapSwap();
                Collections.swap(heap, i, largest);
                i = largest;
            }
        }
    }

    private record ScenarioResult(String label, long totalNs, long distanceOnlyNs, long overheadNs,
                                  int points, int events, int samples, int warmupSkippedEvents, int repeats,
                                  long opsTotal, long distanceCalls, long overheadOps, long sortComparisons,
                                  Map<Integer, LevelStats> levelStats,
                                  long heapComparisons, long heapSwaps, long finalSortComparisons,
                                  long listAllocs, long pointDistAllocs) {
    }

    private record LevelStats(long totalNs, long distanceOnlyNs, long overheadNs, int samples) {
    }

    private static final class OperationCounter {
        private long distanceCalls;
        private long sortComparisons;
        private long heapComparisons;
        private long heapSwaps;
        private long finalSortComparisons;
        private long listAllocs;
        private long pointDistAllocs;

        private void incDistanceCall() {
            distanceCalls++;
        }

        private void incSortComparison() {
            sortComparisons++;
        }

        private void incHeapComparison() {
            heapComparisons++;
        }

        private void incHeapSwap() {
            heapSwaps++;
        }

        private void incFinalSortComparison() {
            finalSortComparisons++;
        }

        private void incListAlloc() {
            listAllocs++;
        }

        private void incPointDistAlloc() {
            pointDistAllocs++;
        }

        private void add(OperationCounter other) {
            this.distanceCalls += other.distanceCalls;
            this.sortComparisons += other.sortComparisons;
            this.heapComparisons += other.heapComparisons;
            this.heapSwaps += other.heapSwaps;
            this.finalSortComparisons += other.finalSortComparisons;
            this.listAllocs += other.listAllocs;
            this.pointDistAllocs += other.pointDistAllocs;
        }

        private long totalOps() {
            return distanceCalls + overheadOps();
        }

        private long overheadOps() {
            return sortComparisons + heapComparisons + heapSwaps + finalSortComparisons + listAllocs + pointDistAllocs;
        }

        private long distanceCalls() {
            return distanceCalls;
        }

        private long sortComparisons() {
            return sortComparisons;
        }

        private long heapComparisons() {
            return heapComparisons;
        }

        private long heapSwaps() {
            return heapSwaps;
        }

        private long finalSortComparisons() {
            return finalSortComparisons;
        }

        private long listAllocs() {
            return listAllocs;
        }

        private long pointDistAllocs() {
            return pointDistAllocs;
        }
    }

    private static void logScenario(ScenarioResult result) {
        long expectedDistanceCalls = (long) result.samples * result.repeats * result.points;
        long totalCalls = (long) result.samples * result.repeats;
        long totalMs = result.totalNs / 1_000_000;
        long distanceOnlyMs = result.distanceOnlyNs / 1_000_000;
        long overheadMs = result.overheadNs / 1_000_000;
        double avgTotalMsPerCall = totalCalls == 0 ? 0.0 : (double) result.totalNs / totalCalls / 1_000_000;
        double avgDistanceMsPerCall = totalCalls == 0 ? 0.0 : (double) result.distanceOnlyNs / totalCalls / 1_000_000;
        double avgOverheadMsPerCall = totalCalls == 0 ? 0.0 : (double) result.overheadNs / totalCalls / 1_000_000;
        double avgTotalMsPerEvent = result.samples == 0 ? 0.0 : (double) result.totalNs / result.samples / 1_000_000;
        double avgDistanceMsPerEvent = result.samples == 0 ? 0.0 : (double) result.distanceOnlyNs / result.samples / 1_000_000;
        double avgOverheadMsPerEvent = result.samples == 0 ? 0.0 : (double) result.overheadNs / result.samples / 1_000_000;
        log.info("{} totalMs={} distanceOnlyMs={} overheadMs={}",
                result.label, totalMs, distanceOnlyMs, overheadMs);
        log.info("{} points={} events={} samples={} repeats={} warmupSkippedEvents={}",
                result.label, result.points, result.events, result.samples, result.repeats, result.warmupSkippedEvents);
        log.info("{} distanceCalls=({}/{}) opsTotal={} overheadOps={}",
                result.label, result.distanceCalls, expectedDistanceCalls, result.opsTotal, result.overheadOps);
        log.info("[{}] avg(ms/event): total={}, distance={}, overhead={} (events={})",
                result.label,
                String.format("%.2f", avgTotalMsPerEvent),
                String.format("%.2f", avgDistanceMsPerEvent),
                String.format("%.2f", avgOverheadMsPerEvent),
                result.samples);
        for (Map.Entry<Integer, LevelStats> entry : result.levelStats.entrySet()) {
            int level = entry.getKey();
            LevelStats stats = entry.getValue();
            double avgLevelTotalMs = stats.samples == 0 ? 0.0 : (double) stats.totalNs / stats.samples / 1_000_000;
            double avgLevelDistanceMs = stats.samples == 0 ? 0.0 : (double) stats.distanceOnlyNs / stats.samples / 1_000_000;
            double avgLevelOverheadMs = stats.samples == 0 ? 0.0 : (double) stats.overheadNs / stats.samples / 1_000_000;
            log.info("[{}][level={}] avg(ms/event): total={}, distance={}, overhead={} (events={})",
                    result.label,
                    level,
                    String.format("%.2f", avgLevelTotalMs),
                    String.format("%.2f", avgLevelDistanceMs),
                    String.format("%.2f", avgLevelOverheadMs),
                    stats.samples);
        }
        log.info("{} sortComparisons={} finalSortComparisons={}",
                result.label, result.sortComparisons, result.finalSortComparisons);
        log.info("{} heapComparisons={} heapSwaps={}",
                result.label, result.heapComparisons, result.heapSwaps);
        log.info("{} listAllocs={} pointDistAllocs={}",
                result.label, result.listAllocs, result.pointDistAllocs);
    }
}
