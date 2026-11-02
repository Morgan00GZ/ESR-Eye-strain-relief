package com.eyestrainrelief.core

/**
 * 注视时长数据源。真机阶段接入，P0 阶段为空实现。
 *
 * 设计上只要求能提供「当前是否有人在注视屏幕」的瞬时信号，
 * 累计时长由 [SessionTimer] 自行积分得到，避免依赖厂商是否提供累计值。
 */
interface GazeSource {
    val name: String

    /** 本次启动时该数据源是否可用 */
    val isAvailable: Boolean

    /** 开始接收信号；回调应只在状态发生变化时触发，禁止周期上报 */
    fun start(onChange: (active: Boolean) -> Unit) {}

    fun stop() {}
}

/** 空实现：等同于「系统不提供注视数据」，全部走亮屏计时 */
object NoGazeSource : GazeSource {
    override val name: String = "none"
    override val isAvailable: Boolean = false
}
