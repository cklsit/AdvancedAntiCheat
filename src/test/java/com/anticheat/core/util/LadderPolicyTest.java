package com.anticheat.core.util;

import com.anticheat.core.db.LadderStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 惩罚阶梯 / 白名单的**纯解析与选档**测试。
 *
 * <h3>为什么这些用例值得单独写</h3>
 * 这一层的事故全是"静默生效"的：{@code "7d"} 被当成 7 毫秒（封禁秒解）、
 * 分档比较写成 {@code >} 而不是 {@code >=}（永远差一档）、
 * 排序假设失效导致选错档、白名单名字大小写不匹配（加白了却不生效）。
 * 它们都不抛异常，只会在线上表现为"处罚没生效"或"白名单没生效"。
 *
 * <p>因此这里的断言都在钉**边界**：等于 min-vl 的那一刻算不算命中、
 * 裸数字该不该被接受、乱序输入能不能选出正确档位。</p>
 */
class LadderPolicyTest {

    // ------------------------------------------------------------------ 时长解析

    @Test
    @DisplayName("时长：perm/空/0 是永久；带单位才接受；裸数字与写错必须报错而不是猜")
    void parseDuration() {
        assertEquals(LadderPolicy.PERMANENT_MILLIS, LadderPolicy.parseDurationMillis(null));
        assertEquals(LadderPolicy.PERMANENT_MILLIS, LadderPolicy.parseDurationMillis(""));
        assertEquals(LadderPolicy.PERMANENT_MILLIS, LadderPolicy.parseDurationMillis("perm"));
        assertEquals(LadderPolicy.PERMANENT_MILLIS, LadderPolicy.parseDurationMillis("Permanent"));
        assertEquals(LadderPolicy.PERMANENT_MILLIS, LadderPolicy.parseDurationMillis("0"));

        assertEquals(30_000L, LadderPolicy.parseDurationMillis("30s"));
        assertEquals(600_000L, LadderPolicy.parseDurationMillis("10m"));
        assertEquals(7_200_000L, LadderPolicy.parseDurationMillis("2h"));
        assertEquals(604_800_000L, LadderPolicy.parseDurationMillis("7d"));
        assertEquals(604_800_000L, LadderPolicy.parseDurationMillis("1w"));
        assertEquals(604_800_000L, LadderPolicy.parseDurationMillis("7D"), "单位大小写不敏感");
        assertEquals(604_800_000L, LadderPolicy.parseDurationMillis(" 7d "), "首尾空格要 trim");

        // 裸数字是最危险的一种：写 "7" 的本意可能是 7 天，也可能是 7 秒。
        // 与其猜错，不如报错让调用方告警。
        assertEquals(LadderPolicy.INVALID_MILLIS, LadderPolicy.parseDurationMillis("7"));
        assertEquals(LadderPolicy.INVALID_MILLIS, LadderPolicy.parseDurationMillis("d"));
        assertEquals(LadderPolicy.INVALID_MILLIS, LadderPolicy.parseDurationMillis("0d"));
        assertEquals(LadderPolicy.INVALID_MILLIS, LadderPolicy.parseDurationMillis("-3d"));
        assertEquals(LadderPolicy.INVALID_MILLIS, LadderPolicy.parseDurationMillis("7x"));
        assertEquals(LadderPolicy.INVALID_MILLIS, LadderPolicy.parseDurationMillis("abc"));
    }

    @Test
    @DisplayName("时长描述：写错时要把原文暴露出来，不能悄悄变成永久")
    void describeDuration() {
        assertEquals("永久", LadderPolicy.describeDuration(null));
        assertEquals("永久", LadderPolicy.describeDuration("perm"));
        assertEquals("7d", LadderPolicy.describeDuration("7d"));
        assertTrue(LadderPolicy.describeDuration("7x").contains("7x"),
                "写错的原文必须出现在描述里: " + LadderPolicy.describeDuration("7x"));
        assertTrue(LadderPolicy.describeDuration("7x").contains("配置写错"));
    }

    // ------------------------------------------------------------------ 阶梯解析

    @Test
    @DisplayName("阶梯解析：自动编号、按 min-vl 排序、缺 min-vl 的条目丢弃")
    void parseSteps() {
        List<Map<String, Object>> raw = new ArrayList<>();
        raw.add(entry(50.0, "BAN", "7d", "封禁 7 天"));
        raw.add(entry(20.0, "kick", null, null));
        Map<String, Object> noThreshold = new LinkedHashMap<>();
        noThreshold.put("action", "ban");
        raw.add(noThreshold);

        List<LadderStep> steps = LadderPolicy.parseSteps(raw);
        assertEquals(2, steps.size(), "缺 min-vl 的条目没有分档依据，必须丢弃");
        assertEquals(20.0, steps.get(0).getMinVl(), 1e-9, "必须按 min-vl 升序，与书写顺序无关");
        assertEquals(1, steps.get(0).getStep(), "step 由顺序自动编号");
        assertEquals("kick", steps.get(0).getAction());
        assertNull(steps.get(0).getDuration());
        assertEquals(50.0, steps.get(1).getMinVl(), 1e-9);
        assertEquals(2, steps.get(1).getStep());
        assertEquals("ban", steps.get(1).getAction(), "action 要统一成小写");
        assertEquals("7d", steps.get(1).getDuration());
        assertEquals("封禁 7 天", steps.get(1).getReason());

        assertEquals(0, LadderPolicy.parseSteps(null).size());
        assertEquals(0, LadderPolicy.parseSteps(Collections.<Map<String, Object>>emptyList()).size());
    }

    // ------------------------------------------------------------------ 选档

