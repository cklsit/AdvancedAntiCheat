package com.anticheat.contract;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核心层「检测 ↔ 配置」契约测试。
 *
 * <p>为什么需要单独一个测试类：{@link ConfigContractTest} 只扫 `*.java`，
 * 而核心层是 **Kotlin** 写的——它对 `core.checks.*` 的读取完全落在扫描盲区里。
 * 结果是「新增一个检测、忘了在 config.yml 里加 `core.checks.<名字>`」
 * 不会触发任何失败：检测照常运行，但管理员既看不到它、也关不掉它。
 * 本测试把这个盲区补上。</p>
 *
 * <p>双向校验：</p>
 * <ol>
 *   <li>代码里 `@CheckData(name = "X")` 声明的每个名字，config.yml 的
 *       `core.checks` 里必须有同名条目（否则管理员管不到它）；</li>
 *   <li>config.yml 里的每个条目，代码里必须有同名检测（否则是残留的失效配置，
 *       会让人误以为某个能力还开着）。</li>
 * </ol>
 */
class CoreCheckContractTest {

    /** 检测实现所在目录（Kotlin 源码）。 */
    private static final Path CHECK_IMPL_DIR = Repo.mainJava()
            .resolve("com/anticheat/core/check/impl");

    /**
     * 匹配 `@CheckData(...)` 注解体（允许跨行），后接可选的其它注解与 `class`。
     *
     * <p>用「注解后必须紧跟 class」这个约束把匹配范围钉死在**类声明**上：
     * 只匹配 `@CheckData(...)` 会连 KDoc 里的示例、辅助类上的误写一起命中，
     * 而那些地方没有 `class` 跟着。</p>
     */
    private static final Pattern CHECK_DATA =
            Pattern.compile("@CheckData\\s*\\((.*?)\\)\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*class\\s",
                    Pattern.DOTALL);

    /** 从注解体里取 name = "..."。 */
    private static final Pattern NAME_ATTR = Pattern.compile("name\\s*=\\s*\"([^\"]+)\"");

    private static Set<String> declaredInCode;

    @SuppressWarnings("unchecked")
    private static Set<String> configuredInYaml() throws IOException {
        Map<String, Object> config = new Yaml().load(
                Repo.read(Repo.mainResources().resolve("config.yml")));
        Object core = config.get("core");
        assertTrue(core instanceof Map, "config.yml 必须存在 core: 段");
        Object checks = ((Map<String, Object>) core).get("checks");
        assertTrue(checks instanceof Map, "config.yml 必须存在 core.checks: 段");
        return new LinkedHashSet<>(((Map<String, Object>) checks).keySet().stream()
                .map(String::valueOf).toList());
    }

    @BeforeAll
    static void scanCode() throws IOException {
        declaredInCode = new LinkedHashSet<>();
        assertTrue(Files.isDirectory(CHECK_IMPL_DIR),
                "检测实现目录不存在：" + CHECK_IMPL_DIR);

        try (Stream<Path> stream = Files.walk(CHECK_IMPL_DIR)) {
            List<Path> kotlinFiles = stream
                    .filter(p -> p.toString().endsWith(".kt"))
                    .toList();
            assertFalse(kotlinFiles.isEmpty(), "没有扫描到任何 Kotlin 检测文件，先检查本测试的扫描逻辑");

            for (Path file : kotlinFiles) {
                String src = Repo.stripComments(Repo.read(file));
                Matcher m = CHECK_DATA.matcher(src);
                while (m.find()) {
                    Matcher name = NAME_ATTR.matcher(m.group(1));
                    assertTrue(name.find(),
                            file.getFileName() + " 的 @CheckData 没有写 name，"
                                    + "会导致 checkName 退化成类名、配置键与代码不一致");
                    declaredInCode.add(name.group(1));
                }
            }
        }
    }

    @Test
    @DisplayName("代码里的每个检测都必须在 config.yml 的 core.checks 里可配")
    void everyCheckIsConfigurable() throws IOException {
        Set<String> configured = configuredInYaml();
        Set<String> missing = new TreeSet<>();
        for (String name : declaredInCode) {
            if (!configured.contains(name)) {
                missing.add(name);
            }
        }
        assertTrue(missing.isEmpty(),
                "以下检测在代码里存在，但 config.yml 的 core.checks 里没有条目——"
                        + "管理员将无法开关或调参，且完全看不到它的存在：" + missing
                        + "\n修复：在 config.yml 的 core.checks 下补上同名条目。");
    }

    @Test
    @DisplayName("config.yml 里的每个 core.checks 条目都必须对应一个真实检测")
    void everyConfiguredEntryHasACheck() throws IOException {
        Set<String> configured = configuredInYaml();
        Set<String> orphans = new TreeSet<>();
        for (String name : configured) {
            if (!declaredInCode.contains(name)) {
                orphans.add(name);
            }
        }
        assertTrue(orphans.isEmpty(),
                "config.yml 的 core.checks 里有以下条目，但代码里找不到同名检测——"
                        + "这是残留配置，会让人误以为该能力仍然存在：" + orphans
                        + "\n修复：删掉配置条目，或把检测名改回一致。");
    }

    @Test
    @DisplayName("检测名不得重复，且必须与类名一致（便于排障时一眼定位）")
    void checkNamesAreUniqueAndMatchClassName() throws IOException {
        Map<String, String> nameToFile = new java.util.LinkedHashMap<>();
        Set<String> duplicates = new TreeSet<>();
        Set<String> mismatches = new TreeSet<>();

        try (Stream<Path> stream = Files.walk(CHECK_IMPL_DIR)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".kt")).toList()) {
                String src = Repo.stripComments(Repo.read(file));
                Matcher m = CHECK_DATA.matcher(src);
                while (m.find()) {
                    Matcher name = NAME_ATTR.matcher(m.group(1));
                    if (!name.find()) {
                        continue;
                    }
                    String checkName = name.group(1);
                    String simpleFile = file.getFileName().toString().replace(".kt", "");

                    String previous = nameToFile.put(checkName, file.getFileName().toString());
                    if (previous != null) {
                        duplicates.add(checkName + " (" + previous + " 与 " + file.getFileName() + ")");
                    }
                    if (!checkName.equals(simpleFile)) {
                        mismatches.add(checkName + " -> 文件 " + file.getFileName());
                    }
                }
            }
        }

        assertTrue(duplicates.isEmpty(),
                "检测名重复会让配置项互相覆盖，且告警里无法区分是哪一个：" + duplicates);
        assertTrue(mismatches.isEmpty(),
                "检测名与文件名不一致（排障时要多跳一次，且容易改错文件）：" + mismatches
                        + "\n约定：一个检测一个文件，文件名与 @CheckData(name) 一致。");
    }

    @Test
    @DisplayName("检测分组目录只用小写 ASCII（跨平台路径安全）")
    void groupDirectoriesAreAsciiSafe() throws IOException {
        Set<String> groups = new TreeSet<>();
        try (Stream<Path> stream = Files.walk(CHECK_IMPL_DIR, 1)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> !p.equals(CHECK_IMPL_DIR))
                    .forEach(p -> groups.add(p.getFileName().toString()));
        }
        assertFalse(groups.isEmpty(), "check/impl 下必须有分组目录");

        Set<String> bad = new TreeSet<>();
        for (String group : groups) {
            if (!group.equals(group.toLowerCase(Locale.ROOT))
                    || !group.matches("[a-z0-9_]+")) {
                bad.add(group);
            }
        }
        assertTrue(bad.isEmpty(),
                "分组目录名必须是小写 ASCII（本机历史上出过中文/全角路径导致的编码问题）：" + bad);
    }
}
