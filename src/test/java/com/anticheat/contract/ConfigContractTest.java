package com.anticheat.contract;

import com.anticheat.ai.FeatureDimensions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 配置文件契约测试 —— 保证"代码读的配置项"与"随 jar 发布的默认配置"永远一致，
 * 并锁定各功能模块的关键设计不变量。
 *
 * <p>为什么需要它：{@code plugin.getConfig().getBoolean("xxx.enabled", true)} 这种写法
 * 在配置项不存在时<b>不会报错</b>，只是静默走默认值——于是"管理员关了开关却还在跑"
 * 这类问题能潜伏很久（本项目就出现过一次：代码读 {@code behavior-detection.enabled}，
 * 而 config.yml 里只有 {@code behavior:}）。这里把它变成编译期之后立刻可见的失败。
 */
class ConfigContractTest {

    private static Path configFile;
    private static Map<String, Object> config;

    @SuppressWarnings("unchecked")
    @BeforeAll
    static void loadConfig() throws IOException {
        configFile = Repo.mainResources().resolve("config.yml");
        assertTrue(Files.isRegularFile(configFile), "必须随 jar 发布默认 config.yml");
        config = new Yaml().load(Repo.read(configFile));
        assertNotNull(config, "config.yml 必须是合法 YAML 映射");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(String path) {
        Object cur = config;
        for (String seg : path.split("\\.")) {
            assertTrue(cur instanceof Map, "配置段不存在或不是映射：" + path);
            cur = ((Map<String, Object>) cur).get(seg);
            assertNotNull(cur, "配置段缺失：" + path);
        }
        return (Map<String, Object>) cur;
    }

    private static Object value(String path) {
        Object cur = config;
        for (String seg : path.split("\\.")) {
            assertTrue(cur instanceof Map, "配置段不存在：" + path);
            cur = ((Map<String, Object>) cur).get(seg);
            assertNotNull(cur, "配置项缺失：" + path);
        }
        return cur;
    }

    private static double dbl(String path) {
        return ((Number) value(path)).doubleValue();
    }

    private static boolean bool(String path) {
        return (Boolean) value(path);
    }

    @Test
    @DisplayName("顶层功能段齐全：删掉一整段会让功能静默失效，必须拦住")
    void topLevelSectionsExist() {
        Set<String> expected = new LinkedHashSet<>(java.util.Arrays.asList(
                // 2026-09-25：旧引擎（com.anticheat.detection + DetectionManager 等）
                // 整体移除，`detection:` 与 `fingerprint:` 两段随之删除。
                // 这两条是**刻意**去掉的，不是漏配——旧引擎的检测项已由核心层的
                // `core.checks.<名字>` 取代，此处保留旧段名反而会掩盖"配置写了不生效"。
                "notify", "ban", "database", "check-client", "bounty",
                "honeypot", "behavior", "ailab",
                "gui", "captcha"));
        Set<String> missing = new TreeSet<>();
        for (String key : expected) {
            if (!config.containsKey(key)) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), "config.yml 缺少顶层功能段：" + missing);
    }

    @Test
    @DisplayName("代码里读取的每个配置路径，其顶层段必须存在于 config.yml")
    void everyConfigPathReadByCodeExists() throws IOException {
        Pattern call = Pattern.compile(
                "getConfig\\(\\)\\s*\\.\\s*(?:getString|getBoolean|getInt|getLong|getDouble"
                        + "|getStringList|getIntegerList|getMapList|getList|getConfigurationSection"
                        + "|isSet|getKeys|getValues)\\s*\\(\\s*\"([^\"]+)\"");
        Set<String> roots = new LinkedHashSet<>();
        Set<String> offenders = new TreeSet<>();
        for (Path p : Repo.javaFiles()) {
            Matcher m = call.matcher(Repo.stripComments(Repo.read(p)));
            while (m.find()) {
                String path = m.group(1);
                String root = path.split("\\.")[0];
                roots.add(root);
                if (!config.containsKey(root)) {
                    offenders.add(Repo.root().relativize(p) + " -> " + path);
                }
            }
        }
        assumeTrue(!roots.isEmpty(), "未扫描到任何 getConfig() 调用，先检查本测试的扫描逻辑");
        assertTrue(offenders.isEmpty(),
                "以下配置路径在 config.yml 中不存在（会静默走代码默认值，管理员改了不生效）：\n  "
                        + String.join("\n  ", offenders)
                        + "\n修复：在 config.yml 补上对应段/项，或改正代码里的路径。");
    }

