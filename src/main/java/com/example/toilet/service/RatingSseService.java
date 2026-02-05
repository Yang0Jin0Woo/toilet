package com.example.toilet.service;

import com.example.toilet.repository.ToiletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;

@Service
@RequiredArgsConstructor
@Slf4j
public class RatingSseService {

    public record RatingUpdate(Long toiletId, double avgRating, long reviewCount) {}

    private static final long EMITTER_TIMEOUT_MS = Duration.ofHours(1).toMillis();

    private final ToiletRepository toiletRepository;

    private final CopyOnWriteArrayList<SseEmitter> allEmitters = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<Long, CopyOnWriteArrayList<SseEmitter>> emittersByToilet = new ConcurrentHashMap<>();
    private final Set<Long> pendingToiletIds = ConcurrentHashMap.newKeySet();
    private final ExecutorService sseExecutor = new ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(512),
            new ThreadFactory() {
                private int idx = 1;
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "sse-dispatch-" + (idx++));
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    public SseEmitter subscribe(Long toiletId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        if (toiletId == null) {
            allEmitters.add(emitter);
        } else {
            emittersByToilet.computeIfAbsent(toiletId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        }

        emitter.onCompletion(() -> removeEmitter(toiletId, emitter));
        emitter.onTimeout(() -> removeEmitter(toiletId, emitter));
        emitter.onError(e -> removeEmitter(toiletId, emitter));

        return emitter;
    }

    public void publishRatingUpdateAsync(Long toiletId) {
        if (toiletId == null) return;
        if (!pendingToiletIds.add(toiletId)) {
            return;
        }
        try {
            sseExecutor.submit(() -> {
                try {
                    publishRatingUpdate(toiletId);
                } finally {
                    pendingToiletIds.remove(toiletId);
                }
            });
        } catch (RejectedExecutionException e) {
            pendingToiletIds.remove(toiletId);
            log.debug("SSE publish rejected; dropped coalesced update for toiletId={}", toiletId);
        }
    }

    private void publishRatingUpdate(Long toiletId) {
        try {
            var aggOpt = toiletRepository.findRatingAggById(toiletId);
            if (aggOpt.isEmpty()) return;

            long sum = aggOpt.get().getRatingSum() == null ? 0L : aggOpt.get().getRatingSum();
            long count = aggOpt.get().getRatingCount() == null ? 0L : aggOpt.get().getRatingCount();
            double avg = count <= 0 ? 0.0 : (double) sum / (double) count;

            RatingUpdate update = new RatingUpdate(toiletId, avg, count);
            sendTo(null, allEmitters, update);
            sendTo(toiletId, emittersByToilet.get(toiletId), update);
        } catch (Exception e) {
            log.warn("Failed to publish rating update for toiletId={}", toiletId, e);
        }
    }

    private void sendTo(Long toiletId, List<SseEmitter> emitters, RatingUpdate update) {
        if (emitters == null || emitters.isEmpty()) return;
        List<SseEmitter> toRemove = null;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("rating-updated")
                        .data(update));
            } catch (IOException e) {
                if (toRemove == null) {
                    toRemove = new java.util.ArrayList<>();
                }
                toRemove.add(emitter);
                log.debug("SSE emitter marked for removal due to send failure: {}", e.getMessage());
            }
        }
        if (toRemove != null) {
            for (SseEmitter emitter : toRemove) {
                removeEmitter(toiletId, emitter);
            }
        }
    }

    private void removeEmitter(Long toiletId, SseEmitter emitter) {
        if (toiletId == null) {
            allEmitters.remove(emitter);
            safeComplete(emitter);
            return;
        }
        List<SseEmitter> list = emittersByToilet.get(toiletId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emittersByToilet.remove(toiletId);
            }
        }
        safeComplete(emitter);
    }

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignore) {
        }
    }

    @PreDestroy
    public void shutdown() {
        sseExecutor.shutdownNow();
    }
}
