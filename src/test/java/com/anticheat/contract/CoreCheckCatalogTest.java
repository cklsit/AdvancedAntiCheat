package com.anticheat.contract;

import com.anticheat.core.check.Check;
import com.anticheat.core.check.CheckData;
import com.anticheat.core.manager.CheckManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检测目录（[CheckManager.CHECK_CLASSES]）与 `config.yml` 的 `core.checks` 双向一致性。
 *
 * <h3>为什么需要这个测试</h3>
 * 核心层有**三份**"有哪些检测"的名单：
 * 1. `CheckManager` 构造里的 `register(Xxx(player))`（实例登记，每玩家一份）；
 * 2. `CheckManager.CHECK_CLASSES`（类目录，给数据库规则登记用，**不依赖玩家**）；
 * 3. `config.yml` 的 `core.checks`（管理员能看到/开关的清单）。
 *
 * <p>三者必须一致，但只有第 1 份是"跑起来才会有反应"的：漏了第 2 份的后果是
 * "某个检测在数据库规则表里查不到"（空服时管理员改不了它的阈值），
 * 漏了第 3 份的后果是"新增的检测管理员看不见也关不掉"。两种都不会报错。</p>
 *
 * <p>`CoreCheckContractTest` 已经守住了"源码里的 `@CheckData` ↔ config.yml"这一对；
 * 这里补上"类目录 ↔ config.yml"这一对——也就是新加检测时最容易忘的那一步。</p>
 */
class CoreCheckCatalogTest {

    @Test
    @DisplayName("CHECK_CLASSES 非空且没有重复")
    void catalogIsSane() {
        List<Class<? extends Check>> classes = CheckManager.Companion.getCHECK_CLASSES();
        assertNotNull(classes);
        assertFalse(classes.isEmpty(), "检测目录不能为空");
        Set<Class<?>> unique = new LinkedHashSet<>(classes);
        assertEquals(classes.size(), unique.size(), "检测目录里有重复的类");
    }

    @Test
    @DisplayName("类目录的 @CheckData 名字集合 == config.yml 的 core.checks 键集合（双向）")
    void catalogMatchesConfig() {
        Set<String> fromClasses = new TreeSet<>();
        for (Class<? extends Check> type : CheckManager.Companion.getCHECK_CLASSES()) {
            CheckData data = type.getAnnotation(CheckData.class);
            assertNotNull(data, type.getSimpleName() + " 缺少 @CheckData 注解（规则表拿不到键名）");
            fromClasses.add(data.name());
        }

        Set<String> fromConfig = new TreeSet<>(readCheckKeys());
        assertFalse(fromConfig.isEmpty(), "config.yml 里读不到 core.checks（文件结构变了？）");

        Set<String> onlyInClasses = new TreeSet<>(fromClasses);
        onlyInClasses.removeAll(fromConfig);
        Set<String> onlyInConfig = new TreeSet<>(fromConfig);
        onlyInConfig.removeAll(fromClasses);

        assertTrue(onlyInClasses.isEmpty(),
                "这些检测在类目录里、但 config.yml 的 core.checks 里没有（管理员看不见也关不掉）: " + onlyInClasses);
        assertTrue(onlyInConfig.isEmpty(),
                "config.yml 里有、但类目录里没有（新增检测时忘了加进 CheckManager.CHECK_CLASSES）: " + onlyInConfig);
        assertEquals(fromClasses, fromConfig);
    }

    @Test
    @DisplayName("目录里的类名与 @CheckData 的 name 一致（便于按类名搜代码）")
    void classNameMatchesCheckName() {
        for (Class<? extends Check> type : CheckManager.Companion.getCHECK_CLASSES()) {
            CheckData data = type.getAnnotation(CheckData.class);
            assertNotNull(data);
            assertEquals(type.getSimpleName(), data.name(),
                    type.getSimpleName() + " 的 @CheckData.name 与之不一致，会让排障时按类名搜不到");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> readCheckKeys() {
        try (InputStream in = CoreCheckCatalogTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in, "拿不到打包进来的 config.yml");
            Map<String, Object> root = new Yaml().load(in);
            Object core = root.get("core");
            assertTrue(core instanceof Map, "config.yml 里没有 core 段");
            Object checks = ((Map<String, Object>) core).get("checks");
            assertTrue(checks instanceof Map, "config.yml 里没有 core.checks 段");
            return new ArrayList<>(((Map<String, Object>) checks).keySet());
        } catch (Exception e) {
            throw new AssertionError("解析 config.yml 失败: " + e.getMessage(), e);
        }
    }
}
