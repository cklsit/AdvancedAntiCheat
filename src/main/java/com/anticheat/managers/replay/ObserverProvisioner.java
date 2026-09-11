package com.anticheat.managers.replay;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * ObserverProvisioner —— 违规回放「方案 A」的观察者自动部署器。
 *
 * <p>设计目标：管理员只需要把插件 jar 丢进 plugins/，本类自动完成观察者集群的部署。
 * 无需正版账号：观察者使用离线 UUID + 假 accessToken 登录，因此要求服务端
 * {@code online-mode=false}（离线模式）；若服务端开启正版验证会在预检阶段拦下并说明。</p>
 *
 * <p>部署管线（全部在异步线程执行，绝不阻塞主线程）：</p>
 * <ol>
 *   <li>预检：Docker 可用性 + 服务端离线模式 + 资源可写</li>
 *   <li>释放镜像内自带的 observer 构建文件到 {@code plugins/AdvancedAntiCheat/observer/}</li>
 *   <li>准备 Temurin JRE 8（构建 MC 1.8.8 客户端所需，缺失则按多个源下载并解压）</li>
 *   <li>按当前环境生成 docker-compose.yml（HLS 目录、服务端地址、端口、内存限制）</li>
 *   <li>{@code docker compose build}（首次较久，有层缓存后很快）</li>
 *   <li>{@code docker compose up -d} 并轮询每个 observer 的 /status 直到就绪</li>
 * </ol>
 *
 * <p>当宿主没有 Docker 时，本类只做「提示 + 等待管理员选择」，不会自动安装，
 * 也不会让插件其他功能受影响：</p>
 * <ul>
 *   <li>A. 安装 Docker：{@code /ac replay docker install}（Linux 自动，其它平台给链接）</li>
 *   <li>B. 禁用该功能：{@code /ac replay disable}</li>
 * </ul>
 */
public class ObserverProvisioner {

    /** Docker 检测结果 */
    public enum DockerState {
        AVAILABLE("Docker 可用"),
        NOT_INSTALLED("未安装 Docker（找不到 docker 命令）"),
        DAEMON_DOWN("已安装 Docker，但守护进程未运行"),
        PERMISSION_DENIED("当前用户无权访问 Docker（需要加入 docker 组或用 root）"),
        ERROR("Docker 检测失败");

        public final String desc;

        DockerState(String desc) {
            this.desc = desc;
        }
    }

    /** 功能状态 */
    public enum FeatureState {
        DISABLED("已禁用"),
        NEEDS_DECISION("等待管理员选择（安装 Docker / 禁用功能）"),
        PROVISIONING("正在部署观察者集群"),
        RUNNING("运行中"),
        FAILED("部署失败");

        public final String desc;

        FeatureState(String desc) {
            this.desc = desc;
        }
    }

    /** 随 jar 一起打包的 observer 构建文件 */
    private static final String[] BUNDLED_FILES = {
            "Dockerfile", "entrypoint.sh", "healthcheck.sh",
            "run-mc.sh", "observerctl.py", "xorg-dummy.conf"
    };

    /**
     * Temurin JRE 8 下载源（按顺序回退），仅用于构建 MC 1.8.8 客户端运行时。
     * <p>国内镜像必须排在最前：api.adoptium.net 会 302 到 GitHub Releases，
     * 国内实测常被限速到 1~2 MB/min（40MB 包要半小时以上），
     * 而清华 TUNA 镜像实测 20 秒内即可下完。</p>
     */
    private static final String[] DEFAULT_JRE_URLS = {
            "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/8/jre/x64/linux/OpenJDK8U-jre_x64_linux_hotspot_8u504b01.tar.gz",
            "https://mirrors.ustc.edu.cn/adoptium/8/jre/x64/linux/OpenJDK8U-jre_x64_linux_hotspot_8u504b01.tar.gz",
            "https://api.adoptium.net/v3/binary/latest/8/ga/linux/x64/jre/hotspot/normal/eclipse"
    };

