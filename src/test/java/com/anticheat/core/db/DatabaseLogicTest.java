package com.anticheat.core.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据库层**纯逻辑**部分的测试：配置解析、IP 情报、风险评分、用户名历史、审计条件拼装。
 *
 * <p>这些是"错了不会报错"的重灾区：配置解析错 → 连到别的库；
 * 风险公式错 → 分数整体偏高或偏低（还很好看）；
 * 审计条件拼错 → 查出来的记录少了（页面上看不出来）。</p>
 */
class DatabaseLogicTest {

    // ------------------------------------------------------------------ 配置解析

    @Test
    @DisplayName("配置：默认后端是 H2（保底），type 拼错也回到 H2 而不是报错")
    void settingsDefaultsToH2() {
        Map<String, Object> values = new HashMap<>();
        values.put("type", "h2");
        DatabaseSettings settings = DatabaseSettings.from(values);
        assertEquals(DatabaseSettings.Backend.H2, settings.getBackend());
        assertTrue(settings.getEnabled());
        assertTrue(settings.jdbcUrl("/data").startsWith("jdbc:h2:file:/data/"),
                "H2 的库文件应在插件数据目录下: " + settings.jdbcUrl("/data"));

        // 拼错的后端名 → H2（而不是"不落库"）
        assertEquals(DatabaseSettings.Backend.H2, DatabaseSettings.Backend.parse("postgresq"));
        assertEquals(DatabaseSettings.Backend.POSTGRESQL, DatabaseSettings.Backend.parse("PostgreSQL"));
        assertEquals(DatabaseSettings.Backend.POSTGRESQL, DatabaseSettings.Backend.parse("pg"));

        // 没有 database 段 → 整体禁用（不尝试连接）
        DatabaseSettings absent = DatabaseSettings.from(null);
        assertFalse(absent.getEnabled());
    }

    @Test
    @DisplayName("配置：PostgreSQL 参数解析 + 非法值被夹到合理区间")
    void settingsParsingAndClamping() {
        Map<String, Object> values = new HashMap<>();
        values.put("type", "postgresql");
        values.put("postgres.host", "10.0.0.5");
        values.put("postgres.port", 6543);
        values.put("postgres.database", "aac");
        values.put("postgres.username", "u");
        values.put("postgres.password", "s3cr3tPw");
        values.put("pool-size", 999);                 // 超上限 → 夹到 32
        values.put("connection-timeout-ms", 1);       // 低于下限 → 夹到 250
        values.put("violation.batch-size", 0);        // 0 会让刷写永远为空 → 夹到 1
        values.put("stats.bucket-minutes", 0);        // 0 会导致除零/桶异常 → 夹到 1
        values.put("server-name", "Lobby-2");

        DatabaseSettings settings = DatabaseSettings.from(values);
        assertEquals(DatabaseSettings.Backend.POSTGRESQL, settings.getBackend());
        assertEquals("jdbc:postgresql://10.0.0.5:6543/aac", settings.getPostgres().jdbcUrl());
        assertEquals("jdbc:postgresql://10.0.0.5:6543/aac", settings.jdbcUrl("/data"),
                "PostgreSQL 忽略数据目录");
        assertEquals(DatabaseSettings.MAX_POOL_SIZE, settings.getPoolSize());
        assertEquals(DatabaseSettings.MIN_TIMEOUT_MS, settings.getConnectionTimeoutMs());
        assertEquals(1, settings.getViolation().getBatchSize());
        assertEquals(1, settings.getStats().getBucketMinutes());
        assertEquals("Lobby-2", settings.getServerName());
        assertFalse(settings.describe().contains("s3cr3tPw"), "排障描述里不能出现密码: " + settings.describe());
    }

    // ------------------------------------------------------------------ IP 情报

