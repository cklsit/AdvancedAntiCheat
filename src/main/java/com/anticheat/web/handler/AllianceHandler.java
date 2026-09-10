package com.anticheat.web.handler;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.detection.ViolationRecord;
import com.anticheat.detection.association.AssociationDetector;
import com.anticheat.detection.association.SocialGraph;
import com.anticheat.managers.AuditManager;
import com.anticheat.managers.ViolationManager;
import com.anticheat.utils.VersionUtil;
import com.anticheat.web.BukkitBridge;
import com.anticheat.web.auth.Permission;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 联盟图谱 REST 端点：
 * <ul>
 *   <li>GET /api/alliance/graph - 基于真实 SocialGraph + 同IP + 疑似小号 构建关联图谱</li>
 * </ul>
 * <p>数据来源：AdvancedDetectionManager.getSocialGraph() / getAssociationDetector()，
 * 仅返回当前在线玩家之间的真实关联；无关联时返回空图谱（非假数据）。</p>
 */
public class AllianceHandler extends AbstractHandler {

    public AllianceHandler(AdvancedAntiCheat plugin, AuditManager auditManager) {
        super(plugin, auditManager);
    }

    public void register(Javalin app) {
        app.get("/api/alliance/graph", this::graph);
    }

    private void graph(Context ctx) {
        if (!AuthHandler.require(ctx, Permission.DASHBOARD_READ)) return;

        // 主线程快照在线玩家（1.8 兼容：通过反射安全读取）
        List<Player> online = BukkitBridge.syncSupply(plugin, VersionUtil::safeGetOnlinePlayers);

        SocialGraph socialGraph = null;
        AssociationDetector associationDetector = null;
        try {
            socialGraph = plugin.getAdvancedDetectionManager().getSocialGraph();
            associationDetector = plugin.getAdvancedDetectionManager().getAssociationDetector();
        } catch (Throwable ignored) {
            // 降级：仅展示玩家节点，无边
        }

        ViolationManager vm = null;
        try {
            vm = plugin.getDetectionManager().getViolationManager();
        } catch (Throwable ignored) {
        }

        // 节点：在线玩家（圆形布局）
        List<Map<String, Object>> nodes = new ArrayList<>();
        Map<UUID, Integer> uuidToIdx = new HashMap<>();
        int n = online.size();
        for (int i = 0; i < n; i++) {
            Player p = online.get(i);
            UUID u = p.getUniqueId();
            int score = computeScore(vm, u);
            uuidToIdx.put(u, i);
            double angle = n > 0 ? 2 * Math.PI * i / n : 0;
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", u.toString());
            node.put("label", p.getName() == null ? u.toString().substring(0, 8) : p.getName());
            node.put("type", "player");
            node.put("score", score);
            node.put("x", 50 + 40 * Math.cos(angle));
            node.put("y", 50 + 40 * Math.sin(angle));
            node.put("ip", p.getAddress() == null || p.getAddress().getAddress() == null
                    ? "" : p.getAddress().getAddress().getHostAddress());
            node.put("world", p.getWorld() == null ? "" : p.getWorld().getName());
            nodes.add(node);
        }

        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> edgeKeys = new HashSet<>();

        // 边1：行为交互（来自 SocialGraph，仅在线玩家间）
        if (socialGraph != null) {
            for (Player p : online) {
                UUID u = p.getUniqueId();
                Set<UUID> neighbors = null;
                try {
                    neighbors = socialGraph.getNeighbors(u);
                } catch (Throwable ignored) {
                }
                if (neighbors == null) continue;
                for (UUID other : neighbors) {
                    if (!uuidToIdx.containsKey(other)) continue;
                    addEdge(edges, edgeKeys, u, other, "behavior", weightOf(socialGraph, u, other));
                }
            }
        }

        // 边2：同 IP 关联
        Map<String, List<UUID>> ipMap = new HashMap<>();
        for (Player p : online) {
            String ip = p.getAddress() == null || p.getAddress().getAddress() == null
                    ? "" : p.getAddress().getAddress().getHostAddress();
            if (ip.isEmpty()) continue;
            ipMap.computeIfAbsent(ip, k -> new ArrayList<>()).add(p.getUniqueId());
        }
        for (List<UUID> uuids : ipMap.values()) {
            if (uuids.size() < 2) continue;
            for (int i = 0; i < uuids.size(); i++) {
                for (int j = i + 1; j < uuids.size(); j++) {
                    addEdge(edges, edgeKeys, uuids.get(i), uuids.get(j), "ip", 0.9);
                }
            }
        }

        // 边3：疑似小号（来自 AssociationDetector）
        if (associationDetector != null) {
            for (Player p : online) {
                UUID u = p.getUniqueId();
                Set<UUID> alts = null;
                try {
                    alts = associationDetector.getSuspectedAltAccounts(u);
                } catch (Throwable ignored) {
                }
                if (alts == null) continue;
                for (UUID other : alts) {
                    if (!uuidToIdx.containsKey(other)) continue;
                    addEdge(edges, edgeKeys, u, other, "hardware", 0.85);
                }
            }
        }

        // 团伙簇：来自 SocialGraph.detectCheatingGroups()
        List<Map<String, Object>> groups = new ArrayList<>();
        if (socialGraph != null) {
            try {
                List<SocialGraph.CheatingGroup> cheatingGroups = socialGraph.detectCheatingGroups();
                if (cheatingGroups != null) {
                    for (SocialGraph.CheatingGroup g : cheatingGroups) {
                        Map<String, Object> grp = new LinkedHashMap<>();
                        grp.put("members", g.members.stream().map(UUID::toString).collect(Collectors.toList()));
                        grp.put("suspicionScore", g.suspicionScore);
                        grp.put("density", g.density);
                        groups.add(grp);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("nodeCount", nodes.size());
        stats.put("edgeCount", edges.size());
        stats.put("groupCount", groups.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("edges", edges);
        result.put("groups", groups);
        result.put("stats", stats);

        ok(ctx, result);
    }

    private void addEdge(List<Map<String, Object>> edges, Set<String> keys,
                         UUID a, UUID b, String kind, double weight) {
        String key = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
        if (keys.contains(key)) return;
        keys.add(key);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("source", a.toString());
        e.put("target", b.toString());
        e.put("kind", kind);
        e.put("weight", Math.min(1.0, Math.max(0.0, weight)));
        edges.add(e);
    }

    private double weightOf(SocialGraph g, UUID a, UUID b) {
        try {
            Double w = g.getEdgeWeight(a, b);
            return w == null ? 0.0 : w;
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    private int computeScore(ViolationManager vm, UUID uuid) {
        if (vm == null) return 0;
        try {
            List<ViolationRecord> hist = vm.getViolationHistory(uuid);
            if (hist == null) return 0;
            int score = 0;
            for (ViolationRecord r : hist) {
                score += (int) Math.round(r.getViolationLevel() * 10);
            }
            return Math.min(100, score);
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