    @Test
    @DisplayName("AI 实验室：特征维度与融合权重不变量")
    void aiLabInvariants() {
        assertTrue(bool("ailab.enabled"), "AI 实验室默认开启（ailab.enabled=false 才是纯规则模式）");
        assertEquals(FeatureDimensions.DIMS, ((Number) value("ailab.features.dims")).intValue(),
                "config.yml 的 ailab.features.dims 必须与 FeatureDimensions.DIMS 一致，"
                        + "否则特征向量会错位，所有模型的分数都将失去意义");

        double p = dbl("ailab.fusion.personal-weight");
        double g = dbl("ailab.fusion.global-weight");
        double s = dbl("ailab.fusion.supervised-weight");
        assertEquals(1.0, p + g + s, 1e-6, "融合权重应归一（缺项会自动重归一，但默认值必须自洽）");
        assertTrue(p > 0 && g > 0 && s > 0, "三路权重都应大于 0");

        double watch = dbl("ailab.decision.watch-threshold");
        double alert = dbl("ailab.decision.alert-threshold");
        assertTrue(watch < alert, "watch 阈值必须低于 alert 阈值（否则静默标记直接等于告警）");
        assertTrue(alert <= 1.0 && watch >= 0.0, "决策阈值必须落在 [0,1]");

        assertTrue(((Number) value("ailab.forest.trees")).intValue() > 0, "森林树数必须为正");
        assertTrue(((Number) value("ailab.baseline.warmup-samples")).intValue() > 0,
                "warmup 样本数必须为正（否则新人立刻被个人基线误判）");
        assertTrue(((Number) value("ailab.supervised.min-labels")).intValue() >= 20,
                "首次训练最少标签数应 ≥20，样本太少训出的模型不可信");
    }

    @Test
    @DisplayName("验证码 TypeB：非对称罚分与阈值分离带不变量")
    void captchaMotionMimicryInvariants() {
        String base = "captcha.tasks.motion-mimicry";
        assertTrue(bool("captcha.tasks.direct-interaction.enabled")
                        || bool(base + ".enabled"),
                "验证码必须至少有一个可用检测项，否则玩家无法通过验证会被超时踢出");
        assertTrue(bool(base + ".enabled"), "动作模仿检测项默认启用");

        double missing = dbl(base + ".missing-penalty");
        double extra = dbl(base + ".extra-penalty");
        assertTrue(missing > extra,
                "漏做必须比多做严判（missing-penalty=" + missing
                        + " 必须 > extra-penalty=" + extra + "）");

        double maxDistance = dbl(base + ".max-distance");
        assertTrue(maxDistance > 0.05 && maxDistance < 0.5,
                "DTW 阈值应落在合理区间 (0.05, 0.5)，当前 " + maxDistance);

        int min = ((Number) value(base + ".min-actions")).intValue();
        int max = ((Number) value(base + ".max-actions")).intValue();
        assertTrue(min >= 2, "题目至少 2 步才有区分度，当前 min-actions=" + min);
        assertTrue(min <= max, "min-actions 不能大于 max-actions");

        assertTrue(((Number) value(base + ".max-attempts")).intValue() >= 1,
                "必须允许至少 1 次尝试");
        assertTrue(((Number) value("captcha.time-limit")).intValue() > 0, "验证码总时限必须为正");
    }

    @Test
    @DisplayName("GUI 配置项存在且类型正确")
    void guiConfig() {
        assertTrue(value("gui.enabled") instanceof Boolean, "gui.enabled 必须是布尔");
        assertFalse(String.valueOf(value("gui.filler-material")).trim().isEmpty(),
                "gui.filler-material 不能为空");
        assertFalse(String.valueOf(value("gui.title-prefix")).trim().isEmpty(), "gui.title-prefix 不能为空");
    }

    @Test
    @DisplayName("已下线能力不得回流：web / replay 配置段必须保持移除状态")
    void removedSectionsStayRemoved() {
        for (String section : new String[]{"web", "replay"}) {
            assertFalse(config.containsKey(section),
                    "config.yml 不应再出现 " + section + " 段——Web 面板与观察者回放已整体下线，"
                            + "重新引入会带来「声明了却不生效」的配置项");
        }
        assertNotNull(config.get("gui"), "gui 段必须保留（游戏内管理界面仍在用）");
    }

