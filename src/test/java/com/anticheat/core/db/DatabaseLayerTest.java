package com.anticheat.core.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据库层的**真实 SQL 测试**：跑在内存 H2 上。
 *
 * <h3>为什么必须跑真库</h3>
 * 这一层的错误几乎全是"不会抛异常、只会悄悄错"的类型：
 * 参数绑错位（写了坐标却绑到了 ping）、时间戳单位错（秒当毫秒）、
 * upsert 的 UPDATE 没命中却当成成功、唯一键释放不掉导致"第二次封禁失败"。
 * 用 mock 断言"SQL 文本对不对"是抓不到这些的，所以这里直接建库、建表、
 * 写数据、读回来比对。
 *
 * <p>也正因为要"两个后端共用一套 SQL"，这套测试同时是**可移植性的守门人**：
 * H2 跑不通的语句，PostgreSQL 那边也不该依赖（反之亦然，两者的公共子集才是我们写的）。</p>
 */
class DatabaseLayerTest {

    private static JdbcPool pool;

    private static int databaseSeq = 0;

    /** @BeforeEach 里首次迁移的结果，供"迁移幂等"用例断言 */
    private static MigrationResult initialMigration;

    @BeforeEach
    void setUp() {
        DatabaseService.Companion.registerDriver(DatabaseSettings.Backend.H2);
        // 每个用例一个独立的库：内存库会保留在同一个 JVM 里，
        // 共用的话前面用例写入的数据会污染后面的"条数"断言（本轮就是这么撞上的）。
        // DB_CLOSE_DELAY=-1 保证库在最后一个连接关闭后仍然存活。
        if (pool != null) {
            pool.close();
        }
        pool = new JdbcPool(
                "jdbc:h2:mem:aac_db_" + (++databaseSeq) + ";DB_CLOSE_DELAY=-1", "sa", "", 2, 3000L);
        initialMigration = new Migrator(pool).migrate();
    }

    @Test
    @DisplayName("迁移：首次执行建全部结构，重复执行幂等（只跳过）")
    void migrationsAreIdempotent() {
        // 首次迁移已在 @BeforeEach 里做过（每个用例一个干净的库）
        assertEquals(Arrays.asList(1, 2), initialMigration.getApplied(),
                "首次应执行两个迁移: " + initialMigration.describe());
        assertEquals(Migrations.latestVersion(), initialMigration.getLatestVersion());

        MigrationResult second = new Migrator(pool).migrate();
        assertTrue(second.getApplied().isEmpty(), "重复执行不应再跑任何迁移");
        assertEquals(2, second.getSkipped());
        assertTrue(second.getChecksumMismatches().isEmpty(), "校验和必须一致（没有人改过已执行的迁移）");
    }

    @Test
    @DisplayName("档案：登录累计会话与用户名历史，离线累计时长")
    void profileLifecycle() {
        ProfileRepository profiles = new ProfileRepository(pool);
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();

        profiles.onLogin(uuid, "Alice", "192.168.1.5", now);
        ProfileRow first = profiles.find(uuid);
        assertNotNull(first);
        assertEquals(1, first.getSessions());
        assertEquals("192.168.1.5", first.getLastIp());
        assertEquals(now, first.getFirstSeen());

        // 改名后再登录：旧名应进入 player_name 表，且能按旧名反查到同一个人
        profiles.onLogin(uuid, "Alice2", "192.168.1.5", now + 1000);
        ProfileRow second = profiles.find(uuid);
        assertEquals(2, second.getSessions(), "同一 uuid 第二次登录要累加会话数");
        assertEquals("Alice2", second.getName(), "名字要更新为最新");
        assertEquals("192.168.1.5", second.getLastIp(), "IP 为 null 时应保留旧值（这里显式给了值，必一致）");

        List<PlayerNameRow> names = profiles.namesOf(uuid);
        assertEquals(2, names.size(), "两个名字都要留痕: " + names);

        List<ProfileRow> byOldName = profiles.findByAnyName("Alice");
        assertEquals(1, byOldName.size(), "按旧名应能反查到档案（小号识别的基本能力）");
        assertEquals(uuid, byOldName.get(0).getUuid());

        profiles.onLogout(uuid, 3600, now + 2000);
        assertEquals(3600L, profiles.find(uuid).getPlaytimeSeconds());
    }