    @Test
    @DisplayName("IP：归一化各种脏数据（端口/方括号/zone/前导斜杠）")
    void ipNormalization() {
        assertEquals("192.168.1.5", IpIntel.normalize("/192.168.1.5:53211"));
        assertEquals("192.168.1.5", IpIntel.normalize("192.168.1.5"));
        assertEquals("192.168.1.5", IpIntel.normalize("  192.168.1.5  "));
        assertNotNull(IpIntel.normalize("[2001:db8::1]:25565"));
        assertNotNull(IpIntel.normalize("fe80::1%eth0"));
        assertNull(IpIntel.normalize(null));
        assertNull(IpIntel.normalize(""));
        assertNull(IpIntel.normalize("null"));
        assertNull(IpIntel.normalize("mc.example.com"), "非字面量必须拒绝：解析它会走 DNS（外联+阻塞）");
    }

    @Test
    @DisplayName("IP：网段归并（IPv4 /24、IPv6 /64）与地址族")
    void ipCidrAndFamily() {
        assertEquals(4, IpIntel.familyOf("192.168.1.5"));
        assertEquals(6, IpIntel.familyOf("2001:db8::1"));
        assertEquals(0, IpIntel.familyOf("garbage"));
        assertEquals("192.168.1.0/24", IpIntel.cidrOf("192.168.1.5"));
        assertEquals("10.0.0.0/24", IpIntel.cidrOf("10.0.0.255"));
        assertEquals("2001:db8:1:2:0:0:0:0/64", IpIntel.cidrOf("2001:db8:1:2::abcd"));
    }

    @Test
    @DisplayName("IP 情报：最长前缀优先，命中不了就留空（不编一个国家出来）")
    void ipIntelRules() {
        List<IpIntelRule> rules = IpIntel.parseRules(Arrays.asList(
                map("cidr", "10.0.0.0/8", "asn", 1, "country", "NET"),
                map("cidr", "10.1.0.0/16", "asn", 2, "country", "LAN16"),
                map("cidr", "garbage", "asn", 3, "country", "XX"),
                map("cidr", "192.168.0.0/16", "country", "LAN")
        ));
        assertEquals(3, rules.size(), "非法网段的规则应被跳过");

        assertEquals(2, IpIntel.match("10.1.2.3", rules).getAsn(), "最长前缀优先（/16 胜过 /8）");
        assertEquals(1, IpIntel.match("10.2.2.3", rules).getAsn());
        assertEquals("LAN", IpIntel.match("192.168.9.9", rules).getCountry());
        assertNull(IpIntel.match("8.8.8.8", rules), "没命中就返回 null（SQL 里存 NULL）");
        assertNull(IpIntel.match("garbage", rules));

        assertTrue(IpIntel.contains("10.1.0.0/16", "10.1.2.3"));
        assertFalse(IpIntel.contains("10.1.0.0/16", "10.2.2.3"));
        assertFalse(IpIntel.contains("10.1.0.0/16", "192.168.1.1"), "不同地址族不能相互包含");
    }

    // ------------------------------------------------------------------ 风险评分

    @Test
    @DisplayName("风险：严重度由本次增量推出，实验性检测封顶到 2")
    void severity() {
        assertEquals(4, RiskScorer.severityOf(2.5, false));
        assertEquals(3, RiskScorer.severityOf(1.0, false));
        assertEquals(2, RiskScorer.severityOf(0.5, false));
        assertEquals(1, RiskScorer.severityOf(0.1, false));
        assertEquals(2, RiskScorer.severityOf(3.0, true), "实验性检测不该把风险推到高位");
        assertEquals(1, RiskScorer.severityOf(0.1, true));
    }

