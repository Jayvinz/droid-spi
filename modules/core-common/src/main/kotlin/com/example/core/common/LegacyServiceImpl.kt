package com.example.core.common

import com.example.core.api.LegacyService

/**
 * This provider has no @ServiceProvider annotation. It is declared by core-common's
 * META-INF/services resource, simulating a third-party jar / aar integrated through the
 * JDK SPI convention.
 */
class LegacyServiceImpl : LegacyService {
    override fun describe(): String = "A provider from META-INF/services"
}
