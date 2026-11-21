package com.example.droidreel.base.api

/**
 * Demo diagnostics provider. The `"debug"` key exists only in debug build tables, which proves
 * build-type isolation: release tables must contain no entry for this service at all.
 */
interface DemoInfoService {
    fun describe(): String
}
