package com.aitrade.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Value("${gateway.python.base-url}")
    private String baseUrl;

    @Value("${gateway.python.token}")
    private String token;

    @Value("${gateway.python.timeout-ms:5000}")
    private int timeoutMs;

    @Bean
    public RestClient pythonGatewayRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Gateway-Token", token)
                .requestFactory(factory)
                .build();
    }
}
