package com.anticheat.contract;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * plugin.yml 契约测试。
 *
 * <p>Bukkit 的失败模式非常隐蔽：命令类写好了但忘了注册/忘了写进 plugin.yml，
 * 服务端<b>不报任何错</b>，只是这个命令永远不存在；权限节点拼错同理，
 * 管理员配了权限却不生效。这里用静态契约把这些全部变成 CI 失败。
 */
class PluginYmlContractTest {

    private static Map<String, Object> pluginYml;
    private static Path pluginYmlFile;

    @SuppressWarnings("unchecked")
    @BeforeAll
    static void load() throws IOException {
        pluginYmlFile = Repo.root().resolve("plugin.yml");
        assertTrue(Files.isRegularFile(pluginYmlFile), "仓库根必须有 plugin.yml（pom 会把它打进 jar）");
        pluginYml = new Yaml().load(Repo.read(pluginYmlFile));
        assertNotNull(pluginYml, "plugin.yml 必须是合法 YAML");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(String key) {
        Object v = pluginYml.get(key);
        assertTrue(v instanceof Map, "plugin.yml 缺少 " + key + " 段");
        return (Map<String, Object>) v;
    }

    @Test
    @DisplayName("主类存在且与文件名一致")
    void mainClassExists() {
        String main = String.valueOf(pluginYml.get("main"));
        assertTrue(!main.isEmpty() && main.contains("."), "main 必须是全限定类名，实际：" + main);
        Path file = Repo.mainJava().resolve(main.replace('.', '/') + ".java");
        assertTrue(Files.isRegularFile(file),
                "plugin.yml 声明的 main 类源码不存在：" + file);
        assertEquals(main.substring(main.lastIndexOf('.') + 1),
                file.getFileName().toString().replace(".java", ""),
                "main 类名与文件名必须一致");
    }

    @Test
    @DisplayName("version 必须与 pom.xml 一致")
    void versionMatchesPom() throws IOException {
        String pom = Repo.read(Repo.root().resolve("pom.xml"));
        Matcher m = Pattern.compile("<artifactId>AdvancedAntiCheat</artifactId>\\s*<version>([^<]+)</version>")
                .matcher(pom);
        assertTrue(m.find(), "pom.xml 里找不到项目版本");
        String pomVersion = m.group(1).trim();
        assertEquals(pomVersion, String.valueOf(pluginYml.get("version")).trim(),
                "plugin.yml 的 version 必须与 pom.xml 同步（否则控制台版本号与制品会不一致）");
    }

    @Test
    @DisplayName("api-version 声明正确（1.13+ 服务端必需）")
    void apiVersionDeclared() {
        Object v = pluginYml.get("api-version");
        assertNotNull(v, "缺少 api-version：Paper 1.13+ 会按最老行为加载并告警");
        String s = String.valueOf(v);
        assertTrue(s.matches("\\d+\\.\\d+.*"), "api-version 格式应为 1.x，实际：" + s);
    }

    @Test
    @DisplayName("每个命令都声明了权限，且该权限在 permissions 段有定义")
    void commandPermissionsDeclared() {
        Map<String, Object> commands = map("commands");
        Set<String> declaredPerms = new LinkedHashSet<>(map("permissions").keySet());
        Set<String> problems = new TreeSet<>();
        for (Map.Entry<String, Object> e : commands.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> def = (Map<String, Object>) e.getValue();
            Object perm = def.get("permission");
            if (perm == null || String.valueOf(perm).trim().isEmpty()) {
                problems.add(e.getKey() + " 未声明 permission（命令会被任何人执行）");
            } else if (!declaredPerms.contains(String.valueOf(perm))) {
                problems.add(e.getKey() + " 引用了未定义的权限 " + perm);
            }
            if (def.get("usage") == null) {
                problems.add(e.getKey() + " 未声明 usage（/help 里会显示 null）");
            }
            if (def.get("description") == null) {
                problems.add(e.getKey() + " 未声明 description");
            }
        }
        assertTrue(problems.isEmpty(), "plugin.yml 命令定义有问题：\n  " + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("每个声明的命令名都真的在源码里注册（防止 plugin.yml 写了但代码没注册）")
    void commandsAreActuallyRegistered() throws IOException {
        Set<String> allLiterals = new LinkedHashSet<>();
        for (Path p : Repo.javaFiles()) {
            allLiterals.addAll(Repo.stringLiterals(p));
        }
        Set<String> unregistered = new TreeSet<>();
        for (String cmd : map("commands").keySet()) {
            if (!allLiterals.contains(cmd)) {
                unregistered.add(cmd);
            }
        }
        assertTrue(unregistered.isEmpty(),
                "以下命令在 plugin.yml 中声明，但源码里找不到任何注册痕迹："
                        + unregistered + "\n修复：注册命令或从 plugin.yml 移除。");
    }

    @Test
    @DisplayName("命令类都被实例化（新增命令类忘了注册会在这里暴露）")
    void commandClassesAreInstantiated() throws IOException {
        Path commandsDir = Repo.mainJava().resolve("com/anticheat/commands");
        assertTrue(Files.isDirectory(commandsDir), "必须有命令包");

        List<Path> sources = Repo.javaFiles();
        List<String> unreferenced = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(commandsDir)) {
            List<Path> classes = new ArrayList<>();
            s.filter(p -> p.toString().endsWith(".java")).forEach(classes::add);
            for (Path cls : classes) {
                String simple = cls.getFileName().toString().replace(".java", "");
                if (simple.startsWith("Abstract") || simple.endsWith("Base")) {
                    continue;
                }
                boolean referenced = false;
                for (Path src : sources) {
                    if (src.equals(cls)) {
                        continue;
                    }
                    if (Repo.read(src).contains("new " + simple + "(")) {
                        referenced = true;
                        break;
                    }
                }
                if (!referenced) {
                    unreferenced.add(simple);
                }
            }
        }
        assertTrue(unreferenced.isEmpty(),
                "以下命令类没有被任何地方实例化（写了但没注册，玩家永远用不了）：" + unreferenced);
    }

    @Test
    @DisplayName("每个声明的权限节点都在源码中被引用（防止僵尸权限节点）")
    void permissionsAreReferenced() throws IOException {
        Set<String> allLiterals = new LinkedHashSet<>();
        for (Path p : Repo.javaFiles()) {
            allLiterals.addAll(Repo.stringLiterals(p));
        }
        Set<String> declared = map("permissions").keySet();
        Set<String> stale = new TreeSet<>();
        for (String perm : declared) {
            if (allLiterals.contains(perm)) {
                continue;
            }
            // 父节点（其它权限节点的前缀）只是分组用，代码里不会直接引用
            boolean isParent = false;
            for (String other : declared) {
                if (!other.equals(perm) && other.startsWith(perm + ".")) {
                    isParent = true;
                    break;
                }
            }
            if (!isParent) {
                stale.add(perm);
            }
        }
        assertTrue(stale.isEmpty(),
                "以下权限节点在 plugin.yml 中声明，但源码里从未引用（配了也没用）：" + stale);
    }

    @Test
    @DisplayName("插件元数据完整（name / description / author）")
    void metadataPresent() {
        for (String key : new String[]{"name", "description", "author"}) {
            Object v = pluginYml.get(key);
            assertTrue(v != null && !String.valueOf(v).trim().isEmpty(),
                    "plugin.yml 缺少 " + key);
        }
        assertEquals("AdvancedAntiCheat", String.valueOf(pluginYml.get("name")),
                "插件名变了会导致数据目录/配置目录改名，属于破坏性变更");
    }
}
