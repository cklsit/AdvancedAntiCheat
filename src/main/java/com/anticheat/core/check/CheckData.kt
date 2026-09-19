package com.anticheat.core.check

/**
 * 检查元数据。对齐 Grim 的 `@CheckData`：把「名字 / 配置键 / 衰减速率 / setback 阈值」
 * 全部挂在类上，新增检测不需要改任何注册代码。
 *
 * <p>注意：所有取值都通过 Java 反射在运行时读取，因此必须是 `RUNTIME` 保留策略。
 * 各项都给了默认值，但**新增检测请显式写全 decay/setback**——
 * 依赖默认值会让「阈值到底是多少」在代码里变得不可见。</p>
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class CheckData(
    /** 显示名与稳定标识，例如 `BadPacketsA`。 */
    val name: String,

    /** 配置键。`DEFAULT` 表示与 [name] 相同。 */
    val configName: String = "DEFAULT",

    /** 每次安全动作（reward）扣减的违规分。越大衰减越快。 */
    val decay: Double = 0.02,

    /** 触发 setback（拉回）的违规分阈值；<= 0 表示本检测不参与 setback。 */
    val setback: Double = 0.0,

    val description: String = "",

    /** 实验性检测：默认关闭，需显式开启才参与判定。 */
    val experimental: Boolean = false
)
