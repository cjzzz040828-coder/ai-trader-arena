package com.aitrade.trade.strategy.llm;

import com.aitrade.entity.LlmActivity;
import com.aitrade.mapper.LlmActivityMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * LLM 决策事件总线：
 *   - 维护每个 trader 的 SSE emitter 列表（可多 tab 订阅）
 *   - 维护每个 trader 当前 in-flight decision 的事件缓冲，新订阅者上来先回放一遍
 *   - publish 同步推送给所有 emitter，异步把事件落到 llm_activity 表
 *   - 单 emitter 出错 remove，不影响其他 emitter，不外抛
 */
@Slf4j
@Component
public class LlmActivityPublisher {

    private static final int MAX_FIELD_BYTES = 4096;
    private static final int MAX_MESSAGE_BYTES = 2000;

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> emittersByTrader = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, List<LlmActivityEvent>> inFlightBuffer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> seqByDecision = new ConcurrentHashMap<>();
    private final AtomicLong nextDecisionId = new AtomicLong(1);
    private final ReentrantLock lock = new ReentrantLock();

    private final ExecutorService dbWriter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "llm-activity-db-writer");
        t.setDaemon(true);
        return t;
    });

    private final LlmActivityMapper mapper;

    public LlmActivityPublisher(LlmActivityMapper mapper) {
        this.mapper = mapper;
    }

    public long startDecision(long traderId) {
        long id = nextDecisionId.getAndIncrement();
        seqByDecision.put(id, new AtomicInteger(0));
        lock.lock();
        try {
            inFlightBuffer.put(traderId, new ArrayList<>());
        } finally {
            lock.unlock();
        }
        return id;
    }

    public int nextSeq(long decisionId) {
        return seqByDecision.computeIfAbsent(decisionId, k -> new AtomicInteger(0)).getAndIncrement();
    }

    public void finishDecision(long traderId, long decisionId) {
        lock.lock();
        try {
            inFlightBuffer.remove(traderId);
        } finally {
            lock.unlock();
        }
        seqByDecision.remove(decisionId);
    }

    public void publish(LlmActivityEvent event) {
        List<SseEmitter> snapshot;
        lock.lock();
        try {
            List<LlmActivityEvent> buf = inFlightBuffer.get(event.traderId());
            if (buf != null) buf.add(event);
            CopyOnWriteArrayList<SseEmitter> emitters = emittersByTrader.get(event.traderId());
            snapshot = emitters == null ? List.of() : new ArrayList<>(emitters);
        } finally {
            lock.unlock();
        }

        for (SseEmitter emitter : snapshot) {
            try {
                emitter.send(SseEmitter.event().data(event));
            } catch (IOException | IllegalStateException e) {
                log.debug("[llm-pub] emitter send failed trader={}: {}", event.traderId(), e.getMessage());
                removeEmitter(event.traderId(), emitter);
            }
        }

        try {
            dbWriter.submit(() -> {
                try {
                    mapper.insert(toEntity(event));
                } catch (Exception e) {
                    log.warn("[llm-pub] DB insert failed phase={}: {}", event.phase(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("[llm-pub] submit DB write rejected: {}", e.getMessage());
        }
    }

    public SseEmitter subscribe(long traderId) {
        SseEmitter emitter = new SseEmitter(0L);
        List<LlmActivityEvent> replay;
        lock.lock();
        try {
            List<LlmActivityEvent> buf = inFlightBuffer.get(traderId);
            replay = buf == null ? List.of() : new ArrayList<>(buf);
            emittersByTrader.computeIfAbsent(traderId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        } finally {
            lock.unlock();
        }

        emitter.onCompletion(() -> removeEmitter(traderId, emitter));
        emitter.onTimeout(() -> {
            try { emitter.complete(); } catch (Exception ignored) {}
            removeEmitter(traderId, emitter);
        });
        emitter.onError(t -> removeEmitter(traderId, emitter));

        for (LlmActivityEvent e : replay) {
            try {
                emitter.send(SseEmitter.event().data(e));
            } catch (IOException | IllegalStateException ex) {
                log.debug("[llm-pub] replay send failed: {}", ex.getMessage());
                removeEmitter(traderId, emitter);
                break;
            }
        }
        return emitter;
    }

    private void removeEmitter(long traderId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = emittersByTrader.get(traderId);
        if (list != null) list.remove(emitter);
    }

    private LlmActivity toEntity(LlmActivityEvent e) {
        LlmActivity row = new LlmActivity();
        row.setTraderId(e.traderId());
        row.setUserId(e.userId());
        row.setDecisionId(e.decisionId());
        row.setSeq(e.seq());
        row.setPhase(e.phase());
        row.setRound(e.round());
        row.setToolName(e.toolName());
        row.setToolCallId(e.toolCallId());
        row.setArgsJson(truncate(e.argsJson(), MAX_FIELD_BYTES));
        row.setResultJson(truncate(e.resultJson(), MAX_FIELD_BYTES));
        row.setMessage(truncate(e.message(), MAX_MESSAGE_BYTES));
        return row;
    }

    private static String truncate(String s, int maxBytes) {
        if (s == null) return null;
        if (s.length() <= maxBytes) return s;
        return s.substring(0, maxBytes) + "...(truncated, " + s.length() + " chars total)";
    }

    @PreDestroy
    public void shutdown() {
        dbWriter.shutdown();
        try {
            if (!dbWriter.awaitTermination(2, TimeUnit.SECONDS)) dbWriter.shutdownNow();
        } catch (InterruptedException e) {
            dbWriter.shutdownNow();
            Thread.currentThread().interrupt();
        }
        emittersByTrader.values().forEach(list ->
                list.forEach(e -> {
                    try { e.complete(); } catch (Exception ignored) {}
                }));
        emittersByTrader.clear();
    }
}
