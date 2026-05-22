package com.aitrade.controller;

import com.aitrade.gateway.PythonGatewayClient;
import com.aitrade.gateway.dto.SnapshotResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/quote")
public class QuoteController {

    private final PythonGatewayClient gateway;

    public QuoteController(PythonGatewayClient gateway) {
        this.gateway = gateway;
    }

    @GetMapping("/gateway-health")
    public Map<String, Object> gatewayHealth() {
        return gateway.health();
    }

    @GetMapping("/snapshot/{codes}")
    public SnapshotResponse snapshot(@PathVariable String codes) {
        return gateway.snapshot(codes);
    }

    @GetMapping("/bars/{code}")
    public Map<String, Object> bars(
            @PathVariable String code,
            @RequestParam(defaultValue = "9") int frequency,
            @RequestParam(defaultValue = "240") int count) {
        return gateway.bars(code, frequency, count);
    }

    @GetMapping("/watchlist")
    public Map<String, Object> watchlist() {
        return gateway.watchlist();
    }

    @GetMapping("/watchlist/reload")
    public Map<String, Object> watchlistReload() {
        return gateway.watchlistReload();
    }
}
