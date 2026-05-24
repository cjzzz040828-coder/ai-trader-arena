package com.aitrade.controller;

import com.aitrade.gateway.PythonGatewayClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 把 gateway 的 /pool 接口透传给前端。前端只能透过 Java 后端访问（gateway 受 X-Gateway-Token 保护）。
 * 这里不做权限校验：池子是用户级共享配置，所有登录用户都能查看/编辑。
 */
@RestController
@RequestMapping("/api/pool")
public class PoolController {

    private final PythonGatewayClient gateway;

    public PoolController(PythonGatewayClient gateway) {
        this.gateway = gateway;
    }

    @GetMapping("")
    public Map<String, Object> list() {
        return gateway.poolList();
    }

    @PostMapping("")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return gateway.poolCreate(body);
    }

    @PutMapping("/{name}")
    public Map<String, Object> update(@PathVariable String name, @RequestBody Map<String, Object> body) {
        return gateway.poolUpdate(name, body);
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable String name) {
        return gateway.poolDelete(name);
    }

    @GetMapping("/{name}/status")
    public Map<String, Object> status(@PathVariable String name) {
        return gateway.poolStatus(name);
    }

    @PostMapping("/{name}/rebuild")
    public Map<String, Object> rebuild(@PathVariable String name) {
        return gateway.poolRebuild(name);
    }

    @GetMapping("/{name}/history")
    public Map<String, Object> historyList(@PathVariable String name) {
        return gateway.poolHistoryList(name);
    }

    @GetMapping("/{name}/history/{date}")
    public Map<String, Object> historyOne(@PathVariable String name, @PathVariable String date) {
        return gateway.poolHistoryOne(name, date);
    }
}
