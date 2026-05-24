package com.aitrade.trade;

import com.aitrade.auth.CurrentUser;
import com.aitrade.trade.dto.InstantiateTemplateReq;
import com.aitrade.trade.dto.StrategyTemplateVO;
import com.aitrade.trade.dto.TraderVO;
import com.aitrade.trade.strategy.StrategyTemplateService;
import jakarta.validation.Valid;
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
@RequestMapping("/api/strategy-templates")
@RequiredArgsConstructor
public class StrategyTemplateController {

    private final StrategyTemplateService service;

    @GetMapping
    public List<StrategyTemplateVO> list() {
        return service.listOfficial();
    }

    @GetMapping("/{id}")
    public StrategyTemplateVO get(@PathVariable Long id) {
        return service.getOne(id);
    }

    /** 一键克隆模板为当前用户的新 trader。LLM 模板需在请求体里带 llmApiKey。 */
    @PostMapping("/{id}/instantiate")
    public ResponseEntity<TraderVO> instantiate(@PathVariable Long id,
                                                @Valid @RequestBody InstantiateTemplateReq req,
                                                @CurrentUser Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.instantiate(userId, id, req));
    }
}
