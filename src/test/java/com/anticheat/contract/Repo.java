package com.anticheat.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 源码/配置/命令契约测试的公共工具。
 *
 * <p>这些测试都直接读仓库里的文件（而不是 classpath 资源），
 * 目的就是让"改了代码忘了改配置 / 忘了注册命令"这类漂移在 CI 里立刻暴露。
 */
public final class Repo {

    private Repo() {
    }

    /** 从 user.dir 向上找到含 pom.xml 的目录作为仓库根。 */
    public static Path root() {
        Path cur = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 8 && cur != null; i++) {
            if (Files.isRegularFile(cur.resolve("pom.xml"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        throw new IllegalStateException("找不到仓库根（向上找不到 pom.xml），user.dir="
                + System.getProperty("user.dir"));
    }

    static Path mainResources() {
        return root().resolve("src/main/resources");
    }

    public static Path mainJava() {
        return root().resolve("src/main/java");
    }

    static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /** 递归收集 .java 源文件。 */
    static List<Path> javaFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.walk(mainJava())) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
        }
        return out;
    }

    /** 把源码里所有 Java 字符串字面量提取出来（去注释，避免文档里的示例被当成代码）。 */
    static Set<String> stringLiterals(Path javaFile) throws IOException {
        String src = stripComments(read(javaFile));
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(src);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** 去掉 // 行注释与块注释（近似，够用：只用于避免误报）。 */
    static String stripComments(String src) {
        StringBuilder sb = new StringBuilder(src.length());
        boolean inLine = false;
        boolean inBlock = false;
        boolean inString = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            char n = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    sb.append(c);
                }
                continue;
            }
            if (inBlock) {
                if (c == '*' && n == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                sb.append(c);
                if (c == '\\') {
                    if (n != '\0') {
                        sb.append(n);
                        i++;
                    }
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '/' && n == '/') {
                inLine = true;
                i++;
                continue;
            }
            if (c == '/' && n == '*') {
                inBlock = true;
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    static void assertContainsKey(String label, String path, String file) {
        assertTrue(path != null && !path.isEmpty(), label + " 必须存在（" + file + "）");
    }
}
