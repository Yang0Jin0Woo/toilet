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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

class RenderLatencyStatsTest {

    private static final Logger log = LoggerFactory.getLogger(RenderLatencyStatsTest.class);
    private static final int REPEATS_PER_EVENT = 50;

    @Test
    void logRenderLatencyStats() throws Exception {
        List<Point> points = loadPoints();
        if (points.isEmpty()) {
            log.warn("No points loaded; skip render latency stats.");
            return;
        }

        List<Event> events = buildEvents(points, points.size(), new int[]{4});

        ScenarioResult sortResult = runScenario("RENDER_SORT", events, points, true);
        log.info("RENDER_SORT totalMs={} samples={} points={} events={} repeats={}",
                sortResult.totalMs, sortResult.samples, points.size(), events.size(), REPEATS_PER_EVENT);
        for (EventResult result : sortResult.results) {
            log.info("RENDER_SORT #{} zoom={} elapsedMs={} repeats={} selected={}",
                    result.index, result.zoomLevel, result.elapsedMs, REPEATS_PER_EVENT, result.selected);
        }

        ScenarioResult heapResult = runScenario("RENDER_HEAP", events, points, false);
        log.info("RENDER_HEAP totalMs={} samples={} points={} events={} repeats={}",
                heapResult.totalMs, heapResult.samples, points.size(), events.size(), REPEATS_PER_EVENT);
        for (EventResult result : heapResult.results) {
            log.info("RENDER_HEAP #{} zoom={} elapsedMs={} repeats={} selected={}",
                    result.index, result.zoomLevel, result.elapsedMs, REPEATS_PER_EVENT, result.selected);
        }
    }

    private static ScenarioResult runScenario(String label, List<Event> events, List<Point> points, boolean useSort) {
        long scenarioStart = System.nanoTime();
        int eventIndex = 0;
        List<EventResult> results = new ArrayList<>(events.size());
        for (Event event : events) {
            eventIndex++;
            long elapsedNs = 0;
            int selected = 0;
            for (int r = 0; r < REPEATS_PER_EVENT; r++) {
                long start = System.nanoTime();
                if (useSort) {
                    selected = renderWithSort(points, event);
                } else {
                    selected = renderWithHeap(points, event);
                }
                elapsedNs += System.nanoTime() - start;
            }
            long elapsedMs = elapsedNs / 1_000_000;
            if (eventIndex == 1) {
                // Skip the first measurement after server startup.
                continue;
            }
            results.add(new EventResult(eventIndex, event.zoomLevel, elapsedMs, selected));
            if (selected == 0) {
                System.identityHashCode(event);
            }
        }
        long totalMs = (System.nanoTime() - scenarioStart) / 1_000_000;
        return new ScenarioResult(label, totalMs, results.size(), results);
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

    private static int renderWithSort(List<Point> points, Event event) {
        List<Point> candidates = points;
        int limit = limitForZoom(event.zoomLevel);
        if (limit <= 0) return 0;

        List<PointDist> all = new ArrayList<>(candidates.size());
        for (Point p : candidates) {
            double dist = haversine(event.center.lat, event.center.lng, p.lat, p.lng);
            all.add(new PointDist(p, dist));
        }
        all.sort(Comparator.comparingDouble(a -> a.dist));
        return renderMarkers(all);
    }

    private static int renderWithHeap(List<Point> points, Event event) {
        List<Point> candidates = points;
        int limit = limitForZoom(event.zoomLevel);
        if (limit <= 0) return 0;

        BoundedMaxHeap heap = new BoundedMaxHeap(limit);
        for (Point p : candidates) {
            double dist = haversine(event.center.lat, event.center.lng, p.lat, p.lng);
            heap.push(new PointDist(p, dist));
        }
        List<PointDist> selected = heap.toList();
        selected.sort(Comparator.comparingDouble(a -> a.dist));
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

        private BoundedMaxHeap(int limit) {
            this.limit = limit;
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
            return new ArrayList<>(heap);
        }

        private void siftUp(int idx) {
            int i = idx;
            while (i > 0) {
                int p = (i - 1) >>> 1;
                if (heap.get(p).dist >= heap.get(i).dist) break;
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
                if (l < n && heap.get(l).dist > heap.get(largest).dist) largest = l;
                if (r < n && heap.get(r).dist > heap.get(largest).dist) largest = r;
                if (largest == i) break;
                Collections.swap(heap, i, largest);
                i = largest;
            }
        }
    }

    private record EventResult(int index, int zoomLevel, long elapsedMs, int selected) {
    }

    private record ScenarioResult(String label, long totalMs, int samples, List<EventResult> results) {
    }
}
