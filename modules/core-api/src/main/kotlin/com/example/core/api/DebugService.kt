package com.example.core.api

/**
 * Registered only from the application's `debug` source set.
 *
 * Release builds must contain neither the entry nor the provider; the demo UI self-check
 * verifies that the build-type-specific declaration stays out of release tables.
 */
interface DebugService {
    /** Marker text identifying the debug-only implementation. */
    fun marker(): String
}
