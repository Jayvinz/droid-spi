package com.example.core.api

/**
 * 演示"按标准 SPI 约定、通过 META-INF/services 声明 provider"的接口。
 * 接口本身仍在 base，provider 实现类位于 core-common。
 */
interface LegacyService {
    fun describe(): String
}
