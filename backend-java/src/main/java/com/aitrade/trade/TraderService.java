package com.aitrade.trade;

import com.aitrade.common.ApiException;
import com.aitrade.entity.AiTrader;
import com.aitrade.entity.Position;
import com.aitrade.mapper.AiTraderMapper;
import com.aitrade.mapper.PositionMapper;
import com.aitrade.trade.dto.CreateTraderReq;
import com.aitrade.trade.dto.PositionVO;
import com.aitrade.trade.dto.TraderVO;
import com.aitrade.trade.dto.UpdateTraderReq;
import com.aitrade.trade.strategy.indicator.IndicatorStrategyExecutor;
import com.aitrade.trade.strategy.script.ScriptEngineFactory;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TraderService {

    public static final BigDecimal DEFAULT_INITIAL_BALANCE = new BigDecimal("1000000");
    public static final Set<String> STRATEGY_TYPES = Set.of("MANUAL", "MA", "LLM", "INDICATOR", "SCRIPT");

    private final AiTraderMapper aiTraderMapper;
    private final PositionMapper positionMapper;
    private final com.aitrade.mapper.TradeOrderMapper tradeOrderMapper;
    private final ScriptEngineFactory scriptEngineFactory;

    public AiTrader createDefault(Long userId) {
        AiTrader t = new AiTrader();
        t.setUserId(userId);
        t.setName("默认交易员");
        t.setStrategyType("MANUAL");
        t.setInitialBalance(DEFAULT_INITIAL_BALANCE);
        t.setBalance(DEFAULT_INITIAL_BALANCE);
        t.setFrozenBalance(BigDecimal.ZERO);
        t.setTotalProfit(BigDecimal.ZERO);
        t.setEnabled(1);
        t.setDeleted(0);
        LocalDateTime now = LocalDateTime.now();
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        aiTraderMapper.insert(t);
        return t;
    }

    @Transactional
    public TraderVO create(CreateTraderReq req, Long userId) {
        String type = normalizeStrategyType(req.getStrategyType());
        BigDecimal initial = req.getInitialBalance() == null
                ? DEFAULT_INITIAL_BALANCE
                : req.getInitialBalance().setScale(2, RoundingMode.HALF_UP);
        AiTrader t = new AiTrader();
        t.setUserId(userId);
        t.setName(req.getName().trim());
        t.setStrategyType(type);
        t.setInitialBalance(initial);
        t.setBalance(initial);
        t.setFrozenBalance(BigDecimal.ZERO);
        t.setTotalProfit(BigDecimal.ZERO);
        t.setEnabled(Boolean.FALSE.equals(req.getEnabled()) ? 0 : 1);
        t.setDeleted(0);
        t.setMaShort(req.getMaShort() != null ? req.getMaShort() : 5);
        t.setMaLong(req.getMaLong() != null ? req.getMaLong() : 20);
        if ("MA".equals(type)) validateMaParams(t.getMaShort(), t.getMaLong());
        if ("LLM".equals(type)) {
            t.setLlmBaseUrl(blankToNull(req.getLlmBaseUrl()));
            t.setLlmApiKey(blankToNull(req.getLlmApiKey()));
            t.setLlmModel(blankToNull(req.getLlmModel()));
            t.setLlmPrompt(blankToNull(req.getLlmPrompt()));
        }
        if ("INDICATOR".equals(type)) {
            String cfg = blankToNull(req.getIndicatorConfigJson());
            validateIndicatorConfig(cfg);
            t.setIndicatorConfigJson(cfg);
        }
        if ("SCRIPT".equals(type)) {
            String code = blankToNull(req.getScriptCode());
            validateScriptCode(code);
            t.setScriptCode(code);
        }
        t.setPoolName(normalizePoolName(req.getPoolName()));
        LocalDateTime now = LocalDateTime.now();
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        aiTraderMapper.insert(t);
        return toVO(t);
    }

    @Transactional
    public TraderVO update(Long traderId, UpdateTraderReq req, Long userId) {
        AiTrader t = getOwned(traderId, userId);
        if (req.getName() != null && !req.getName().isBlank()) t.setName(req.getName().trim());
        if (req.getStrategyType() != null) t.setStrategyType(normalizeStrategyType(req.getStrategyType()));
        if (req.getEnabled() != null) t.setEnabled(req.getEnabled() ? 1 : 0);
        if (req.getMaShort() != null) t.setMaShort(req.getMaShort());
        if (req.getMaLong() != null) t.setMaLong(req.getMaLong());
        if ("MA".equals(t.getStrategyType())) validateMaParams(t.getMaShort(), t.getMaLong());

        if (req.getLlmBaseUrl() != null) t.setLlmBaseUrl(blankToNull(req.getLlmBaseUrl()));
        if (req.getLlmModel() != null) t.setLlmModel(blankToNull(req.getLlmModel()));
        if (req.getLlmPrompt() != null) t.setLlmPrompt(blankToNull(req.getLlmPrompt()));
        // 仅当传非空字符串才更新 key；null 或空串表示保留旧值
        if (req.getLlmApiKey() != null && !req.getLlmApiKey().isEmpty()) {
            t.setLlmApiKey(req.getLlmApiKey());
        }
        if (req.getIndicatorConfigJson() != null) {
            String cfg = blankToNull(req.getIndicatorConfigJson());
            if (cfg != null) validateIndicatorConfig(cfg);
            t.setIndicatorConfigJson(cfg);
        }
        if (req.getScriptCode() != null) {
            String code = blankToNull(req.getScriptCode());
            if (code != null) validateScriptCode(code);
            t.setScriptCode(code);
        }
        if ("INDICATOR".equals(t.getStrategyType())) {
            validateIndicatorConfig(t.getIndicatorConfigJson());
        }
        if ("SCRIPT".equals(t.getStrategyType())) {
            validateScriptCode(t.getScriptCode());
        }

        // poolName：null 不动；空串清空（回到默认 watchlist）；非空字符串归一化
        if (req.getPoolName() != null) {
            t.setPoolName(normalizePoolName(req.getPoolName()));
        }

        // 修改初始资产：必须清仓且无 PENDING 单，否则破坏资金守恒
        if (req.getInitialBalance() != null) {
            BigDecimal newInitial = req.getInitialBalance().setScale(2, RoundingMode.HALF_UP);
            BigDecimal currentInitial = nz(t.getInitialBalance());
            if (currentInitial.compareTo(newInitial) != 0) {
                ensureFlat(traderId);
                t.setInitialBalance(newInitial);
                t.setBalance(newInitial);
                t.setFrozenBalance(BigDecimal.ZERO);
                t.setTotalProfit(BigDecimal.ZERO);
            }
        }

        t.setUpdatedAt(LocalDateTime.now());
        aiTraderMapper.updateById(t);
        return toVO(t);
    }

    /** 校验 trader 处于"无持仓且无 PENDING 单"的状态。否则抛 400。 */
    private void ensureFlat(Long traderId) {
        Long posCount = positionMapper.selectCount(new QueryWrapper<Position>()
                .eq("trader_id", traderId).gt("amount", 0));
        if (posCount != null && posCount > 0) {
            throw ApiException.badRequest("修改初始资产前需先重置 trader（当前持仓非空）");
        }
        Long pendCount = tradeOrderMapper.selectCount(new QueryWrapper<com.aitrade.entity.TradeOrder>()
                .eq("trader_id", traderId).eq("status", "PENDING"));
        if (pendCount != null && pendCount > 0) {
            throw ApiException.badRequest("修改初始资产前需先重置 trader（存在 PENDING 订单）");
        }
    }

    @Transactional
    public void softDelete(Long traderId, Long userId) {
        AiTrader t = getOwned(traderId, userId);
        t.setDeleted(1);
        t.setEnabled(0);
        t.setUpdatedAt(LocalDateTime.now());
        aiTraderMapper.updateById(t);
    }

    /** 清空持仓、订单流水保留，资金归位到 trader 的初始资产。调用方需先撤掉所有 PENDING 单。 */
    @Transactional
    public TraderVO reset(Long traderId, Long userId) {
        AiTrader t = getOwned(traderId, userId);
        positionMapper.delete(new QueryWrapper<Position>().eq("trader_id", traderId));
        BigDecimal initial = nz(t.getInitialBalance());
        if (initial.signum() <= 0) initial = DEFAULT_INITIAL_BALANCE;
        t.setBalance(initial);
        t.setFrozenBalance(BigDecimal.ZERO);
        t.setTotalProfit(BigDecimal.ZERO);
        t.setUpdatedAt(LocalDateTime.now());
        aiTraderMapper.updateById(t);
        return toVO(t);
    }

    public List<TraderVO> listMy(Long userId) {
        List<AiTrader> traders = aiTraderMapper.selectList(
                new QueryWrapper<AiTrader>()
                        .eq("user_id", userId)
                        .eq("deleted", 0)
                        .orderByAsc("id"));
        List<TraderVO> result = new ArrayList<>(traders.size());
        for (AiTrader t : traders) result.add(toVO(t));
        return result;
    }

    /** 拿到 trader 并校验属主，否则抛 403/404；过滤已软删。 */
    public AiTrader getOwned(Long traderId, Long userId) {
        AiTrader t = aiTraderMapper.selectById(traderId);
        if (t == null || (t.getDeleted() != null && t.getDeleted() == 1)) {
            throw new ApiException(404, "trader 不存在");
        }
        if (!Objects.equals(t.getUserId(), userId)) throw new ApiException(403, "无权访问该 trader");
        return t;
    }

    public TraderVO toVO(AiTrader t) {
        BigDecimal marketValue = sumMarketValue(t.getId());
        BigDecimal balance = nz(t.getBalance());
        BigDecimal frozen = nz(t.getFrozenBalance());
        BigDecimal initial = nz(t.getInitialBalance());
        if (initial.signum() <= 0) initial = DEFAULT_INITIAL_BALANCE;
        BigDecimal totalAsset = balance.add(frozen).add(marketValue);
        BigDecimal profit = totalAsset.subtract(initial);
        BigDecimal profitPct = profit.multiply(new BigDecimal("100"))
                .divide(initial, 4, RoundingMode.HALF_UP);

        TraderVO vo = new TraderVO();
        vo.setId(t.getId());
        vo.setName(t.getName());
        vo.setStrategyType(t.getStrategyType());
        vo.setEnabled(t.getEnabled() == null || t.getEnabled() == 1);
        vo.setInitialBalance(initial);
        vo.setBalance(balance);
        vo.setFrozenBalance(frozen);
        vo.setMarketValue(marketValue);
        vo.setTotalAsset(totalAsset);
        vo.setTotalProfit(profit);
        vo.setProfitPct(profitPct);
        vo.setMaShort(t.getMaShort());
        vo.setMaLong(t.getMaLong());
        vo.setLlmBaseUrl(t.getLlmBaseUrl());
        vo.setLlmModel(t.getLlmModel());
        vo.setLlmPrompt(t.getLlmPrompt());
        vo.setLlmApiKeySet(t.getLlmApiKey() != null && !t.getLlmApiKey().isEmpty());
        vo.setIndicatorConfigJson(t.getIndicatorConfigJson());
        vo.setScriptCode(t.getScriptCode());
        vo.setPoolName(t.getPoolName());
        vo.setTemplateId(t.getTemplateId());
        return vo;
    }

    public List<PositionVO> listPositions(Long traderId) {
        List<Position> ps = positionMapper.selectList(
                new QueryWrapper<Position>().eq("trader_id", traderId).gt("amount", 0).orderByDesc("updated_at"));
        List<PositionVO> result = new ArrayList<>(ps.size());
        for (Position p : ps) {
            PositionVO vo = new PositionVO();
            vo.setId(p.getId());
            vo.setStockCode(p.getStockCode());
            vo.setAmount(p.getAmount());
            vo.setFrozenAmount(nzi(p.getFrozenAmount()));
            vo.setCostPrice(nz(p.getCostPrice()));
            vo.setCurrentPrice(nz(p.getCurrentPrice()));
            BigDecimal mv = vo.getCurrentPrice().multiply(BigDecimal.valueOf(p.getAmount()));
            vo.setMarketValue(mv);
            BigDecimal cost = vo.getCostPrice().multiply(BigDecimal.valueOf(p.getAmount()));
            if (cost.signum() > 0) {
                vo.setProfitPct(mv.subtract(cost).multiply(new BigDecimal("100"))
                        .divide(cost, 4, RoundingMode.HALF_UP));
            } else {
                vo.setProfitPct(BigDecimal.ZERO);
            }
            vo.setUpdatedAt(p.getUpdatedAt());
            result.add(vo);
        }
        return result;
    }

    private BigDecimal sumMarketValue(Long traderId) {
        List<Position> ps = positionMapper.selectList(
                new QueryWrapper<Position>().eq("trader_id", traderId).gt("amount", 0));
        BigDecimal sum = BigDecimal.ZERO;
        for (Position p : ps) {
            BigDecimal price = nz(p.getCurrentPrice());
            if (price.signum() == 0) price = nz(p.getCostPrice());
            sum = sum.add(price.multiply(BigDecimal.valueOf(p.getAmount())));
        }
        return sum;
    }

    private String normalizeStrategyType(String raw) {
        if (raw == null || raw.isBlank()) return "MANUAL";
        String up = raw.trim().toUpperCase();
        if (!STRATEGY_TYPES.contains(up)) throw ApiException.badRequest("不支持的策略类型: " + raw);
        return up;
    }

    private void validateMaParams(Integer maShort, Integer maLong) {
        if (maShort == null || maShort < 2 || maShort > 60) throw ApiException.badRequest("ma_short 须在 2-60");
        if (maLong == null || maLong < 2 || maLong > 250) throw ApiException.badRequest("ma_long 须在 2-250");
        if (maShort >= maLong) throw ApiException.badRequest("ma_short 必须小于 ma_long");
    }

    private void validateIndicatorConfig(String json) {
        if (json == null || json.isBlank()) {
            throw ApiException.badRequest("INDICATOR 策略需要填写指标配置");
        }
        try {
            IndicatorStrategyExecutor.parseAndValidate(json);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 校验脚本能编译，且暴露了 decide 函数。运行期超时由 ScriptEngineFactory 兜底，这里只验语法。 */
    private void validateScriptCode(String code) {
        if (code == null || code.isBlank()) {
            throw ApiException.badRequest("SCRIPT 策略需要填写脚本源码");
        }
        if (!code.contains("decide")) {
            throw ApiException.badRequest("脚本必须定义 function decide()");
        }
        try {
            scriptEngineFactory.compile(code);
        } catch (Exception e) {
            throw ApiException.badRequest("脚本编译失败：" + e.getMessage());
        }
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static Integer nzi(Integer v) { return v == null ? 0 : v; }

    /** poolName 归一化：null/空串 → null；其它做合法性校验（与 gateway pool_registry 一致的 slug 规则）。 */
    private static String normalizePoolName(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        String lower = trimmed.toLowerCase();
        if (!lower.matches("^[a-z0-9][a-z0-9_-]{0,31}$")) {
            throw ApiException.badRequest("poolName 必须是 1-32 位小写字母/数字/下划线/横线，首位字母数字");
        }
        return lower;
    }

    /** 用于策略调度循环：返回所有 enabled=1, deleted=0, strategy_type IN (MA, LLM, INDICATOR, SCRIPT) 的 trader。 */
    public List<AiTrader> listForStrategy() {
        return aiTraderMapper.selectList(new QueryWrapper<AiTrader>()
                .eq("enabled", 1)
                .eq("deleted", 0)
                .in("strategy_type", "MA", "LLM", "INDICATOR", "SCRIPT"));
    }

    /** dashboard 用：返回当前用户所有 LLM trader（含 disabled，但排除软删）。 */
    public List<AiTrader> listMyLlmTraders(Long userId) {
        return aiTraderMapper.selectList(new QueryWrapper<AiTrader>()
                .eq("user_id", userId)
                .eq("strategy_type", "LLM")
                .eq("deleted", 0)
                .orderByAsc("id"));
    }

    /** 清空全部 LLM 活动用：返回当前用户的所有 LLM trader id。 */
    public List<Long> listMyLlmTraderIds(Long userId) {
        List<AiTrader> traders = aiTraderMapper.selectList(new QueryWrapper<AiTrader>()
                .eq("user_id", userId)
                .eq("strategy_type", "LLM")
                .eq("deleted", 0)
                .select("id"));
        List<Long> ids = new ArrayList<>(traders.size());
        for (AiTrader t : traders) ids.add(t.getId());
        return ids;
    }

    /** 用于排行榜：返回所有 deleted=0 的 trader。 */
    public List<AiTrader> listAllActive() {
        return aiTraderMapper.selectList(new QueryWrapper<AiTrader>().eq("deleted", 0));
    }

    /** 用于 MatchEngine.revalue：返回所有非软删的 trader id。 */
    public Set<Long> idsForRevalue() {
        Set<Long> ids = new HashSet<>();
        for (AiTrader t : aiTraderMapper.selectList(new QueryWrapper<AiTrader>().eq("deleted", 0).select("id"))) {
            ids.add(t.getId());
        }
        return ids;
    }
}