    @Test
    @DisplayName("IP 记录：归一化 + 地址族 + 网段 + 离线情报（ASN/国家）")
    void ipRecords() {
        IpIntelRule lan = new IpIntelRule("192.168.0.0/16", 0, "LAN", "LAN");
        IpRepository ips = new IpRepository(pool, () -> Collections.singletonList(lan));
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();

        // 客户端给的原始串带端口：必须归一化，否则同一个 IP 会存成多条
        IpRow row = ips.recordLogin(uuid, "/192.168.1.5:53211", now);
        assertNotNull(row);
        assertEquals("192.168.1.5", row.getIp());
        assertEquals(4, row.getFamily());
        assertEquals("192.168.1.0/24", row.getCidr(), "IPv4 归并到 /24");
        assertEquals("LAN", row.getCountry(), "按管理员配置的网段规则给出国家/ASN");
        assertEquals(0, row.getAsn());

        ips.recordLogin(uuid, "192.168.1.5", now + 1000);
        List<IpRow> list = ips.listOf(uuid, 10);
        assertEquals(1, list.size(), "同一地址重复登录只应有一条记录");
        assertEquals(2, list.get(0).getLoginCount());
        assertEquals(1, ips.accountCountOf("192.168.1.5"));
        assertEquals(Collections.singletonList(uuid), ips.playersOf("192.168.1.5"));
        assertEquals(Collections.singletonList(uuid), ips.playersInCidr("192.168.1.0/24"));

        // IPv6 走同一条路径，归并到 /64
        IpRow v6 = ips.recordLogin(UUID.randomUUID(), "2001:db8:0:1::5", now);
        assertNotNull(v6);
        assertEquals(6, v6.getFamily());
        assertEquals("2001:db8:0:1:0:0:0:0/64", v6.getCidr(), "IPv6 归并到 /64: " + v6.getCidr());

        // 无法解析的串不落库（而不是留一行垃圾）
        assertNull(ips.recordLogin(uuid, "not-an-ip", now));
        assertNull(ips.recordLogin(uuid, null, now));
    }

    @Test
    @DisplayName("封禁：UUID/IP/CIDR、临时与永久、撤销释放唯一键、过期落状态")
    void bans() {
        BanRepository bans = new BanRepository(pool);
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();

        long id = bans.insert(BanKind.UUID, uuid.toString(), "Alice", uuid, BanScope.PERM,
                "reach", "console", now, null, "Server-1", null);
        assertTrue(id > 0, "应取回自增主键");

        BanRow hit = bans.findEffective(uuid, null, now);
        assertNotNull(hit);
        assertEquals(BanKind.UUID, hit.getKind());
        assertEquals("Alice", hit.getName());
        assertTrue(hit.isEffective(now));

        // 同一 uuid 再来一条：必须被唯一约束挡住（并发下"先查后插"是拦不住的）
        assertThrows(java.sql.SQLException.class,
                () -> bans.insert(BanKind.UUID, uuid.toString(), "Alice", uuid, BanScope.PERM,
                        "dup", "console", now, null, "Server-1", null));

        // 过期封禁不生效，且状态会被扫描落到 expired
        UUID tempUuid = UUID.randomUUID();
        bans.insert(BanKind.UUID, tempUuid.toString(), "Bob", tempUuid, BanScope.TEMP,
                "timer", "console", now, now - 1000, "Server-1", null);
        assertNull(bans.findEffective(tempUuid, null, now), "已过期的封禁不应生效");
        assertEquals(1, bans.expireOverdue(now));
        assertEquals(BanStatus.EXPIRED, bans.listPage(0, 10).stream()
                .filter(b -> tempUuid.equals(b.getUuid())).findFirst().orElseThrow().getStatus());

        // 撤销后唯一键被释放：可以对同一个人重新封禁
        assertEquals(1, bans.revokeByUuid(uuid, "admin", "误封", now));
        assertNull(bans.findEffective(uuid, null, now));
        long again = bans.insert(BanKind.UUID, uuid.toString(), "Alice", uuid, BanScope.TEMP,
                "reach again", "admin", now, now + 86400_000L, "Server-1", null);
        assertTrue(again > id, "撤销后应能重新封禁");

        // IP 与 CIDR
        long ipBan = bans.insert(BanKind.IP, "10.1.2.3", null, null, BanScope.PERM,
                "cheat", "console", now, null, "Server-1", null);
        assertTrue(ipBan > 0);
        assertNotNull(bans.findEffective(UUID.randomUUID(), "10.1.2.3", now), "精确 IP 封禁要命中");
        assertNull(bans.findEffective(UUID.randomUUID(), "10.1.2.4", now));

        bans.insert(BanKind.CIDR, "10.2.0.0/16", null, null, BanScope.PERM,
                "range", "console", now, null, "Server-1", null);
        assertNotNull(bans.findEffective(UUID.randomUUID(), "10.2.9.9", now), "CIDR 覆盖要命中");
        assertNull(bans.findEffective(UUID.randomUUID(), "10.3.9.9", now), "网段外不应命中");
        assertEquals(1, bans.revokeByIp("10.1.2.3", "admin", "unban", now));
        assertNull(bans.findEffective(UUID.randomUUID(), "10.1.2.3", now));
    }

