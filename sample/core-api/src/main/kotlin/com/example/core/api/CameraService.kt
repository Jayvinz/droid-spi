package com.example.core.api

import android.content.Context

/**
 * 相机能力。实现位于 **on-demand 动态模块** `:feature-camera`，
 * 未安装时 `ServiceLoader.load(CameraService::class.java)` 返回 null，注册表里该条目为 MISSING。
 *
 * 页面跳转也由实现自己完成，base 侧不需要知道 `CameraActivity` 的类名。
 */
interface CameraService {
    fun description(): String
    fun open(context: Context)
}
