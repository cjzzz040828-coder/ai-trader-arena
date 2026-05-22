package com.aitrade.backtest;

import com.aitrade.auth.CurrentUser;
import com.aitrade.backtest.dto.BacktestRequest;
import com.aitrade.backtest.dto.BacktestTaskVO;
import com.aitrade.backtest.dto.BacktestTradeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestService service;

    @PostMapping("/tasks")
    public ResponseEntity<BacktestTaskVO> create(@RequestBody BacktestRequest req,
                                                 @CurrentUser Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req, userId));
    }

    @GetMapping("/tasks")
    public List<BacktestTaskVO> list(@CurrentUser Long userId) {
        return service.listByUser(userId);
    }

    @GetMapping("/tasks/{id}")
    public BacktestTaskVO get(@PathVariable Long id, @CurrentUser Long userId) {
        return service.get(id, userId);
    }

    @GetMapping("/tasks/{id}/trades")
    public List<BacktestTradeVO> trades(@PathVariable Long id, @CurrentUser Long userId) {
        return service.trades(id, userId);
    }
}