    @Test
    @DisplayName("违规：批量写入、按检测/玩家查询、按天趋势、风险事件聚合")
    void violations() {
        ViolationRepository repo = new ViolationRepository(pool);
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        long day = Sql.dayBucket(now);

        List<ViolationInput> batch = Arrays.asList(
                violation(uuid, "Alice", "ReachA", 3.6, 0.2, 1, now, day),
                violation(uuid, "Alice", "ReachA", 3.9, 0.3, 1, now + 1000, day),
                violation(uuid, "Alice", "TimerA", 1.1, 0.1, 1, now + 2000, day)
        );
        assertEquals(3, repo.insertBatch(batch));

        assertEquals(3, repo.countSince(now - 1));
        assertEquals(2, repo.recentByCheck("ReachA", 10).size());
        assertEquals(3, repo.recent(uuid, 10).size());

        List<PlayerCheckStatRow> stats = repo.statsOf(uuid, now - 1);
        assertEquals(2, stats.size());
        assertEquals("ReachA", stats.get(0).getCheckName(), "按违规数倒序，ReachA 应在最前");
        assertEquals(2, stats.get(0).getViolations());

        List<ViolationTrendRow> trend = repo.trend(now - 86400_000L);
        assertFalse(trend.isEmpty(), "趋势视图应能按天聚合出结果");
        assertEquals(day, trend.get(0).getDayMillis());
        assertEquals(2, trend.get(0).getViolations(), "同一天同一检测应聚合为 2 条");

        Map<UUID, List<RiskEvent>> events = repo.eventsByPlayer(now - 86400_000L);
        assertEquals(3, events.get(uuid).size(), "风险重算的事件聚合要拿到全部违规");

        assertEquals(2, repo.topChecksSince(now - 86400_000L, 5).size());
        assertEquals(0, repo.deleteOlderThan(now - 60_000L), "窗口外没有数据可删");
    }

