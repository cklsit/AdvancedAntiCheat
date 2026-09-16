package com.anticheat.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 前端 ↔ 后端接口契约测试。
 *
 * <p>前端调了一个后端没实现的接口时，Vite/TS 编译<b>完全不会报错</b>，
 * 只有人点进那个页面才会看到 404——这种漂移通常在功能上线后才被发现。
 * 这里把两边的路由清单静态抽出来做交叉比对。
 *
 * <p>历史遗留的已知漂移记在 {@code tools/ci/api-contract-allowlist.txt}，
 * 该文件只允许缩小、不允许扩大；新增漂移会直接导致 CI 失败。
 */
class WebApiContractTest {

    private static final Path ALLOWLIST = Path.of("tools", "ci", "api-contract-allowlist.txt");

    /** 后端：app.get("/api/x", ...) → (GET, /api/x) */
    private static final Pattern BACKEND = Pattern.compile(
            "app\\.(get|post|put|delete|patch|ws)\\(\\s*\"([^\"]+)\"");

    /** 前端：request.get<X>(`/x/${id}`) → 需要先定位方法再定位路径字面量 */
    private static final Pattern FRONT_METHOD = Pattern.compile(
            "\\.(get|post|put|delete|patch)\\s*(?:<[^>]*>)?\\s*\\(");

    private record Route(String method, String path) {
        @Override
        public String toString() {
            return method.toUpperCase(java.util.Locale.ROOT) + " " + path;
        }
    }

