package com.anticheat.replay;

/**
 * 观察者摄像机机位。
 *
 * <p>两种机位在"能否拍到手持物品"上互斥，这是原生 Minecraft 的限制而非实现缺陷：</p>
 * <ul>
 *   <li>{@link #ATTACH} —— 用 {@code Player#setSpectatorTarget(Entity)} 把观察者绑定到目标眼部，
 *       视角与目标 1:1 一致、服务端零开销、无传送抖动。但被观战者的模型与手持物品
 *       <b>不会</b>被渲染（观察者位于其头部内部，且旁观者自身无手部渲染）。</li>
 *   <li>{@link #SHOULDER} —— 观察者停在目标后上方，画面可同时包含世界、目标持械的手
 *       与其正在攻击的目标，但视角不是目标的第一人称。</li>
 * </ul>
 *
 * <p>方案 A（本项目采用）：平时 {@link #ATTACH}，记录到违规时自动切 {@link #SHOULDER}
 * 保持 {@code autoShoulderHoldSeconds} 秒，从而兼顾"视角一致"与"取证要素完整"。</p>
 */
public enum CameraMode {

    /** 眼位绑定（setSpectatorTarget）：视角 1:1，画面无手持物品。 */
    ATTACH(false),

    /** 过肩机位（目标后上方）：画面有手持物品，视角非第一人称。 */
    SHOULDER(true);

    private final boolean showsHeldItem;

    CameraMode(boolean showsHeldItem) {
        this.showsHeldItem = showsHeldItem;
    }

    /** 该机位下画面是否包含手持物品。 */
    public boolean showsHeldItem() {
        return showsHeldItem;
    }

    /** 宽松解析（忽略大小写、允许 attach/shoulder/eye/behind 等别名），失败返回 def。 */
    public static CameraMode parse(String value, CameraMode def) {
        if (value == null) return def;
        String v = value.trim().toLowerCase();
        if (v.isEmpty()) return def;
        if (v.startsWith("attach") || v.equals("eye") || v.equals("first_person")) return ATTACH;
        if (v.startsWith("shoulder") || v.equals("behind") || v.equals("third_person")) return SHOULDER;
        return def;
    }
}
