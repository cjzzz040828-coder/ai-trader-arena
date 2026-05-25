package com.aitrade.trade.strategy;

import com.aitrade.common.ApiException;
import com.aitrade.entity.AiTrader;
import com.aitrade.entity.StrategyTemplate;
import com.aitrade.mapper.AiTraderMapper;
import com.aitrade.mapper.StrategyTemplateMapper;
import com.aitrade.trade.TraderService;
import com.aitrade.trade.dto.CreateTraderReq;
import com.aitrade.trade.dto.InstantiateTemplateReq;
import com.aitrade.trade.dto.StrategyTemplateVO;
import com.aitrade.trade.dto.TraderVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略模板库服务：列表 / 详情 / 一键实例化（克隆到当前用户的 trader）。
 * 注意：模板 default_params_json 严禁存 api_key。LLM 模板实例化时必须由前端表单提供。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyTemplateService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BigDecimal DEFAULT_INITIAL = new BigDecimal("1000000");

    private final StrategyTemplateMapper templateMapper;
    private final TraderService traderService;
    private final AiTraderMapper aiTraderMapper;

    public List<StrategyTemplateVO> listOfficial() {
        List<StrategyTemplate> templates = templateMapper.selectList(new QueryWrapper<StrategyTemplate>()
                .eq("is_official", 1)
                .orderByAsc("sort_order")
                .orderByAsc("id"));
        if (templates.isEmpty()) return Collections.emptyList();

        Map<Long, Map<String, Object>> metricsByTpl = new HashMap<>();
        try {
            for (Map<String, Object> row : templateMapper.selectAllMetrics()) {
                Object idObj = row.get("template_id");
                if (idObj instanceof Number n) metricsByTpl.put(n.longValue(), row);
            }
        } catch (Exception e) {
            log.warn("[strategy-template] selectAllMetrics failed: {}", e.getMessage());
        }

        List<StrategyTemplateVO> result = new ArrayList<>(templates.size());
        for (StrategyTemplate t : templates) result.add(toVO(t, metricsByTpl.get(t.getId())));
        return result;
    }

    public StrategyTemplateVO getOne(Long id) {
        StrategyTemplate t = templateMapper.selectById(id);
        if (t == null) throw new ApiException(404, "策略模板不存在");
        Map<String, Object> metrics = null;
        try {
            metrics = templateMapper.selectMetrics(id);
        } catch (Exception e) {
            log.warn("[strategy-template] selectMetrics({}) failed: {}", id, e.getMessage());
        }
        return toVO(t, metrics);
    }

    /**
     * 把模板克隆为当前用户的一个新 trader。
     * MA 模板：仅校验 traderName。
     * LLM 模板：必须提供 llmApiKey（不入模板表，只入新 trader 行）。
     * 复用 TraderService.create 的校验链路，然后 UPDATE template_id 记录出身。
     */
    @Transactional
    public TraderVO instantiate(Long userId, Long templateId, InstantiateTemplateReq req) {
        StrategyTemplate t = templateMapper.selectById(templateId);
        if (t == null) throw new ApiException(404, "策略模板不存在");
        if (req == null || req.getTraderName() == null || req.getTraderName().isBlank()) {
            throw ApiException.badRequest("traderName 不能为空");
        }

        Map<String, Object> params = parseParams(t.getDefaultParamsJson());

        CreateTraderReq creq = new CreateTraderReq();
        creq.setName(req.getTraderName().trim());
        creq.setStrategyType(t.getStrategyType());
        creq.setEnabled(false); // 一律以停用态创建，避免用户未确认前就被调度
        creq.setInitialBalance(DEFAULT_INITIAL);

        if ("MA".equals(t.getStrategyType())) {
            creq.setMaShort(intParam(params, "maShort", 5));
            creq.setMaLong(intParam(params, "maLong", 20));
        } else if ("LLM".equals(t.getStrategyType())) {
            if (req.getLlmApiKey() == null || req.getLlmApiKey().isBlank()) {
                throw ApiException.badRequest("LLM 模板实例化必须填写 api_key");
            }
            creq.setLlmBaseUrl(strParam(params, "llmBaseUrl"));
            creq.setLlmModel(strParam(params, "llmModel"));
            creq.setLlmPrompt(strParam(params, "llmPrompt"));
            creq.setLlmApiKey(req.getLlmApiKey().trim());
        } else if ("CTA".equals(t.getStrategyType())) {
            // CTA 模板 default_params_json 直接就是完整的 CtaConfig（entry/stopLoss/exitOnReverseSignal），
            // 实例化时原样回传给 TraderService.create 走 validateCtaConfig 链路。
            try {
                creq.setCtaConfigJson(MAPPER.writeValueAsString(params));
            } catch (Exception e) {
                throw ApiException.badRequest("CTA 模板参数序列化失败: " + e.getMessage());
            }
        } else {
            throw ApiException.badRequest("不支持的模板策略类型: " + t.getStrategyType());
        }

        TraderVO vo = traderService.create(creq, userId);
        // 回填 template_id：单独 UPDATE 避免改动 TraderService.create 签名
        AiTrader updated = new AiTrader();
        updated.setId(vo.getId());
        updated.setTemplateId(t.getId());
        aiTraderMapper.updateById(updated);
        return vo;
    }

    private StrategyTemplateVO toVO(StrategyTemplate t, Map<String, Object> metricsRow) {
        StrategyTemplateVO vo = new StrategyTemplateVO();
        vo.setId(t.getId());
        vo.setCode(t.getCode());
        vo.setName(t.getName());
        vo.setDescription(t.getDescription());
        vo.setStrategyType(t.getStrategyType());
        vo.setParams(parseParams(t.getDefaultParamsJson()));
        vo.setTags(splitTags(t.getTags()));
        vo.setIsOfficial(t.getIsOfficial() != null && t.getIsOfficial() == 1);
        vo.setSortOrder(t.getSortOrder());

        StrategyTemplateVO.Metrics m = new StrategyTemplateVO.Metrics();
        if (metricsRow != null) {
            m.setInstanceCount(intOf(metricsRow.get("instance_count")));
            m.setAvgReturnPct(round(doubleOf(metricsRow.get("avg_return_pct")), 2));
            m.setTotalTrades(intOf(metricsRow.get("total_trades")));
            m.setWinRate(round(doubleOf(metricsRow.get("win_rate")), 2));
        } else {
            m.setInstanceCount(0);
            m.setAvgReturnPct(0.0);
            m.setTotalTrades(0);
            m.setWinRate(0.0);
        }
        vo.setMetrics(m);
        return vo;
    }

    private static Map<String, Object> parseParams(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Object> raw = MAPPER.readValue(json, Map.class);
            // 兜底：永远不把 api_key 透传给前端
            raw.remove("llmApiKey");
            raw.remove("apiKey");
            return raw;
        } catch (Exception e) {
            log.warn("[strategy-template] parseParams failed: {} / {}", e.getMessage(), json);
            return new LinkedHashMap<>();
        }
    }

    private static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) return Collections.emptyList();
        return Arrays.stream(tags.split("[,，]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static Integer intParam(Map<String, Object> p, String key, int def) {
        Object v = p.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); } catch (Exception ignored) {}
        }
        return def;
    }

    private static String strParam(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer intOf(Object v) {
        if (v instanceof Number n) return n.intValue();
        return 0;
    }

    private static Double doubleOf(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    private static Double round(Double v, int digits) {
        if (v == null) return 0.0;
        double scale = Math.pow(10, digits);
        return Math.round(v * scale) / scale;
    }
}
