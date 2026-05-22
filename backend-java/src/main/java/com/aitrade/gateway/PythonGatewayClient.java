package com.aitrade.gateway;

import com.aitrade.gateway.dto.SnapshotResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * 调用本地 Python 网关（mootdx + easytrader）。
 * 统一封装超时、异常、重试，避免业务层直接接触 HTTP 细节。
 */
@Component
public class PythonGatewayClient {

    private final RestClient client;

    public PythonGatewayClient(RestClient pythonGatewayRestClient) {
        this.client = pythonGatewayRestClient;
    }

    public Map<String, Object> health() {
        return get("/health", Map.class);
    }

    public SnapshotResponse snapshot(String codes) {
        return get("/quote/snapshot?codes=" + codes, SnapshotResponse.class);
    }

    public Map<String, Object> bars(String code, int frequency, int count) {
        return get("/quote/bars?code=" + code + "&frequency=" + frequency + "&count=" + count, Map.class);
    }

    public Map<String, Object> watchlist() {
        return get("/watchlist", Map.class);
    }

    public Map<String, Object> watchlistReload() {
        return get("/watchlist/reload", Map.class);
    }

    public Map<String, Object> position() {
        return get("/account/position", Map.class);
    }

    public Map<String, Object> balance() {
        return get("/account/balance", Map.class);
    }

    /** 简单的一次重试封装 */
    private <T> T get(String path, Class<T> type) {
        try {
            return client.get().uri(path).retrieve().body(type);
        } catch (ResourceAccessException timeoutEx) {
            // 网关偶发超时（cpolar 抖动），重试一次
            return client.get().uri(path).retrieve().body(type);
        } catch (RestClientException e) {
            throw new GatewayException("python gateway call failed: " + path, e);
        }
    }

    public static class GatewayException extends RuntimeException {
        public GatewayException(String msg, Throwable cause) { super(msg, cause); }
    }
}
