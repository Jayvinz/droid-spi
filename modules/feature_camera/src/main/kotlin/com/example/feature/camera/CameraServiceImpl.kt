package com.example.feature.camera

import com.example.core.api.CameraService
import top.jayvin.android.spi.ServiceProvider
import com.example.feature.camera.ui.CameraActivity

/**
 * Service implementation inside a dynamic module. Base cannot see this class at compile time,
 * but the registry contains a `ldc` reference from `(CameraService, "")` to this class.
 * While the module is not installed the entry is unavailable; after installation the next
 * `load()` succeeds, with no refresh.
 */
@ServiceProvider(service = CameraService::class)
class CameraServiceImpl : CameraService {

    override fun description(): String = "Camera · from the on-demand dynamic module"

    override fun open(context: android.content.Context) {
        context.startActivity(
            android.content.Intent(context, CameraActivity::class.java)
        )
    }
}
