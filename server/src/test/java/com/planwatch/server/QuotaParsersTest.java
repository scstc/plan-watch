package com.planwatch.server;

import tools.jackson.databind.ObjectMapper;
import com.planwatch.server.model.QuotaTier;
import com.planwatch.server.model.WindowKind;
import com.planwatch.server.service.QuotaQueryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 供应商解析规则的关键用例，与桌面端 Rust 单测（src-tauri/src/quota/）同源。
 * 每个用例对应 docs/providers/*.md 里的一条实测坑。
 */
class QuotaParsersTest {

    private final QuotaQueryService service = new QuotaQueryService();
    private final ObjectMapper mapper = new ObjectMapper();

    private List<QuotaTier> zhipu(String json) throws Exception {
        return service.parseZhipuTiers(mapper.readTree(json).get("data"));
    }

    private List<QuotaTier> minimax(String json) throws Exception {
        return service.parseMinimaxTiers(mapper.readTree(json));
    }

    // ── 智谱 ──

    @Test
    void zhipuUnitFieldOverridesResetOrderWhenWeeklyResetsSooner() throws Exception {
        // 周期末尾周桶比 5h 桶更早重置，按 reset 排序必然标反（issue #3036）
        var tiers = zhipu("""
                {"data":{"limits":[
                  {"type":"TOKENS_LIMIT","unit":6,"number":7,"percentage":42.0,"nextResetTime":1000003600000},
                  {"type":"TOKENS_LIMIT","unit":3,"number":5,"percentage":1.0,"nextResetTime":1000018000000}
                ]}}
                """);
        assertThat(tiers).hasSize(2);
        assertThat(tiers.get(0).window()).isEqualTo(WindowKind.FIVE_HOUR);
        assertThat(tiers.get(0).usedPercent()).isEqualTo(1.0);
        assertThat(tiers.get(1).window()).isEqualTo(WindowKind.WEEKLY);
        assertThat(tiers.get(1).usedPercent()).isEqualTo(42.0);
    }

    @Test
    void zhipuMissingUnitFallsBackToResetOrder() throws Exception {
        // 无 unit：无 reset 的优先归 5h（0% 桶常无 reset），其余按 reset 升序
        var tiers = zhipu("""
                {"data":{"limits":[
                  {"type":"TOKENS_LIMIT","percentage":25.0,"nextResetTime":2000000000000},
                  {"type":"TOKENS_LIMIT","percentage":0.0}
                ]}}
                """);
        assertThat(tiers).hasSize(2);
        assertThat(tiers.get(0).window()).isEqualTo(WindowKind.FIVE_HOUR);
        assertThat(tiers.get(0).usedPercent()).isEqualTo(0.0);
        assertThat(tiers.get(0).resetsAt()).isNull();
        assertThat(tiers.get(1).window()).isEqualTo(WindowKind.WEEKLY);
        assertThat(tiers.get(1).usedPercent()).isEqualTo(25.0);
    }

    @Test
    void zhipuCreditLimitsCarryAbsoluteValues() throws Exception {
        // 积分套餐：usage=总额度，currentValue=已用，remaining=剩余
        var tiers = zhipu("""
                {"data":{"limits":[
                  {"type":"CREDIT_LIMIT","unit":3,"usage":2000,"currentValue":0,"remaining":2000,"percentage":0},
                  {"type":"CREDIT_LIMIT","unit":6,"usage":10000,"currentValue":4788,"remaining":5211,
                   "percentage":47,"nextResetTime":1787919529998}
                ]}}
                """);
        assertThat(tiers).hasSize(2);
        assertThat(tiers.get(1).total()).isEqualTo(10000.0);
        assertThat(tiers.get(1).used()).isEqualTo(4788.0);
        assertThat(tiers.get(1).resetsAt()).isNotBlank();
    }

    @Test
    void zhipuOldPlanSingleTierFallsBackToFiveHour() throws Exception {
        var tiers = zhipu("""
                {"data":{"limits":[{"type":"TOKENS_LIMIT","percentage":2.0,"nextResetTime":1774967594803}]}}
                """);
        assertThat(tiers).hasSize(1);
        assertThat(tiers.get(0).window()).isEqualTo(WindowKind.FIVE_HOUR);
    }

    @Test
    void zhipuNonTokenLimitsIgnored() throws Exception {
        var tiers = zhipu("""
                {"data":{"limits":[{"type":"TIME_LIMIT","percentage":5.0}]}}
                """);
        assertThat(tiers).isEmpty();
    }

    // ── MiniMax ──

    @Test
    void minimaxRemainingPercentInvertedToUsed() throws Exception {
        // 语义坑：remaining_percent 是"剩余"，取反才是已用
        var tiers = minimax("""
                {"model_remains":[
                  {"model_name":"general",
                   "current_interval_remaining_percent":98.0,
                   "current_weekly_remaining_percent":95.0,
                   "current_weekly_status":1,
                   "end_time":1780329600000,"weekly_end_time":1780848000000},
                  {"model_name":"video","current_interval_remaining_percent":100.0}
                ]}
                """);
        assertThat(tiers).hasSize(2);
        assertThat(tiers.get(0).usedPercent()).isEqualTo(2.0);
        assertThat(tiers.get(1).usedPercent()).isEqualTo(5.0);
        assertThat(tiers.get(0).unlimited()).isFalse();
    }

    @Test
    void minimaxWeeklyStatus3IsUnlimited() throws Exception {
        // 无周限额套餐：产出 unlimited 周 tier（展示 ∞），绝不把恒 100% 剩余当真实数据
        var tiers = minimax("""
                {"model_remains":[
                  {"model_name":"general",
                   "current_interval_remaining_percent":99,
                   "current_interval_status":1,
                   "current_weekly_status":3,
                   "current_weekly_remaining_percent":100,
                   "end_time":1780365600000}
                ]}
                """);
        assertThat(tiers).hasSize(2);
        assertThat(tiers.get(0).usedPercent()).isEqualTo(1.0);
        assertThat(tiers.get(1).unlimited()).isTrue();
        assertThat(tiers.get(1).usedPercent()).isEqualTo(0.0);
        assertThat(tiers.get(1).resetsAt()).isNull();
    }

    @Test
    void minimaxSkipsVideoModel() throws Exception {
        var tiers = minimax("""
                {"model_remains":[
                  {"model_name":"video","current_interval_remaining_percent":50.0,
                   "current_weekly_status":1,"current_weekly_remaining_percent":50.0},
                  {"model_name":"general","current_interval_remaining_percent":80.0,
                   "current_weekly_status":2}
                ]}
                """);
        // general 的 5h = 20%；weekly_status=2 → unlimited
        assertThat(tiers).hasSize(2);
        assertThat(tiers.get(0).usedPercent()).isEqualTo(20.0);
        assertThat(tiers.get(1).unlimited()).isTrue();
    }

    @Test
    void minimaxMissingGeneralReturnsEmpty() throws Exception {
        assertThat(minimax("{\"model_remains\":[{\"model_name\":\"video\"}]}")).isEmpty();
        assertThat(minimax("{}")).isEmpty();
    }
}

