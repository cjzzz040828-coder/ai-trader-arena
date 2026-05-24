package com.aitrade.trade.strategy.llm;

import com.aitrade.entity.LlmActivity;
import com.aitrade.mapper.LlmActivityMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * LLM 决策事件总线：
 *   - trader 级订阅：详情页用。维护 traderId → emitters，新订阅者上来回放 in-flight buffer
 *   - 用户级订阅：dashboard 用。维护 userId → emitters，订阅后只收增量（基线走 HTTP）
 *   - publish 同步推 SSE + 异步落 llm_activity 表（单线程 executor 保证 happens-before）
 *   - DELETE 也走同一个 dbWriter 队列，避免"清空成功又冒出几条"竞态
 *   - keepalive: 每 25s 给所有 emitter 发 :ping comment，防 nginx 60s idle 切断
 */
@Slf4j
@Component
public class LlmActivityPublisher {

    private static final int MAX_FIELD_BYTES = 4096;
    private static final int MAX_MESSAGE_BYTES = 2000;
    private static final long KEEPALIVE_INTERVAL_SECONDS = 25;

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> emittersByTrader = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, List<LlmActivityEvent>> inFlightBuffer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> seqByDecision = new ConcurrentHashMap<>();
    private final AtomicLong nextDecisionId = new AtomicLong(1);
    private final ReentrantLock lock = new ReentrantLock();

