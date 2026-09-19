package com.anticheat.core.check.type

import com.anticheat.core.util.update.PredictionComplete

/**
 * 预测完成监听：物理引擎算完偏差后回调。
 *
 * <p>这是 Grim 式检测的主力接口——真正的移动检测都挂在这里，
 * 而不是直接对比相邻两个包的位移。</p>
 */
interface PostPredictionListener {

    fun onPredictionComplete(complete: PredictionComplete)
}
