package com.example.core.api

/**
 * Demonstrates an interface whose provider is declared through standard SPI
 * (`META-INF/services`). The interface itself stays in base; the implementation lives in
 * core-common.
 */
interface LegacyService {
    /** Returns the description produced by the META-INF-declared provider. */
    fun describe(): String
}
