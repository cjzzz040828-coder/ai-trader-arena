package com.aitrade.controller;

import com.aitrade.gateway.PythonGatewayClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final PythonGatewayClient gateway;

    public NewsController(PythonGatewayClient gateway) {
        this.gateway = gateway;
    }

    @GetMapping("/stock/{code}")
    public Map<String, Object> stockNews(
            @PathVariable String code,
            @RequestParam(defaultValue = "10") int limit) {
        return gateway.stockNews(code, limit);
    }

    @GetMapping("/cls")
    public Map<String, Object> clsTelegraph(
            @RequestParam(defaultValue = "全部") String symbol,
            @RequestParam(defaultValue = "30") int limit) {
        return gateway.clsTelegraph(symbol, limit);
    }
}