    private final ExecutorService dbWriter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "llm-activity-db-writer");
        t.setDaemon(true);
        return t;
    });

    private final ScheduledExecutorService keepAliveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "llm-activity-keepalive");
        t.setDaemon(true);
        return t;
    });

    private final LlmActivityMapper mapper;

    public LlmActivityPublisher(LlmActivityMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        // 从 DB 续号，防止重启后 decisionId 跟旧数据撞键
        try {
            List<LlmActivity> top = mapper.selectList(new QueryWrapper<LlmActivity>()
                    .orderByDesc("decision_id").last("LIMIT 1"));
            if (!top.isEmpty() && top.get(0).getDecisionId() != null) {
                long maxId = top.get(0).getDecisionId();
                nextDecisionId.set(maxId + 1);
                log.info("[llm-pub] nextDecisionId initialized to {} (DB MAX+1)", maxId + 1);
            }
        } catch (Exception e) {
            log.warn("[llm-pub] init nextDecisionId failed, starting from 1: {}", e.getMessage());
        }

        keepAliveScheduler.scheduleAtFixedRate(this::sendKeepAlivePings,
                KEEPALIVE_INTERVAL_SECONDS, KEEPALIVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
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
        List<SseEmitter> traderSnapshot;
        lock.lock();
        try {
            List<LlmActivityEvent> buf = inFlightBuffer.get(event.traderId());
            if (buf != null) buf.add(event);
            CopyOnWriteArrayList<SseEmitter> tList = emittersByTrader.get(event.traderId());
            traderSnapshot = tList == null ? List.of() : new ArrayList<>(tList);
        } finally {
            lock.unlock();
        }

        // trader 级分发（锁外）
        for (SseEmitter emitter : traderSnapshot) {
            try {
                emitter.send(SseEmitter.event().data(event));
            } catch (IOException | IllegalStateException e) {
                log.debug("[llm-pub] trader emitter send failed trader={}: {}", event.traderId(), e.getMessage());
                removeTraderEmitter(event.traderId(), emitter);
            }
        }

        // 用户级分发（锁外、与 in-flight buffer 解耦）
        if (event.userId() != null) {
            CopyOnWriteArrayList<SseEmitter> uList = emittersByUser.get(event.userId());
            if (uList != null) {
                for (SseEmitter emitter : new ArrayList<>(uList)) {
                    try {
                        emitter.send(SseEmitter.event().data(event));
                    } catch (IOException | IllegalStateException e) {
                        log.debug("[llm-pub] user emitter send failed user={}: {}", event.userId(), e.getMessage());
                        removeUserEmitter(event.userId(), emitter);
                    }
                }
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

    /** trader 级订阅（详情页用）。新订阅者会拿到当前 in-flight 决策的事件回放。 */
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

        emitter.onCompletion(() -> removeTraderEmitter(traderId, emitter));
        emitter.onTimeout(() -> {
            try { emitter.complete(); } catch (Exception ignored) {}
            removeTraderEmitter(traderId, emitter);
        });
        emitter.onError(t -> removeTraderEmitter(traderId, emitter));

        for (LlmActivityEvent e : replay) {
            try {
                emitter.send(SseEmitter.event().data(e));
            } catch (IOException | IllegalStateException ex) {
                log.debug("[llm-pub] replay send failed: {}", ex.getMessage());
                removeTraderEmitter(traderId, emitter);
                break;
            }
        }
        return emitter;
    }

    /** 用户级订阅（dashboard 用）。不做 in-flight 回放，基线由前端 HTTP 拉。 */
    public SseEmitter subscribeUser(long userId) {
        SseEmitter emitter = new SseEmitter(0L);
        emittersByUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeUserEmitter(userId, emitter));
        emitter.onTimeout(() -> {
            try { emitter.complete(); } catch (Exception ignored) {}
            removeUserEmitter(userId, emitter);
        });
        emitter.onError(t -> removeUserEmitter(userId, emitter));

        return emitter;
    }

    /** 清空某个 trader 的全部 llm_activity 历史。走 dbWriter 队列保证顺序，清完发 purged 系统事件。 */
    public int clearForTrader(long traderId, Long userId) {
        Integer deleted;
        try {
            deleted = dbWriter.submit(() ->
                    mapper.delete(new QueryWrapper<LlmActivity>().eq("trader_id", traderId))).get();
        } catch (Exception e) {
            log.warn("[llm-pub] clearForTrader {} failed: {}", traderId, e.getMessage());
            return 0;
        }

        // 进行中决策的 buffer：clear 但不 remove，保留空 list 标识仍在 in-flight，
        // 剩余事件继续 publish + 落库（语义：清历史不中断未来）
        lock.lock();
        try {
            List<LlmActivityEvent> buf = inFlightBuffer.get(traderId);
            if (buf != null) buf.clear();
        } finally {
            lock.unlock();
        }

        // 广播 purged 系统事件给该用户的所有 dashboard，让多 tab 同步清空
        if (userId != null) {
            broadcastPurged(userId, traderId);
        }
        return deleted == null ? 0 : deleted;
    }

    /** 清空当前用户的全部 llm_activity 历史。 */
    public int clearForUser(long userId) {
        Integer deleted;
        try {
            deleted = dbWriter.submit(() ->
                    mapper.delete(new QueryWrapper<LlmActivity>().eq("user_id", userId))).get();
        } catch (Exception e) {
            log.warn("[llm-pub] clearForUser {} failed: {}", userId, e.getMessage());
            return 0;
        }

        // 清该用户名下所有 trader 的 in-flight buffer
        lock.lock();
        try {
            for (List<LlmActivityEvent> buf : inFlightBuffer.values()) {
                if (buf.isEmpty()) continue;
                Long evUserId = buf.get(0).userId();
                // null 安全比较：trader 未绑 user 时 userId 可能为 null，避免拆箱 NPE
                if (evUserId != null && evUserId == userId) buf.clear();
            }
        } finally {
            lock.unlock();
        }

        broadcastPurged(userId, null);
        return deleted == null ? 0 : deleted;
    }

    /** 发一个 phase=purged 的系统事件到用户级 emitter，让多 tab 同步清空 UI。不落库。 */
    private void broadcastPurged(long userId, Long traderId) {
        LlmActivityEvent ev = new LlmActivityEvent(
                traderId, userId, 0L, 0,
                "purged", null, null, null, null, null,
                traderId != null ? ("trader " + traderId + " 的活动已清空") : "已清空所有 LLM 活动",
                null,
                LocalDateTime.now().toString());
        CopyOnWriteArrayList<SseEmitter> uList = emittersByUser.get(userId);
        if (uList == null) return;
        for (SseEmitter emitter : new ArrayList<>(uList)) {
            try {
                emitter.send(SseEmitter.event().data(ev));
            } catch (IOException | IllegalStateException e) {
                removeUserEmitter(userId, emitter);
            }
        }
    }

    private void sendKeepAlivePings() {
        for (CopyOnWriteArrayList<SseEmitter> list : emittersByTrader.values()) {
            for (SseEmitter e : list) {
                try { e.send(SseEmitter.event().comment("ping")); } catch (Exception ignored) {}
            }
        }
        for (CopyOnWriteArrayList<SseEmitter> list : emittersByUser.values()) {
            for (SseEmitter e : list) {
                try { e.send(SseEmitter.event().comment("ping")); } catch (Exception ignored) {}
            }
        }
    }

    private void removeTraderEmitter(long traderId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = emittersByTrader.get(traderId);
        if (list != null) list.remove(emitter);
    }

    private void removeUserEmitter(long userId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = emittersByUser.get(userId);
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
        row.setPromptJson(truncate(e.promptJson(), MAX_FIELD_BYTES));
        return row;
    }

    private static String truncate(String s, int maxBytes) {
        if (s == null) return null;
        if (s.length() <= maxBytes) return s;
        return s.substring(0, maxBytes) + "...(truncated, " + s.length() + " chars total)";
    }

    @PreDestroy
    public void shutdown() {
        keepAliveScheduler.shutdownNow();
        dbWriter.shutdown();
        try {
            if (!dbWriter.awaitTermination(2, TimeUnit.SECONDS)) dbWriter.shutdownNow();
        } catch (InterruptedException e) {
            dbWriter.shutdownNow();
            Thread.currentThread().interrupt();
        }
        emittersByTrader.values().forEach(list ->
                list.forEach(e -> { try { e.complete(); } catch (Exception ignored) {} }));
        emittersByTrader.clear();
        emittersByUser.values().forEach(list ->
                list.forEach(e -> { try { e.complete(); } catch (Exception ignored) {} }));
        emittersByUser.clear();
    }
}