    private final AdvancedAntiCheat plugin;
    private final AtomicReference<FeatureState> state = new AtomicReference<>(FeatureState.NEEDS_DECISION);
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "aac-observer-provisioner");
                t.setDaemon(true);
                return t;
            });

    private volatile DockerState dockerState = DockerState.ERROR;
    private volatile String lastError = "";
    private volatile String composeMode = null; // "docker compose" | "docker-compose"

    public ObserverProvisioner(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    // ==================================================================
    // 配置读取
    // ==================================================================

    private String cfg(String key, String def) {
        String v = plugin.getConfig().getString(key, def);
        return v == null ? def : v.trim();
    }

    private int cfgInt(String key, int def) {
        return plugin.getConfig().getInt(key, def);
    }

    private boolean cfgBool(String key, boolean def) {
        return plugin.getConfig().getBoolean(key, def);
    }

    /** 观察者供给方式，docker=启用本部署器；其它值视为不启用 */
    private boolean provisionerEnabled() {
        return "docker".equalsIgnoreCase(cfg("replay.observer.provisioner", "docker"));
    }

    private String imageName() {
        return cfg("replay.observer.image", "replay-observer:latest");
    }

    /** observer 工作目录：plugins/AdvancedAntiCheat/observer */
    private Path baseDir() {
        return new File(plugin.getDataFolder(), "observer").toPath().toAbsolutePath();
    }

    /** HLS 根目录（与 FfmpegManager 的 hlsRootDir 保持一致） */
    private Path hlsRoot() {
        return new File(plugin.getDataFolder(), cfg("replay.hls.dir", "hls")).toPath().toAbsolutePath();
    }

    /** 参与部署的 observer id 列表 */
    private List<Integer> observerIds() {
        List<Integer> ids = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            String url = plugin.getConfig().getString("replay.observer." + i + ".url", null);
            if (url != null && !url.trim().isEmpty()) {
                ids.add(i);
            }
        }
        if (ids.isEmpty()) {
            // 默认只部署 1 个观察者：观察者客户端是完整 MC 客户端 + 软件渲染，
            // 每多一个都是实打实的 CPU/内存开销（曾因 3 个实例压垮过 NAS）。
            ids.add(1);
        }
        return ids;
    }

    private int observerPort(int id) {
        String url = plugin.getConfig().getString("replay.observer." + id + ".url",
                "http://127.0.0.1:1808" + id);
        try {
            URI u = URI.create(url.trim());
            if (u.getPort() > 0) return u.getPort();
        } catch (Throwable ignored) {
        }
        return 18080 + id;
    }

    // ==================================================================
    // 状态查询
    // ==================================================================

    public FeatureState getState() {
        return state.get();
    }

    public DockerState getDockerState() {
        return dockerState;
    }

    public String getLastError() {
        return lastError;
    }

    /** /ac replay status 的文本输出 */
    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        out.add("§8──── §6违规回放 · 观察者部署 §8────");
        out.add("§7功能状态: §f" + state.get().desc);
        out.add("§7供给方式: §f" + cfg("replay.observer.provisioner", "docker"));
        out.add("§7Docker  : §f" + dockerState.desc);
        if (!lastError.isEmpty()) {
            out.add("§7最近错误: §c" + lastError);
        }
        out.add("§7工作目录: §f" + baseDir());
        out.add("§7HLS 目录: §f" + hlsRoot());
        out.add("§7服务端地址: §f" + resolveServerHost() + ":" + resolveServerPort());
        out.add("§7离线模式: §f" + (Bukkit.getOnlineMode() ? "§c否（正版验证）" : "§a是"));
        out.add("§7观察者  : §f" + observerIds());
        return out;
    }

    // ==================================================================
    // 入口：插件启用时调用
    // ==================================================================

    public void initAsync() {
        if (!provisionerEnabled()) {
            state.set(FeatureState.DISABLED);
            plugin.getLogger().info("[Replay][Provisioner] provisioner=" + cfg("replay.observer.provisioner", "docker")
                    + "，跳过观察者自动部署");
            return;
        }
        worker.submit(() -> {
            DockerState ds = probeDocker();
            dockerState = ds;
            if (ds == DockerState.AVAILABLE) {
                if (cfgBool("replay.observer.autoProvision", true)) {
                    bootstrap("插件启动自动部署");
                } else {
                    state.set(FeatureState.NEEDS_DECISION);
                    plugin.getLogger().info("[Replay][Provisioner] Docker 可用；autoProvision=false，"
                            + "如需部署请执行 /ac replay setup");
                }
            } else {
                state.set(FeatureState.NEEDS_DECISION);
                if (cfgBool("replay.observer.promptOnMissingDocker", true)) {
                    logDecisionPrompt(ds);
                    notifyAdmins(buildDecisionPromptText(ds));
                }
            }
        });
    }

    // ==================================================================
    // Docker 探测
    // ==================================================================

    /** 探测 docker 可用性（会缓存 composeMode） */
    public DockerState probeDocker() {
        // 1. docker 命令是否存在
        int rc = runQuiet(List.of("docker", "--version"));
        if (rc == -1) {
            return DockerState.NOT_INSTALLED;
        }
        if (rc != 0) {
            // 命令存在但执行失败，多半是权限问题
            return DockerState.PERMISSION_DENIED;
        }
        // 2. 守护进程是否在跑
        int rc2 = runQuiet(List.of("docker", "info", "--format", "{{.ServerVersion}}"));
        if (rc2 != 0) {
            return DockerState.DAEMON_DOWN;
        }
        // 3. 决定 compose 调用方式
        if (runQuiet(List.of("docker", "compose", "version")) == 0) {
            composeMode = "docker compose";
        } else if (runQuiet(List.of("docker-compose", "--version")) == 0) {
            composeMode = "docker-compose";
        } else {
            composeMode = null;
            return DockerState.ERROR;
        }
        return DockerState.AVAILABLE;
    }

    /** -1 = 命令不存在 */
    private int runQuiet(List<String> cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -2;
            }
            return p.exitValue();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -2;
        }
    }

    // ==================================================================
    // 预检
    // ==================================================================

    /**
     * 部署前预检。返回 null 表示通过，否则返回阻断原因（面向管理员的完整说明）。
     */
    private String preflight() {
        // 1. 正版验证：本方案使用离线账号，正版服无法登录
        if (Bukkit.getOnlineMode()) {
            return "服务端开启了正版验证（server.properties: online-mode=true）。\n"
                    + "本方案的观察者使用离线账号登录，因此只能在离线模式（online-mode=false）下工作。\n"
                    + "若必须保留正版验证，请改为在代理层（BungeeCord/Velocity）启用验证、\n"
                    + "让后端服保持离线模式，再执行 /ac replay setup。\n"
                    + "或者选择 B：/ac replay disable";
        }
        // 2. Docker
        DockerState ds = probeDocker();
        dockerState = ds;
        if (ds != DockerState.AVAILABLE) {
            return "Docker 不可用：" + ds.desc + "\n"
                    + "请先解决 Docker 后再执行 /ac replay setup（或用 /ac replay docker install 尝试自动安装）。";
        }
        return null;
    }

    // ==================================================================
    // 主流程
    // ==================================================================

    /** 执行完整部署流程（异步） */
    public void bootstrap(String reason) {
        worker.submit(() -> {
            state.set(FeatureState.PROVISIONING);
            lastError = "";
            try {
                plugin.getLogger().info("[Replay][Provisioner] 开始部署观察者集群（" + reason + "）...");

                String block = preflight();
                if (block != null) {
                    fail(block);
                    return;
                }

                Path base = baseDir();
                Files.createDirectories(base);

                // 1) 释放随 jar 打包的构建文件
                extractBundledFiles(base);

                // 2) 准备 JRE 8
                if (!prepareJre(base)) {
                    fail("Temurin JRE 8 准备失败，详见上方日志。可通过 replay.observer.jreUrl 指定镜像。");
                    return;
                }

                // 3) 生成 docker-compose.yml
                generateCompose(base);

                // 4) build
                plugin.getLogger().info("[Replay][Provisioner] 构建镜像（首次较慢，请耐心等待）...");
                int rcBuild = runCompose(base, List.of("build"), "build", 3600);
                if (rcBuild != 0) {
                    fail("docker compose build 失败（exit=" + rcBuild + "），详见上方日志。");
                    return;
                }

                // 5) up -d
                plugin.getLogger().info("[Replay][Provisioner] 启动观察者容器...");
                int rcUp = runCompose(base, List.of("up", "-d"), "up", 600);
                if (rcUp != 0) {
                    fail("docker compose up 失败（exit=" + rcUp + "），详见上方日志。");
                    return;
                }

                // 6) 等待就绪
                int timeout = cfgInt("replay.observer.startupTimeoutSeconds", 600);
                if (!waitForObserversReady(timeout)) {
                    fail("观察者容器已启动，但 " + timeout + "s 内未全部就绪。"
                            + "请查看 docker logs replay-observer-1 排查。");
                    return;
                }

                state.set(FeatureState.RUNNING);
                plugin.getLogger().info("[Replay][Provisioner] 观察者集群部署完成，"
                        + observerIds().size() + " 个实例全部就绪。");
                notifyAdmins("§a[AntiCheat] 违规回放观察者集群部署完成，共 "
                        + observerIds().size() + " 个实例。");
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "[Replay][Provisioner] 部署异常: " + t.getMessage(), t);
                fail("部署异常: " + t.getMessage());
            }
        });
    }

    /** 停止并移除容器（保留镜像与构建目录） */
    public void teardown(CommandSender requester) {
        worker.submit(() -> {
            try {
                Path base = baseDir();
                if (!Files.isDirectory(base) || !Files.exists(base.resolve("docker-compose.yml"))) {
                    state.set(FeatureState.DISABLED);
                    reply(requester, "§7没有发现部署记录，已标记为禁用。");
                    return;
                }
                int rc = runCompose(base, List.of("down"), "down", 300);
                state.set(FeatureState.DISABLED);
                reply(requester, rc == 0
                        ? "§a[AntiCheat] 观察者容器已停止并移除。"
                        : "§c[AntiCheat] docker compose down 返回 " + rc + "，详见控制台。");
            } catch (Throwable t) {
                reply(requester, "§c停止失败: " + t.getMessage());
            }
        });
    }

    /** 方案 A 的「A. 安装 Docker」 */
    public void installDockerAsync(CommandSender requester) {
        worker.submit(() -> {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (!os.contains("linux")) {
                reply(requester, "§e[AntiCheat] 当前系统为 " + System.getProperty("os.name")
                        + "，请手动安装 Docker Desktop：");
                reply(requester, "§7https://docs.docker.com/engine/install/");
                reply(requester, "§7安装完成后执行 §f/ac replay setup");
                return;
            }
            if (!cfgBool("replay.observer.allowAutoInstallDocker", false)) {
                reply(requester, "§e[AntiCheat] 出于安全考虑，自动安装默认关闭。");
                reply(requester, "§7如确认允许，请在 config.yml 设置 "
                        + "§freplay.observer.allowAutoInstallDocker: true§7 后重试；");
                reply(requester, "§7或手动执行官方脚本：§fcurl -fsSL https://get.docker.com | sh");
                return;
            }
            reply(requester, "§e[AntiCheat] 开始安装 Docker（使用官方脚本 get.docker.com）...");
            int rc = run(List.of("sh", "-c", "curl -fsSL https://get.docker.com | sh"),
                    null, 900, "docker-install");
            if (rc == 0) {
                reply(requester, "§a[AntiCheat] Docker 安装完成，执行 §f/ac replay setup §a继续部署。");
            } else {
                reply(requester, "§c[AntiCheat] 自动安装失败（exit=" + rc + "），请手动安装："
                        + "https://docs.docker.com/engine/install/");
            }
        });
    }

    /** 方案 B 的「B. 禁用该功能」 */
    public void disableFeature(CommandSender requester) {
        plugin.getConfig().set("replay.observer.provisioner", "none");
        plugin.getConfig().set("replay.surveillance.enabled", false);
        plugin.saveConfig();
        state.set(FeatureState.DISABLED);
        try {
            if (plugin.getSurveillanceScheduler() != null) {
                plugin.getSurveillanceScheduler().onConfigReload();
            }
        } catch (Throwable ignored) {
        }
        reply(requester, "§e[AntiCheat] 违规回放功能已禁用（provisioner=none, surveillance.enabled=false）。");
        reply(requester, "§7其他检测功能不受影响。可用 §f/ac replay enable §7重新启用。");
    }

    /** 重新启用（不改 Docker 部分，只把开关打开） */
    public void enableFeature(CommandSender requester) {
        plugin.getConfig().set("replay.observer.provisioner", "docker");
        plugin.getConfig().set("replay.surveillance.enabled", true);
        plugin.saveConfig();
        state.set(FeatureState.NEEDS_DECISION);
        try {
            if (plugin.getSurveillanceScheduler() != null) {
                plugin.getSurveillanceScheduler().onConfigReload();
            }
        } catch (Throwable ignored) {
        }
        reply(requester, "§a[AntiCheat] 违规回放已重新启用，正在检测 Docker...");
        initAsync();
    }

    // ==================================================================
    // 部署子步骤
    // ==================================================================

    private void extractBundledFiles(Path base) throws IOException {
        for (String name : BUNDLED_FILES) {
            Path target = base.resolve(name);
            byte[] bytes;
            try (InputStream in = plugin.getResource("observer/" + name)) {
                if (in == null) {
                    plugin.getLogger().warning("[Replay][Provisioner] jar 内缺少 observer/" + name);
                    continue;
                }
                bytes = in.readAllBytes();
            }
            // 统一为 LF：Windows 上打包/释放可能带 CRLF，会让容器内 shell 脚本报
            // "bad interpreter: /bin/bash^M" 或 Dockerfile 解析失败。
            if (name.endsWith(".sh") || name.endsWith(".py") || name.equals("Dockerfile")
                    || name.endsWith(".conf")) {
                String text = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
                bytes = text.getBytes(StandardCharsets.UTF_8);
            }
            Files.write(target, bytes);
            if (name.endsWith(".sh") || name.endsWith(".py")) {
                target.toFile().setExecutable(true, false);
            }
        }
        plugin.getLogger().info("[Replay][Provisioner] 已释放 observer 构建文件到 " + base);
    }

    /** 准备 jdk8/ 目录（Temurin JRE 8），已存在则跳过 */
    private boolean prepareJre(Path base) {
        Path jdk8 = base.resolve("jdk8");
        Path javaBin = jdk8.resolve("bin").resolve("java");
        if (Files.isExecutable(javaBin)) {
            plugin.getLogger().info("[Replay][Provisioner] 复用已有 JRE 8: " + javaBin);
            return true;
        }
        try {
            Files.createDirectories(jdk8);
        } catch (IOException e) {
            plugin.getLogger().warning("[Replay][Provisioner] 无法创建 jdk8 目录: " + e.getMessage());
            return false;
        }

        List<String> urls = new ArrayList<>();
        String custom = cfg("replay.observer.jreUrl", "");
        if (!custom.isEmpty()) {
            urls.add(custom);
        }
        Collections.addAll(urls, DEFAULT_JRE_URLS);

        Path tarball = base.resolve("jdk8.tar.gz");
        boolean downloaded = false;
        for (String url : urls) {
            try {
                plugin.getLogger().info("[Replay][Provisioner] 下载 JRE 8: " + url);
                if (download(url, tarball, 900)) {
                    downloaded = true;
                    break;
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("[Replay][Provisioner] 下载失败（" + url + "）: " + t.getMessage());
            }
        }
        if (!downloaded) {
            plugin.getLogger().warning("[Replay][Provisioner] 所有 JRE 下载源均失败。"
                    + "可手动把 Temurin JRE 8 解压到 " + jdk8);
            return false;
        }

        // 解压（--strip-components=1 去掉顶层 jdk8uXXX-bXX-jre/）
        int rc = run(List.of("tar", "-xzf", tarball.toString(), "-C", jdk8.toString(),
                "--strip-components=1"), null, 300, "jre-extract");
        try {
            Files.deleteIfExists(tarball);
        } catch (IOException ignored) {
        }
        if (rc != 0) {
            plugin.getLogger().warning("[Replay][Provisioner] JRE 解压失败（exit=" + rc + "）");
            return false;
        }
        if (!Files.isExecutable(jdk8.resolve("bin").resolve("java"))) {
            plugin.getLogger().warning("[Replay][Provisioner] JRE 解压后未找到 jdk8/bin/java");
            return false;
        }
        plugin.getLogger().info("[Replay][Provisioner] JRE 8 就绪: " + jdk8.resolve("bin").resolve("java"));
        return true;
    }

    /**
     * 带进度与停滞检测的下载。
     * <p>不用「总超时」判断成败，而是监控文件大小：只要还在增长就继续等；
     * 连续 {@code STALL_TIMEOUT_MS} 无增长即判定该源不可用，取消并回退下一个源。
     * 这样既能容忍慢速但稳定的源，也不会在死链上干等。</p>
     */
    private boolean download(String url, Path target, int timeoutSec) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("User-Agent", "AdvancedAntiCheat-Provisioner")
                .GET().build();

        final java.util.concurrent.Future<HttpResponse<Path>> fut =
                client.sendAsync(req, HttpResponse.BodyHandlers.ofFile(target));

        final long stallTimeoutMs = 120_000L;
        final long start = System.currentTimeMillis();
        long lastSize = -1;
        long lastChange = start;
        long lastLog = 0;

        while (!fut.isDone()) {
            Thread.sleep(3000);
            long size = Files.exists(target) ? Files.size(target) : 0;
            long now = System.currentTimeMillis();
            if (size != lastSize) {
                lastSize = size;
                lastChange = now;
                if (now - lastLog > 15_000) {
                    lastLog = now;
                    plugin.getLogger().info(String.format(
                            "[Replay][Provisioner] 下载中 %.1f MB（已用 %.0fs）",
                            size / 1048576.0, (now - start) / 1000.0));
                }
            } else if (now - lastChange > stallTimeoutMs) {
                plugin.getLogger().warning("[Replay][Provisioner] 下载停滞超过 "
                        + (stallTimeoutMs / 1000) + "s，放弃该源并尝试下一个: " + url);
                fut.cancel(true);
                try {
                    Files.deleteIfExists(target);
                } catch (IOException ignored) {
                }
                return false;
            }
        }

        HttpResponse<Path> resp = fut.get();
        if (resp.statusCode() / 100 != 2) {
            plugin.getLogger().warning("[Replay][Provisioner] HTTP " + resp.statusCode() + " " + url);
            return false;
        }
        long size = Files.size(target);
        plugin.getLogger().info(String.format(
                "[Replay][Provisioner] 下载完成: %.1f MB（耗时 %.0fs）",
                size / 1048576.0, (System.currentTimeMillis() - start) / 1000.0));
        return size > 1024 * 1024;
    }

    /** 生成 docker-compose.yml（按当前环境定制路径/端口/内存） */
    private void generateCompose(Path base) throws IOException {
        String host = resolveServerHost();
        int port = resolveServerPort();
        int memMb = cfgInt("replay.observer.memoryMb", 0);
        String image = imageName();
        Path hlsRoot = hlsRoot();
        Files.createDirectories(hlsRoot);

        StringBuilder sb = new StringBuilder();
        sb.append("# 由 AdvancedAntiCheat 自动生成，请勿手工编辑（重新部署会覆盖）\n");
        sb.append("# 生成时间: ").append(new java.util.Date()).append("\n");
        sb.append("services:\n");
        for (int id : observerIds()) {
            Path obsHls = hlsRoot.resolve(String.valueOf(id));
            // 必须预先创建：否则 docker 会因 bind mount 源目录不存在而启动失败
            Files.createDirectories(obsHls);

            sb.append("  replay-observer-").append(id).append(":\n");
            sb.append("    build:\n      context: .\n      dockerfile: Dockerfile\n");
            sb.append("    image: ").append(image).append("\n");
            sb.append("    container_name: replay-observer-").append(id).append("\n");
            sb.append("    restart: unless-stopped\n");
            // 内存上限：Synology DSM 内核对 CFS/内存限额支持不佳（会直接启动失败），
            // 因此 memoryMb<=0 时不写 mem_limit，交由 Docker 默认行为处理。
            if (memMb > 0) {
                sb.append("    mem_limit: ").append(memMb).append("m\n");
            }
            sb.append("    extra_hosts:\n      - \"host.docker.internal:host-gateway\"\n");
            sb.append("    environment:\n");
            sb.append("      - OBSERVER_ID=").append(id).append("\n");
            sb.append("      - USERNAME=ReplayObserver_").append(id).append("\n");
            sb.append("      - UUID=ffffffff-ffff-ffff-ffff-fffffffffff").append(id).append("\n");
            sb.append("      - MC_SERVER_HOST=").append(host).append("\n");
            sb.append("      - MC_SERVER_PORT=").append(port).append("\n");
            sb.append("      - VNC_ENABLE=false\n");
            sb.append("      - RENDER_DISTANCE=").append(cfgInt("replay.observer.client.renderDistance", 8)).append("\n");
            // 按需进服开关：true = 客户端只在有人观看时才进服（默认）
            sb.append("      - MC_ONDEMAND=").append(
                    plugin.getConfig().getBoolean("replay.observer.mcOnDemand", true)).append("\n");
            sb.append("    volumes:\n");
            sb.append("      - \"").append(obsHls.toString().replace('\\', '/')).append(":/hls\"\n");
            sb.append("    ports:\n");
            sb.append("      - \"").append(observerPort(id)).append(":8080\"\n");
        }
        Files.write(base.resolve("docker-compose.yml"),
                sb.toString().getBytes(StandardCharsets.UTF_8));
        plugin.getLogger().info("[Replay][Provisioner] 已生成 docker-compose.yml（server="
                + host + ":" + port + "，hls=" + hlsRoot + "）");
    }

    private boolean waitForObserversReady(int timeoutSec) {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        List<Integer> ids = new ArrayList<>(observerIds());
        while (System.currentTimeMillis() < deadline) {
            ids.removeIf(this::probeObserverReady);
            if (ids.isEmpty()) {
                return true;
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean probeObserverReady(int id) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(4)).build();
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + observerPort(id) + "/status"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 && resp.body().contains("\"ready\"");
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================================================================
    // 服务器地址解析
    // ==================================================================

    private String resolveServerHost() {
        String explicit = cfg("replay.observer.mcServerHost", "");
        if (!explicit.isEmpty()) {
            return explicit;
        }
        String bound = Bukkit.getIp();
        if (bound != null && !bound.isEmpty() && !"0.0.0.0".equals(bound)) {
            return bound;
        }
        String lan = detectLanIp();
        return lan != null ? lan : "host.docker.internal";
    }

    private int resolveServerPort() {
        int cfgPort = cfgInt("replay.observer.mcServerPort", 0);
        if (cfgPort > 0) {
            return cfgPort;
        }
        int p = Bukkit.getPort();
        return p > 0 ? p : 25565;
    }

    private String detectLanIp() {
        try {
            String firstSiteLocal = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName().toLowerCase();
                // 跳过容器/虚拟网桥：它们的地址（如 172.20.0.1）也是 RFC1918，
                // 但容器访问不到，必须排除，否则观察者会连不上服务器。
                if (n.startsWith("docker") || n.startsWith("br-") || n.startsWith("veth")
                        || n.startsWith("virbr") || n.startsWith("tun") || n.startsWith("tap")) {
                    continue;
                }
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || !addr.isSiteLocalAddress()) continue;
                    String ip = addr.getHostAddress();
                    // 优先返回典型局域网地址（192.168.x.x / 10.x.x.x）
                    if (ip.startsWith("192.168.") || ip.startsWith("10.")) {
                        return ip;
                    }
                    if (firstSiteLocal == null) {
                        firstSiteLocal = ip;
                    }
                }
            }
            return firstSiteLocal;
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ==================================================================
    // 进程与提示
    // ==================================================================

    private int runCompose(Path dir, List<String> args, String label, int timeoutSec) {
        if (composeMode == null) {
            plugin.getLogger().warning("[Replay][Provisioner] compose 不可用");
            return -1;
        }
        List<String> cmd = new ArrayList<>();
        if ("docker compose".equals(composeMode)) {
            cmd.add("docker");
            cmd.add("compose");
        } else {
            cmd.add("docker-compose");
        }
        cmd.addAll(args);
        return run(cmd, dir, timeoutSec, label);
    }

    /** 执行外部命令并把输出转发到插件日志；返回 exit code（-1 命令不存在，-2 超时） */
    private int run(List<String> cmd, Path workDir, int timeoutSec, String label) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (workDir != null) {
                pb.directory(workDir.toFile());
            }
            pb.redirectErrorStream(true);
            p = pb.start();

            final Process proc = p;
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    int n = 0;
                    while ((line = r.readLine()) != null) {
                        n++;
                        if (n <= 40 || n % 100 == 0) {
                            plugin.getLogger().info("[" + label + "] " + line);
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "aac-provisioner-" + label);
            reader.setDaemon(true);
            reader.start();

            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                plugin.getLogger().warning("[Replay][Provisioner] " + label + " 超时(" + timeoutSec + "s)，已终止");
                return -2;
            }
            return p.exitValue();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) p.destroyForcibly();
            return -2;
        }
    }

    private void fail(String reason) {
        state.set(FeatureState.FAILED);
        lastError = reason.replace('\n', ' ');
        plugin.getLogger().warning("[Replay][Provisioner] 部署失败: " + reason);
        notifyAdmins("§c[AntiCheat] 违规回放部署失败：\n§7" + reason
                + "\n§7可用 §f/ac replay status §7查看详情，或 §f/ac replay disable §7关闭该功能。");
    }

    /** 控制台「二选一」提示框 */
    private void logDecisionPrompt(DockerState ds) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n============================================================\n");
        sb.append(" [AdvancedAntiCheat] 违规回放需要 Docker，但当前不可用\n");
        sb.append("------------------------------------------------------------\n");
        sb.append(" 检测结果: ").append(ds.desc).append("\n");
        if (Bukkit.getOnlineMode()) {
            sb.append(" 注意    : 服务端为 online-mode=true（正版验证），\n");
            sb.append("           本方案使用离线观察者账号，无法登录，需先改为离线模式。\n");
        }
        sb.append("\n 请选择其中一项：\n");
        sb.append("\n   A. 安装 Docker 并启用违规回放\n");
        sb.append("      Linux 自动安装:  /ac replay docker install\n");
        sb.append("      手动安装指南  :  https://docs.docker.com/engine/install/\n");
        sb.append("      安装完成后执行:  /ac replay setup\n");
        sb.append("\n   B. 禁用违规回放功能\n");
        sb.append("      /ac replay disable\n");
        sb.append("\n 在你做出选择之前，违规回放处于停用状态，\n");
        sb.append(" 其它反作弊检测功能不受任何影响。\n");
        sb.append("============================================================");
        plugin.getLogger().warning(sb.toString());
    }

    private String buildDecisionPromptText(DockerState ds) {
        return "§e[AntiCheat] 违规回放需要 Docker，当前不可用（" + ds.desc + "）。\n"
                + "§7请二选一：\n"
                + "§a  A. §7安装 Docker → §f/ac replay docker install§7（或手动安装后 §f/ac replay setup§7）\n"
                + "§a  B. §7禁用该功能 → §f/ac replay disable";
    }

    /** 提示所有在线管理员（主线程执行） */
    private void notifyAdmins(String message) {
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("anticheat.admin")) {
                        for (String line : message.split("\n")) {
                            p.sendMessage(line);
                        }
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void reply(CommandSender to, String msg) {
        if (to == null) {
            plugin.getLogger().info(msg.replaceAll("§[0-9a-fk-or]", ""));
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (String line : msg.split("\n")) {
                    to.sendMessage(line);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public void shutdown() {
        worker.shutdownNow();
    }
}