    private static List<Path> frontendApiFiles() throws IOException {
        Path dir = Repo.root().resolve("web-panel/src");
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.toString();
                        return n.endsWith(".ts") && !n.endsWith(".d.ts");
                    })
                    .filter(p -> p.toString().contains("api") || p.toString().contains("composables"))
                    .forEach(out::add);
        }
        return out;
    }

    private static Set<Route> backendRoutes() throws IOException {
        Set<Route> out = new LinkedHashSet<>();
        for (Path p : Repo.javaFiles()) {
            Matcher m = BACKEND.matcher(Repo.stripComments(Repo.read(p)));
            while (m.find()) {
                out.add(new Route(m.group(1), m.group(2)));
            }
        }
        return out;
    }

    private static Set<Route> frontendRoutes() throws IOException {
        Set<Route> out = new LinkedHashSet<>();
        for (Path file : frontendApiFiles()) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                Matcher method = FRONT_METHOD.matcher(line);
                if (!method.find()) {
                    continue;
                }
                // 路径字面量可能落在同一行，也可能落在后续几行（多行调用）
                String rest = line.substring(method.end());
                String found = extract(rest);
                int scanned = 0;
                while (found == null && ++scanned <= 4 && i + scanned < lines.size()) {
                    found = extract(lines.get(i + scanned));
                }
                if (found == null) {
                    continue;
                }
                out.add(new Route(method.group(1), normalizeFrontend(found)));
            }
        }
        return out;
    }

    /**
     * 从一段文本里取出第一个「以 / 开头」的字符串字面量（成对的 ' " ` 作定界符）。
     * 用定界符配对而不是字符类，才能正确吃下 `${encodeURIComponent(id)}` 这类含括号的模板。
     */
    private static String extract(String text) {
        for (int i = 0; i + 1 < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '`' && c != '\'' && c != '"') {
                continue;
            }
            if (text.charAt(i + 1) != '/') {
                continue;
            }
            int end = text.indexOf(c, i + 1);
            if (end < 0) {
                end = text.length();
            }
            return text.substring(i + 1, end);
        }
        return null;
    }

    /** /x/${id}/y?z=1 → /api/x/{}/y；`/notifications${search ? ...}` → /api/notifications */
    private static String normalizeFrontend(String raw) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            int dollar = raw.indexOf("${", i);
            if (dollar < 0) {
                sb.append(raw, i, raw.length());
                break;
            }
            sb.append(raw, i, dollar);
            // 模板表达式：${...} 或 ${func(x) 这类被 ) 截断的写法
            int end = raw.indexOf('}', dollar);
            int after = end < 0 ? raw.length() : end + 1;
            boolean pathParam = dollar > 0 && raw.charAt(dollar - 1) == '/';
            if (pathParam) {
                sb.append("{}");
            }
            // 非路径位置（例如直接拼查询串 `${search ? '?' + search : ''}`）整体丢弃
            i = after;
        }
        String path = sb.toString();
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!path.startsWith("/api")) {
            path = "/api" + path;
        }
        return path;
    }

    private static boolean matches(Route front, Set<Route> backend) {
        for (Route be : backend) {
            if (!be.method().equalsIgnoreCase(front.method())) {
                continue;
            }
            if (segmentsMatch(front.path(), be.path())) {
                return true;
            }
        }
        return false;
    }

    private static boolean segmentsMatch(String a, String b) {
        String[] as = a.split("/");
        String[] bs = b.split("/");
        if (as.length != bs.length) {
            return false;
        }
        for (int i = 0; i < as.length; i++) {
            if (as[i].equals(bs[i])) {
                continue;
            }
            boolean aParam = as[i].equals("{}");
            boolean bParam = bs[i].startsWith("{") && bs[i].endsWith("}");
            if (!aParam && !bParam) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> allowlist() throws IOException {
        Path file = Repo.root().resolve(ALLOWLIST);
        Set<String> out = new TreeSet<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        for (String line : Files.readAllLines(file)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            out.add(t.toUpperCase(java.util.Locale.ROOT));
        }
        return out;
    }

    @Test
    @DisplayName("静态抽取健全性：两侧都必须抽到路由，否则本测试是假绿")
    void extractionIsSane() throws IOException {
        Set<Route> be = backendRoutes();
        Set<Route> fe = frontendRoutes();
        assertTrue(be.size() > 30, "后端应抽到 30+ 条路由，实际 " + be.size());
        assertTrue(fe.size() > 20, "前端应抽到 20+ 条接口调用，实际 " + fe.size());
    }

    @Test
    @DisplayName("前端调用的每个接口都必须在后端注册（方法 + 路径）")
    void frontendCallsExistingBackendRoutes() throws IOException {
        Set<Route> backend = backendRoutes();
        Set<String> allowed = allowlist();
        Set<String> drift = new TreeSet<>();
        for (Route fe : frontendRoutes()) {
            if (matches(fe, backend)) {
                continue;
            }
            if (allowed.contains(fe.toString().toUpperCase(java.util.Locale.ROOT))) {
                continue;
            }
            drift.add(fe.toString());
        }
        assertTrue(drift.isEmpty(),
                "前端调用了后端未注册的接口（运行期 404）：\n  " + String.join("\n  ", drift)
                        + "\n修复：在后端 Handler 里注册对应路由；确属历史遗留则显式登记到 "
                        + ALLOWLIST + "（只允许缩小）。");
    }

    @Test
    @DisplayName("allowlist 里的条目必须仍然是失效的（删掉已修好的条目，防止文件腐烂）")
    void allowlistHasNoStaleEntries() throws IOException {
        Set<String> allowed = allowlist();
        assumeTrue(!allowed.isEmpty(), "allowlist 为空，跳过腐烂检查");
        Set<Route> backend = backendRoutes();
        Set<String> feStrings = new LinkedHashSet<>();
        for (Route r : frontendRoutes()) {
            feStrings.add(r.toString().toUpperCase(java.util.Locale.ROOT));
        }
        Set<String> problems = new TreeSet<>();
        for (String entry : allowed) {
            int sp = entry.indexOf(' ');
            if (sp < 0) {
                problems.add("格式错误（应为 METHOD /path）：" + entry);
                continue;
            }
            String method = entry.substring(0, sp);
            String path = entry.substring(sp + 1).trim();
            if (!feStrings.contains(entry)) {
                problems.add("前端已不再调用该接口，条目应删除：" + entry);
            } else if (matches(new Route(method, path), backend)) {
                problems.add("后端已实现该接口，条目应删除：" + entry);
            }
        }
        assertTrue(problems.isEmpty(), "api-contract-allowlist.txt 需要维护：\n  "
                + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("前端 WS 端点与后端一致")
    void websocketEndpointConsistent() throws IOException {
        Set<Route> backend = backendRoutes();
        boolean hasAppWs = backend.stream().anyMatch(r -> r.path().equals("/ws"));
        boolean hasReplayWs = backend.stream().anyMatch(r -> r.path().equals("/ws/replay/{targetUuid}"));
        assertTrue(hasAppWs, "后端必须注册 /ws（面板实时推送）");
        assertTrue(hasReplayWs, "后端必须注册 /ws/replay/{targetUuid}（违规回放帧推送）");
    }
}
