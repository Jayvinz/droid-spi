package com.example.demo

import com.example.core.api.DebugService
import top.jayvin.android.spi.ServiceProvider

/**
 * Declared only under `src/debug`, so only the `debug` build type variants register it.
 * Release tables must not contain this entry.
 */
@ServiceProvider(service = DebugService::class)
class DebugServiceImpl : DebugService {
    override fun marker(): String = "debug build type provider from app/src/debug"
}
