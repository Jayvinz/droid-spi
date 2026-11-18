package com.example.feature.camera

import com.example.core.api.CameraService
import top.fishquant.mobile.spi.ServiceProvider
import com.example.feature.camera.ui.CameraActivity

/**
 * 动态模块里的服务实现。base 在编译期看不到这个类，
 * 但注册表里有一条 `(CameraService, "")` → 本类的 `ldc` 引用；
 * 模块未安装时该条目是 MISSING，装好后经过一次 refresh 变成 READY。
 */
@ServiceProvider(service = CameraService::class)
class CameraServiceImpl : CameraService {

    override fun description(): String = "相机 · 来自 on-demand 动态模块"

    override fun open(context: android.content.Context) {
        context.startActivity(
            android.content.Intent(context, CameraActivity::class.java)
        )
    }
}
