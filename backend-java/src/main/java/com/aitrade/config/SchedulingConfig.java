package com.aitrade.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 拆开 @Scheduled 调度池和 LLM 执行池。
 *
 * 背景：Spring 默认 @Scheduled 跑在单线程上，导致 StrategyScheduler 主调度 / 边沿监视 /
 * MatchScheduler / ReflectionWorker 互相阻塞。最严重的是单个 LLM trader 一轮决策能跑
 * 80-700 秒，期间撮合引擎都停摆。
 *
 * 方案：
 *  - scheduling 池扩到 4 线程：4 个 @Scheduled 任务互不阻塞
 *  - llmExecutor 独立池 4 线程：StrategyScheduler 把 LLM trader 异步提交到这里跑，
 *    本身立刻返回，不卡快策略（MA/INDICATOR/SCRIPT/CTA）的 tick
 *
 * 并发上限 4 是给 LLM 限流的安全垫（SiliconFlow RPM + token 预算）。
 */
@Configuration
public class SchedulingConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("aitrade-sched-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        registrar.setTaskScheduler(scheduler);
    }

    @Bean("llmExecutor")
    public ThreadPoolTaskExecutor llmExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(50);
        exec.setThreadNamePrefix("aitrade-llm-");
        // 队列满了直接丢弃新任务（下一轮 tick 还会再提交，没必要堆积）
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        exec.initialize();
        return exec;
    }
}
