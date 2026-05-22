package com.aitrade.trade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchScheduler {

    private final MatchEngine engine;

    @Scheduled(fixedDelay = 10_000, initialDelay = 5_000)
    public void run() {
        try {
            engine.tick();
        } catch (Exception e) {
            log.error("[match-scheduler] tick failed", e);
        }
    }
}