    @Test
    @DisplayName("规则表：首次登记写入 config 值，之后库里的策略字段是权威（不被启动同步覆盖）")
    void checkRuleAuthority() {
        RuleRepository rules = new RuleRepository(pool);
        long now = System.currentTimeMillis();

        Map<String, Object> thresholds = new HashMap<>();
        thresholds.put("max-reach", 3.0);
        thresholds.put("tolerance", 0.4);
        RuleRepository.CheckSeed seed = new RuleRepository.CheckSeed(
                "ReachA", true, 0.05, 0.0, false, "攻击距离超限", thresholds);
        assertEquals(1, rules.syncCheckRules(Collections.singletonList(seed), "startup", now).size());

        CheckRuleRow row = rules.loadCheckRules().get("ReachA");
        assertNotNull(row);
        assertEquals(0.4, Double.parseDouble(row.getThresholds().get("tolerance")), 1e-9,
                "首次登记应把 config.yml 的阈值写进库");
        assertTrue(row.getEnabled());

        // 管理员直接改库（把阈值收紧、把检测关掉）
        rules.setCheckThresholds("ReachA", Collections.singletonMap("tolerance", 0.25), "admin", now + 1);
        rules.setCheckEnabled("ReachA", false, "admin", now + 2);

        // 再次启动同步：描述/实验性会被刷新，但 enabled 与 thresholds **不能**被覆盖
        rules.syncCheckRules(Collections.singletonList(seed), "startup", now + 3);
        CheckRuleRow after = rules.loadCheckRules().get("ReachA");
        assertFalse(after.getEnabled(), "库里的开关是权威：重启同步不能把它改回 true");
        assertEquals(0.25, Double.parseDouble(after.getThresholds().get("tolerance")), 1e-9,
                "库里的阈值是权威：重启同步不能把管理员的调整覆盖掉");
        assertEquals("攻击距离超限", after.getDescription(), "代码事实（描述）仍应刷新");
    }

    @Test
    @DisplayName("白名单：到期失效、删除后可重新添加（唯一键被释放）")
    void whitelist() {
        RuleRepository rules = new RuleRepository(pool);
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();

        rules.addWhitelist(uuid, "Alice", "手动放行", "admin", now, null);
        assertTrue(rules.isWhitelisted(uuid, null, now));
        assertTrue(rules.isWhitelisted(UUID.randomUUID(), "Alice", now), "按名字也应命中");

        // 同一个 uuid 再加一条会撞唯一键；删除后可以再加
        assertEquals(1, rules.removeWhitelist(uuid.toString()));
        assertFalse(rules.isWhitelisted(uuid, null, now));
        rules.addWhitelist(uuid, "Alice", "再次放行", "admin", now, null);
        assertTrue(rules.isWhitelisted(uuid, null, now));

        // 到期即失效（白名单也要有期限，否则一次临时放行会变成永久豁免）
        UUID temp = UUID.randomUUID();
        rules.addWhitelist(temp, "Bob", "临时", "admin", now, now - 1);
        assertFalse(rules.isWhitelisted(temp, null, now));
        assertFalse(rules.listWhitelist(10).isEmpty());
    }

    @Test
    @DisplayName("惩罚阶梯：整体替换（不会留下半套）")
    void punishmentLadder() {
        RuleRepository rules = new RuleRepository(pool);
        long now = System.currentTimeMillis();
        rules.replaceLadder(Arrays.asList(
                new LadderStep(1, 10.0, "alert", null, "观察"),
                new LadderStep(2, 20.0, "kick", null, "踢出"),
                new LadderStep(3, 40.0, "ban", "7d", "临时封禁")
        ), now);
        assertEquals(3, rules.loadLadder().size());

        rules.replaceLadder(Collections.singletonList(new LadderStep(1, 5.0, "alert", null, "只告警")), now + 1);
        List<LadderStep> after = rules.loadLadder();
        assertEquals(1, after.size(), "整体替换应先清空（否则会残留旧阶梯）");
        assertEquals(5.0, after.get(0).getMinVl(), 1e-9);
    }

    @Test
    @DisplayName("检查命中率：累加口径（players 取较大值而不是相加）")
    void checkStats() {
        RuleRepository rules = new RuleRepository(pool);
        long bucket = Sql.dayBucket(System.currentTimeMillis());

        rules.accumulateCheckStats(bucket, Collections.singletonList(
                new CheckStatDelta("ReachA", 1000, 3, 0.6, 2)));
        rules.accumulateCheckStats(bucket, Collections.singletonList(
                new CheckStatDelta("ReachA", 500, 1, 0.2, 5)));

        List<HitRateRow> rates = rules.checkHitRates();
        HitRateRow row = rates.stream().filter(r -> "ReachA".equals(r.getCheckName())).findFirst().orElseThrow();
        assertEquals(1500, row.getEvaluations(), "同一桶内评估次数应累加");
        assertEquals(4, row.getFlags());
        assertEquals(4.0 / 1500.0, row.getHitRate(), 1e-9, "命中率 = flags / evaluations");

        List<CheckStatRow> history = rules.checkStatsSince("ReachA", bucket - 1);
        assertEquals(1, history.size());
        assertEquals(5, history.get(0).getPlayers(), "players 是'桶内出现过多少个玩家'，取较大值而不是相加");
    }

