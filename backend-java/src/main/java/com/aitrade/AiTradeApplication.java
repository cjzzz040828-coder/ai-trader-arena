package com.aitrade;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.aitrade.mapper")
public class AiTradeApplication {

    public static void main(String[] args) {
        // 固定 JVM 默认时区为东八区，避免 Docker/Linux 默认 UTC 导致
        // LocalDateTime.now() / new Date() 全部差 8 小时（活动卡片时间穿越到凌晨）。
        // Dockerfile 里已加 -Duser.timezone=Asia/Shanghai 兜底，这里再代码层硬设一次，
        // 让本地 IDE 直接跑、java -jar 跑、Docker 跑都一致。
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(AiTradeApplication.class, args);
    }
}