    @Test
    @DisplayName("config.yml 里声明的材质名都必须存在于 1.8.8（跨版本铁律）")
    void configuredMaterialsExistInLegacyApi() throws Exception {
        Path legacyJar = findLegacyApiJar();
        assumeTrue(legacyJar != null,
                "找不到 spigot-api 1.8.8 jar（首次构建会下载到 ~/.m2），跳过材质校验");

        List<String> materialKeys = new ArrayList<>();
        collectMaterialKeys(config, "", materialKeys);

        try (LegacyApiClassLoader cl = new LegacyApiClassLoader(
                new java.net.URL[]{legacyJar.toUri().toURL()},
                ConfigContractTest.class.getClassLoader())) {
            Class<?> material = Class.forName("org.bukkit.Material", false, cl);
            assertNotNull(material);
            List<String> bad = new ArrayList<>();
            for (String key : materialKeys) {
                try {
                    // valueOf 会在 1.8.8 的 Material 枚举里查找，找不到即抛 IllegalArgumentException
                    material.getMethod("valueOf", String.class).invoke(null, key);
                } catch (ReflectiveOperationException e) {
                    bad.add(key);
                } catch (LinkageError e) {
                    bad.add(key + "(linkage: " + e.getClass().getSimpleName() + ")");
                }
            }
            assertTrue(bad.isEmpty(),
                    "以下材质名在 1.8.8 的 Material 枚举里不存在，1.8 服务端会抛 NoSuchFieldError："
                            + bad + " —— 改用 VersionUtil.compatMaterial(现代名, 1.8名, fallback)");
        }
    }

    /**
     * 只对 {@code org.bukkit.*} 走 child-first（加载 1.8.8 jar 里的那份），
     * 其余（Guava、snakeyaml 等运行时依赖）仍委托给测试 JVM 的类加载器。
     * 这样既拿到真正的 1.8.8 API，又不会因缺依赖而 NoClassDefFound。
     */
    private static final class LegacyApiClassLoader extends java.net.URLClassLoader {

        LegacyApiClassLoader(java.net.URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("org.bukkit")) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        c = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(c);
                    }
                    return c;
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectMaterialKeys(Object node, String path, List<String> out) {
        if (node instanceof Map) {
            // YAML 里可能存在数字键（如 ban 段），所以键一律用 toString 而不是强转 String
            for (Map.Entry<?, ?> e : ((Map<?, ?>) node).entrySet()) {
                String key = String.valueOf(e.getKey());
                String childPath = path.isEmpty() ? key : path + "." + key;
                if (key.endsWith("material") || key.endsWith("material-name")
                        || key.equals("material") || key.endsWith("-material")) {
                    Object v = e.getValue();
                    if (v instanceof String && !((String) v).trim().isEmpty()) {
                        out.add(((String) v).trim().toUpperCase(java.util.Locale.ROOT));
                    }
                }
                collectMaterialKeys(e.getValue(), childPath, out);
            }
        } else if (node instanceof List) {
            for (Object item : (List<Object>) node) {
                collectMaterialKeys(item, path, out);
            }
        }
    }

    /** 在本地 Maven 仓库里找 spigot-api 1.8.8。 */
    private static Path findLegacyApiJar() throws IOException {
        List<Path> roots = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (home != null) {
            roots.add(java.nio.file.Paths.get(home, ".m2", "repository", "org", "spigotmc", "spigot-api"));
            roots.add(java.nio.file.Paths.get(home, ".m2", "repository", "org", "bukkit", "bukkit"));
        }
        for (Path r : roots) {
            if (!Files.isDirectory(r)) {
                continue;
            }
            try (java.util.stream.Stream<Path> s = Files.walk(r, 4)) {
                java.util.Optional<Path> hit = s
                        .filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .filter(p -> p.toString().contains("1.8.8"))
                        .filter(p -> !p.toString().contains("sources"))
                        .filter(p -> !p.toString().contains("javadoc"))
                        .findFirst();
                if (hit.isPresent()) {
                    return hit.get();
                }
            }
        }
        return null;
    }
}
