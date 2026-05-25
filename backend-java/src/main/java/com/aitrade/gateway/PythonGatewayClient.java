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
        // bars 用 getNoRetry：回测时单只票失败可接受，retry 反而把"卡住的票"的代价翻倍。
        return getNoRetry("/quote/bars?code=" + code + "&frequency=" + frequency + "&count=" + count, Map.class);
    }

    public Map<String, Object> transaction(String code, int count) {
        return getNoRetry("/quote/transaction?code=" + code + "&count=" + count, Map.class);
    }

    public Map<String, Object> watchlist() {
        return get("/watchlist", Map.class);
    }

    /** 指定池子加载 watchlist。poolName 为 null/空时退化到 default watchlist（settings.watchlist_mode 决定）。 */
    public Map<String, Object> watchlist(String poolName) {
        if (poolName == null || poolName.isBlank()) return watchlist();
        return get("/watchlist?pool=" + poolName, Map.class);
    }

    public Map<String, Object> watchlistReload() {
        return get("/watchlist/reload", Map.class);
    }

    // ---------------- 池子注册中心 / 状态 / 历史 ----------------
    public Map<String, Object> poolList() {
        return get("/pool", Map.class);
    }

    public Map<String, Object> poolStatus(String name) {
        return get("/pool/" + name + "/status", Map.class);
    }

    public Map<String, Object> poolHistoryList(String name) {
        return get("/pool/" + name + "/history", Map.class);
    }

    public Map<String, Object> poolHistoryOne(String name, String date) {
        return get("/pool/" + name + "/history/" + date, Map.class);
    }

    /** POST：包含 body 的请求，独立封装。 */
    public Map<String, Object> poolRebuild(String name) {
        try {
            return client.post().uri("/pool/" + name + "/rebuild").retrieve().body(Map.class);
        } catch (RestClientException e) {
            throw new GatewayException("python gateway call failed: POST /pool/" + name + "/rebuild", e);
        }
    }

    public Map<String, Object> poolCreate(Map<String, Object> body) {
        try {
            return client.post().uri("/pool").body(body).retrieve().body(Map.class);
        } catch (RestClientException e) {
            throw new GatewayException("python gateway call failed: POST /pool", e);
        }
    }

    public Map<String, Object> poolUpdate(String name, Map<String, Object> body) {
        try {
            return client.put().uri("/pool/" + name).body(body).retrieve().body(Map.class);
        } catch (RestClientException e) {
            throw new GatewayException("python gateway call failed: PUT /pool/" + name, e);
        }
    }

    public Map<String, Object> poolDelete(String name) {
        try {
            return client.delete().uri("/pool/" + name).retrieve().body(Map.class);
        } catch (RestClientException e) {
            throw new GatewayException("python gateway call failed: DELETE /pool/" + name, e);
        }
    }

    public Map<String, Object> position() {
        return get("/account/position", Map.class);
    }

    public Map<String, Object> balance() {
        return get("/account/balance", Map.class);
    }

    public Map<String, Object> stockNews(String code, int limit) {
        return getNoRetry("/news/stock/" + code + "?limit=" + limit, Map.class);
    }

    public Map<String, Object> clsTelegraph(String symbol, int limit) {
        String s = (symbol == null || symbol.isBlank()) ? "全部" : symbol;
        return getNoRetry("/news/cls?symbol=" + java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)
                + "&limit=" + limit, Map.class);
    }

    public Map<String, Object> stockSearch(String q, int limit) {
        return getNoRetry("/quote/search?q="
                + java.net.URLEncoder.encode(q == null ? "" : q, java.nio.charset.StandardCharsets.UTF_8)
                + "&limit=" + limit, Map.class);
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

    /** 不重试的 GET：单只数据失败可接受、retry 没价值的场景（如回测 bars 批量预拉）。 */
    private <T> T getNoRetry(String path, Class<T> type) {
        try {
            return client.get().uri(path).retrieve().body(type);
        } catch (RestClientException e) {
            throw new GatewayException("python gateway call failed: " + path, e);
        }
    }

    public static class GatewayException extends RuntimeException {
        public GatewayException(String msg, Throwable cause) { super(msg, cause); }
    }
}
