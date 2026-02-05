package com.example.toilet.service;

import com.example.toilet.repository.ToiletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@RequiredArgsConstructor
@Slf4j
public class RatingSseService {

    public record RatingUpdate(Long toiletId, double avgRating, long reviewCount) {}

    private static final long EMITTER_TIMEOUT_MS = Duration.ofHours(1).toMillis();

    private final ToiletRepository toiletRepository;

    private final CopyOnWriteArrayList<SseEmitter> allEmitters = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<Long, CopyOnWriteArrayList<SseEmitter>> emittersByToilet = new ConcurrentHashMap<>();

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

    public void publishRatingUpdate(Long toiletId) {
        if (toiletId == null) return;
        var aggOpt = toiletRepository.findRatingAggById(toiletId);
        if (aggOpt.isEmpty()) return;

        long sum = aggOpt.get().getRatingSum() == null ? 0L : aggOpt.get().getRatingSum();
        long count = aggOpt.get().getRatingCount() == null ? 0L : aggOpt.get().getRatingCount();
        double avg = count <= 0 ? 0.0 : (double) sum / (double) count;

        RatingUpdate update = new RatingUpdate(toiletId, avg, count);
        sendTo(allEmitters, update);
        sendTo(emittersByToilet.get(toiletId), update);
    }

    private void sendTo(List<SseEmitter> emitters, RatingUpdate update) {
        if (emitters == null || emitters.isEmpty()) return;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("rating-updated")
                        .data(update));
            } catch (IOException e) {
                safeComplete(emitter);
                emitters.remove(emitter);
                log.debug("SSE emitter removed due to send failure: {}", e.getMessage());
            }
        }
    }

    private void removeEmitter(Long toiletId, SseEmitter emitter) {
        safeComplete(emitter);
        if (toiletId == null) {
            allEmitters.remove(emitter);
            return;
        }
        List<SseEmitter> list = emittersByToilet.get(toiletId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emittersByToilet.remove(toiletId);
            }
        }
    }

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignore) {
        }
    }
}