    @Test
    @DisplayName("选档：第几次被抓决定上限，VL 决定够不够格；证据不足退回较轻档，超档数封顶")
    void selectStep() {
        // 第 1 次踢 / 第 2 次封 1 天 / 第 3 次封 7 天；min-vl 是各档的证据门槛
        List<LadderStep> steps = Arrays.asList(
                new LadderStep(1, 8.0, "kick", null, null),
                new LadderStep(2, 10.0, "ban", "1d", null),
                new LadderStep(3, 12.0, "ban", "7d", null));

        assertNull(LadderPolicy.selectStep(steps, 7.9, 1), "VL 未达最低档门槛 → 不处罚");
        assertEquals("kick", LadderPolicy.selectStep(steps, 8.0, 1).getAction(), "等于门槛必须命中");
        assertEquals("kick", LadderPolicy.selectStep(steps, 99.0, 1).getAction(),
                "第 1 次被抓最多只到第 1 档：VL 再高也不能凭一次就越级封禁");
        assertEquals("ban", LadderPolicy.selectStep(steps, 11.0, 2).getAction(), "第 2 次 → 第 2 档");
        assertEquals("7d", LadderPolicy.selectStep(steps, 20.0, 3).getDuration(), "第 3 次 → 第 3 档");
        assertEquals("7d", LadderPolicy.selectStep(steps, 20.0, 9).getDuration(),
                "被抓次数超过档数 → 封顶在最后一档（不能返回 null 把人放走）");
        assertEquals("kick", LadderPolicy.selectStep(steps, 9.0, 3).getAction(),
                "第 3 次但 VL 只到第 1 档的门槛 → 退回第 1 档（证据不够就不给重刑）");

        assertNull(LadderPolicy.selectStep(null, 100.0, 1));
        assertNull(LadderPolicy.selectStep(Collections.<LadderStep>emptyList(), 100.0, 1));
    }

    @Test
    @DisplayName("描述：把档位与动作写成人能读的一行（日志要靠它证明阶梯真的生效）")
    void describe() {
        List<LadderStep> steps = Arrays.asList(
                new LadderStep(1, 8.0, "kick", null, null),
                new LadderStep(2, 10.0, "ban", "1d", null),
                new LadderStep(3, 12.0, "ban", "7d", null));
        String text = LadderPolicy.describe(steps);
        assertTrue(text.contains("第1次(8.0)→kick"), text);
        assertTrue(text.contains("第2次(10.0)→ban(1d)"), text);
        assertTrue(text.contains("第3次(12.0)→ban(7d)"), text);
        assertTrue(LadderPolicy.describe(null).contains("无"));
    }

    @Test
    @DisplayName("模板展开：%player% / %check% / %vl% / %duration%")
    void expandTemplate() {
        String out = LadderPolicy.expandTemplate(
                "%player% 因 %check% (VL=%vl%) 被处理，时长 %duration%", "cklsit", "ReachA", 12.345, "7d");
        assertEquals("cklsit 因 ReachA (VL=12.35) 被处理，时长 7d", out);

        assertEquals("永久", LadderPolicy.expandTemplate("%duration%", "p", "c", 1.0, null));
        assertTrue(LadderPolicy.expandTemplate("%duration%", "p", "c", 1.0, "7x").contains("配置写错"));
    }

    // ------------------------------------------------------------------ 白名单解析

    @Test
    @DisplayName("白名单解析：字符串（名字或 UUID）与映射两种写法；无效条目丢弃")
    void parseWhitelist() {
        long now = 1_700_000_000_000L;
        UUID uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

        List<Object> raw = new ArrayList<>();
        raw.add("Notch");
        raw.add(uuid.toString());
        raw.add("  ");
        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("name", "Builder");
        mapped.put("reason", "建筑组");
        mapped.put("expires-in", "30d");
        raw.add(mapped);
        Map<String, Object> empty = new LinkedHashMap<>();
        raw.add(empty);

        List<WhitelistSeed> seeds = WhitelistPolicy.parse(raw, now);
        assertEquals(3, seeds.size(), "空白字符串与空映射都要丢弃");

        assertNull(seeds.get(0).getUuid(), "纯名字条目的 uuid 必须是 null（不能瞎猜一个）");
        assertEquals("Notch", seeds.get(0).getName());
        assertNull(seeds.get(0).getExpiresAt(), "没写 expires-in 就是永久");
        assertEquals("config.yml", seeds.get(0).getReason());

        assertEquals(uuid, seeds.get(1).getUuid(), "UUID 字符串要被识别成 uuid，而不是当成名字");
        assertNull(seeds.get(1).getName());

        assertEquals("Builder", seeds.get(2).getName());
        assertEquals("建筑组", seeds.get(2).getReason());
        assertNotNull(seeds.get(2).getExpiresAt());
        assertEquals(now + 30L * 86_400_000L, seeds.get(2).getExpiresAt().longValue());

        // expires-in 写错按永久处理（白名单的方向是"放行"，把它变成"立刻过期"更难排查）
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("name", "Oops");
        bad.put("expires-in", "30");
        assertNull(WhitelistPolicy.parse(Collections.singletonList(bad), now).get(0).getExpiresAt(),
                "写错的 expires-in 按永久，并且由 describeDuration 在日志里暴露原文");

        assertEquals(0, WhitelistPolicy.parse(null, now).size());
    }

    // ------------------------------------------------------------------ 辅助

    private static Map<String, Object> entry(Double minVl, String action, String duration, String reason) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (minVl != null) {
            map.put("min-vl", minVl);
        }
        map.put("action", action);
        if (duration != null) {
            map.put("duration", duration);
        }
        if (reason != null) {
            map.put("reason", reason);
        }
        return map;
    }
}