    @Test
    @DisplayName("审计：查询口径与旧实现一致（精确匹配 + 关键字模糊 + 时间区间 + 分页）")
    void auditLog() {
        AuditRepository audits = new AuditRepository(pool);
        long now = System.currentTimeMillis();
        audits.insert(new AuditRow(null, now - 3000, "admin", 2, "BAN", "Alice", "10.0.0.1", "SUCCESS", "reach 超限"));
        audits.insert(new AuditRow(null, now - 2000, "console", 0, "UNBAN", "Alice", "10.0.0.1", "SUCCESS", "误封"));
        audits.insert(new AuditRow(null, now - 1000, "admin", 2, "BAN", "Bob", "10.0.0.2", "FAILED", "权限不足"));

        assertEquals(3, audits.count(new AuditFilter()));
        assertEquals(2, audits.count(new AuditFilter("BAN", null, null, null, null, 1, 20)));
        assertEquals(1, audits.count(new AuditFilter(null, "FAILED", null, null, null, 1, 20)));
        assertEquals(2, audits.count(new AuditFilter(null, null, "Alice", null, null, 1, 20)),
                "关键字要在 operator/target/detail 三列上模糊匹配");
        assertEquals(2, audits.count(new AuditFilter(null, null, null, now - 2500, null, 1, 20)),
                "startTime 之后有两条（now-2000、now-1000）");
        assertEquals(1, audits.count(new AuditFilter(null, null, null, null, now - 2500, 1, 20)),
                "endTime 之前只有最早那条（now-3000）");

        List<AuditRow> page = audits.query(new AuditFilter(null, null, null, null, null, 1, 2));
        assertEquals(2, page.size(), "分页大小生效");
        assertTrue(page.get(0).getTimestamp() > page.get(1).getTimestamp(), "必须按时间倒序");
        List<AuditRow> secondPage = audits.query(new AuditFilter(null, null, null, null, null, 2, 2));
        assertEquals(1, secondPage.size(), "第二页只剩 1 条");
        // 时间倒序：第 1 页是 [Bob(now-1000), Alice(now-2000)]，第 2 页只剩 Alice(now-3000)
        assertEquals("Alice", secondPage.get(0).getTarget());
    }

    @Test
    @DisplayName("批量写入器：队列满时丢弃并计数，刷写后队列清空")
    void recorderBackpressure() {
        RuleRepository rules = new RuleRepository(pool);
        ViolationRepository repo = new ViolationRepository(pool);
        // 队列容量 64（下限），batch 8
        DatabaseSettings.ViolationSettings settings =
                new DatabaseSettings.ViolationSettings(8, 1000L, 64);
        ViolationRecorder recorder = new ViolationRecorder(
                settings, repo, rules, 5, System::currentTimeMillis);

        long before = repo.countSince(0);
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            recorder.record(violation(uuid, "Alice", "ReachA", 3.5, 0.3, 1, now + i, Sql.dayBucket(now)));
            recorder.noteEvaluation("ReachA");
        }
        recorder.noteFlag("ReachA", 0.3, uuid);

        assertEquals(64, recorder.pendingCount(), "队列容量就是上限（其余被丢弃并计数）");

        kotlin.Pair<Integer, Integer> result = recorder.flush();
        assertEquals(8, result.getFirst(), "一次刷写只取一批（batch-size）");
        assertEquals(56, recorder.pendingCount());
        assertTrue(repo.countSince(0) > before);

