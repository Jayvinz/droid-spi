package com.example.core.common

import com.example.core.api.LegacyService

/**
 * 这个 provider 没有用 @ServiceProvider 注解，
 * 而是由 core-common 的 META-INF/services 资源配置声明 ——
 * 模拟第三方 jar / aar 按 JDK SPI 约定集成的场景。
 */
class LegacyServiceImpl : LegacyService {
    override fun describe(): String = "来自 META-INF/services 的 provider"
}
