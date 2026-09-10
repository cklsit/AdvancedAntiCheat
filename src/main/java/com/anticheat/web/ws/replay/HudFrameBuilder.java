package com.anticheat.web.ws.replay;

import com.anticheat.managers.replay.TracePoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HUD 帧构造器（v3 协议）。
 *
 * <p>统一把 {@link TracePoint} 序列化为前端 {@code HudFrame} 期望的<b>长键</b>结构：
 * {@code health / maxHealth / hunger / armor / level / xp / air / hotbarSlot / hotbar}，
 * 并附带准星目标（{@code tk/tt/tn/td/tb}）、完整背包（{@code finv}）与坐标
 * （{@code x/y/z/yaw/pitch}）。</p>
 *
 * <p>之所以用长键而非存档里的短键（h/f/a/lvl…），是因为前端合成层
 * {@code HudOverlay.vue} 与 {@code ReplayWatchView.vue} 已按长键消费，
 * 归档路径也已把短键映射成长键——live 直接吐长键可零成本复用现有渲染。</p>
 */
public final class HudFrameBuilder {

    private HudFrameBuilder() {}

    /**
     * 构建 HUD 帧 Map。
     *
     * @param tp          采样点
     * @param timeOffsetMs 相对 startTime 的毫秒偏移（可为 null，如 init 快照不含 t）
     */
    public static Map<String, Object> build(TracePoint tp, Long timeOffsetMs) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("w", tp.getWallClockMs());
        if (timeOffsetMs != null) f.put("t", timeOffsetMs);

        // 生命/饥饿/护甲：半值(0-40) → 整值(0-20)，前端按 10 格渲染
        f.put("health", tp.getHealthHalf() / 2.0);
        f.put("maxHealth", 20);
        f.put("hunger", (int) tp.getHunger());
        f.put("armor", tp.getArmorHalf() / 2.0);
        f.put("level", (int) tp.getExpLevel());
        f.put("xp", tp.getExpPercent() / 100.0);
        f.put("air", null); // 未采样氧气，前端显示"未在水中"
        f.put("hotbarSlot", (int) tp.getHotbarSlot());

        // 热栏 9 格：String[] 类型名 → {id, count}
        String[] inv = tp.getInventoryHotbar();
        if (inv != null) {
            List<Object> hotbar = new ArrayList<>(9);
            for (String id : inv) {
                if (id != null) {
                    Map<String, Object> slot = new LinkedHashMap<>();
                    slot.put("id", "minecraft:" + id.toLowerCase(java.util.Locale.ROOT));
                    slot.put("count", 1);
                    hotbar.add(slot);
                } else {
                    hotbar.add(null);
                }
            }
            f.put("hotbar", hotbar);
        }

        // 完整 36 格背包（类型名数组，前端可渲染完整背包网格）
        if (tp.getInventoryFull() != null) {
            f.put("finv", tp.getInventoryFull());
        }

        // 准星目标：0=无，1=实体，2=方块
        f.put("tk", (int) tp.getTargetKind());
        if (tp.getTargetKind() == 1) {
            if (tp.getTargetEntityType() != null) f.put("tt", tp.getTargetEntityType());
            if (tp.getTargetEntityName() != null) f.put("tn", tp.getTargetEntityName());
        } else if (tp.getTargetKind() == 2) {
            if (tp.getTargetBlockType() != null) f.put("tb", tp.getTargetBlockType());
        }
        f.put("td", tp.getTargetDistance());

        // 坐标与朝向（实时坐标读数）
        f.put("x", tp.getX());
        f.put("y", tp.getY());
        f.put("z", tp.getZ());
        f.put("yaw", tp.getYaw());
        f.put("pitch", tp.getPitch());
        return f;
    }
}