        // 把剩下的刷完
        for (int i = 0; i < 10; i++) {
            recorder.flush();
        }
        assertEquals(0, recorder.pendingCount());
        assertTrue(recorder.describe().contains("丢弃="), "必须能报出丢弃数：" + recorder.describe());
    }

    @Test
    @DisplayName("惩罚阶梯：整体替换后按 step 读回；再次替换是清空重写（旧档不会残留）")
    void punishmentLadderRoundTrip() {
        RuleRepository rules = new RuleRepository(pool);
        long now = System.currentTimeMillis();

        rules.replaceLadder(Arrays.asList(
                new LadderStep(1, 20.0, "kick", null, null),
                new LadderStep(2, 50.0, "ban", "7d", "封禁 7 天"),
                new LadderStep(3, 100.0, "ban", "perm", "永久封禁")), now);

        List<LadderStep> first = rules.loadLadder();
        assertEquals(3, first.size());
        assertEquals(1, first.get(0).getStep());
        assertEquals(20.0, first.get(0).getMinVl(), 1e-9);
        assertEquals("kick", first.get(0).getAction());
        assertNull(first.get(0).getDuration(), "没写时长要读回 null（不是空串，也不是 0）");
        assertEquals("7d", first.get(1).getDuration());
        assertEquals("永久封禁", first.get(2).getReason());

        // 换成只剩一档：旧两档必须消失。否则"改小阶梯"会变成"阶梯越改越多"，
        // 而多出来的档位照样会被 selectStep 选中 —— 静默改变处罚力度。
        rules.replaceLadder(Collections.singletonList(new LadderStep(1, 30.0, "alert", null, null)), now);
        List<LadderStep> second = rules.loadLadder();
        assertEquals(1, second.size());
        assertEquals(30.0, second.get(0).getMinVl(), 1e-9);
        assertEquals("alert", second.get(0).getAction());
    }

    @Test
    @DisplayName("白名单补齐：生效的不重复加、名字大小写算同一条、过期占位必须释放后才能重加")
    void whitelistEnsureIsIdempotent() {
        RuleRepository rules = new RuleRepository(pool);
        long now = System.currentTimeMillis();
        UUID uuid = UUID.randomUUID();

        assertTrue(rules.ensureWhitelist(uuid, "Cklsit", "config.yml", "config.yml", now, null),
                "首次补齐应当真的插入");
        assertFalse(rules.ensureWhitelist(uuid, "Cklsit", "config.yml", "config.yml", now, null),
                "同目标再补一次不能多出一条：声明式配置每次启动/重载都会跑一遍");
        assertFalse(rules.ensureWhitelist(uuid, "cklsit", "另一份理由", "config.yml", now, null),
                "大小写不同仍算同一条：否则 config 里换个大小写就多出一条白名单");
        assertEquals(1, rules.countWhitelist(now));

        // 只有名字没有 uuid 的情形（还没进服的玩家）也必须能加白并被命中
        UUID other = UUID.randomUUID();
        assertTrue(rules.ensureWhitelist(null, "Builder", "建筑组", "config.yml", now, null));
        assertTrue(rules.isWhitelisted(other, "builder", now), "按名字匹配同样要大小写不敏感");
        assertEquals(2, rules.countWhitelist(now));

        // 过期条目：不参与计数；且**必须先释放它的唯一键占位**，否则同一目标永远加不回来
        UUID temp = UUID.randomUUID();
        assertTrue(rules.ensureWhitelist(temp, "TempGuy", null, "config.yml", now, now - 1_000L));
        assertEquals(2, rules.countWhitelist(now), "已过期的条目不参与计数");
        assertEquals(1, rules.expireWhitelist(now), "应当释放 1 个过期占位");
        assertTrue(rules.ensureWhitelist(temp, "TempGuy", null, "config.yml", now, null),
                "释放占位后要能重新加白（否则 config 里的永久条目会被一条过期记录永久挡住）");
        assertEquals(3, rules.countWhitelist(now));
    }

    // ------------------------------------------------------------------ 辅助

    private static ViolationInput violation(UUID uuid, String name, String check, double vl, double delta,
                                            int severity, long at, long dayBucket) {
        return new ViolationInput(uuid, name, check, "raytrace", vl, delta, severity, at, dayBucket,
                "Server-1", "world", 10.0, 64.0, -3.0, 42, 19.8f, "V_1_8", "INTERACT_ENTITY",
                "verbose", false, false, null);
    }
}