    @Test
    @DisplayName("风险：无事件为 0，随时间衰减，有上限且单调")
    void scoring() {
        long now = 1_700_000_000_000L;
        assertEquals(0.0, RiskScorer.score(Collections.emptyList(), now), 1e-9);

        RiskEvent fresh = new RiskEvent(1.0, now, 3);
        double single = RiskScorer.score(Collections.singletonList(fresh), now);
        assertTrue(single > 0 && single < 30, "单次违规不该直接到高位: " + single);

        // 半衰期 6 小时：一天前的同样事件，权重应衰减到 1/16 左右
        RiskEvent old = new RiskEvent(1.0, now - 24L * 3600_000L, 3);
        double decayed = RiskScorer.score(Collections.singletonList(old), now);
        assertTrue(decayed < single * 0.3, "24 小时前的违规应基本失去权重: " + decayed + " vs " + single);

        // 大量事件 → 收敛到接近上限但不超过
        RiskEvent[] many = new RiskEvent[500];
        Arrays.fill(many, fresh);
        double heavy = RiskScorer.score(Arrays.asList(many), now);
        assertTrue(heavy > 90 && heavy <= RiskScorer.MAX_SCORE, "持续违规应接近上限: " + heavy);

        assertTrue(RiskScorer.score(Arrays.asList(fresh, fresh), now)
                > RiskScorer.score(Collections.singletonList(fresh), now), "事件越多分数越高");
        assertEquals("极高", RiskScorer.band(95.0));
        assertEquals("低", RiskScorer.band(1.0));
    }

    // ------------------------------------------------------------------ 用户名历史

    @Test
    @DisplayName("用户名历史：只放旧名、最新在前、去重且保持首次出现的位置")
    void nameHistoryMerge() {
        assertEquals(Collections.emptyList(), NameHistory.merge(Collections.emptyList(), "Alice", "Alice"),
                "名字没变时历史不增长");
        assertEquals(Collections.singletonList("Alice"), NameHistory.merge(Collections.emptyList(), "Alice", "Bob"));
        assertEquals(Arrays.asList("Bob", "Alice"),
                NameHistory.merge(Collections.singletonList("Alice"), "Bob", "Carol"),
                "新改的名字要把上一个名字插到最前");
        // A -> B -> A：历史里不应出现两次 A
        List<String> merged = NameHistory.merge(Arrays.asList("B", "A"), "A", "C");
        assertEquals(Arrays.asList("A", "B"), merged, "去重时必须保留首次出现的位置（最近的在前）");
        assertTrue(NameHistory.merge(Collections.emptyList(), null, "Zed").isEmpty());
    }

    // ------------------------------------------------------------------ 审计条件拼装

    @Test
    @DisplayName("审计条件：占位符数量与参数个数必须一致（错位不会报错，只会查出错的记录）")
    void auditWhere() {
        assertEquals(AuditWhere.EMPTY, AuditWhere.build(new AuditFilter()).getFirst());

        kotlin.Pair<String, List<Object>> typed =
                AuditWhere.build(new AuditFilter("BAN", "SUCCESS", "abc", 100L, 200L, 3, 50));
        String where = typed.getFirst();
        List<Object> params = typed.getSecond();
        // 5 个条件：type / result / keyword×3 / start / end → 7 个占位符
        assertEquals(7, where.split("\\?", -1).length - 1,
                "占位符数量必须等于参数个数：" + where);
        assertEquals(params.size(), where.split("\\?", -1).length - 1);
        assertTrue(where.contains("(operator LIKE ? OR target LIKE ? OR detail LIKE ?)"),
                "关键字要在三列上模糊匹配：" + where);
        assertEquals("%abc%", params.get(2));
        assertTrue(where.contains("ts >= ?") && where.contains("ts <= ?"));

        // 归一化：页码从 1 起、页长钳到 1~200
        AuditFilter normalized = new AuditFilter(null, null, null, null, null, 0, 9999).normalized();
        assertEquals(1, normalized.getPage());
        assertEquals(200, normalized.getPageSize());
        assertEquals(0, normalized.getOffset());
        assertEquals(200, new AuditFilter(null, null, null, null, null, 2, 200).normalized().getOffset());
    }

    @Test
    @DisplayName("时间分组键：按 UTC 天对齐，跨天不串桶")
    void dayBucket() {
        long day = 86_400_000L;
        assertEquals(0L, Sql.dayBucket(0L));
        assertEquals(0L, Sql.dayBucket(day - 1));
        assertEquals(day, Sql.dayBucket(day));
        assertEquals(day, Sql.dayBucket(day + 12_345L));
        // -1ms 落在 [-86400000, 0) 这一天，其起点是 -86400000（不是 0）
        assertEquals(-86_400_000L, Sql.dayBucket(-1L), "负数时间要向下取整到当天的起点");
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }
}
