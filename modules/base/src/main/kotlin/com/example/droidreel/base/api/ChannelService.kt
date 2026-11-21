package com.example.droidreel.base.api

/**
 * Reports which product flavor supplied this build's implementation.
 *
 * `free` and `vip` implementations live in disjoint flavor source sets, so the two must never
 * end up in the same table. The example's SPI status dialog shows which one was scanned.
 */
interface ChannelService {
    /** Flavor name this implementation was compiled for (`free` / `vip`). */
    fun channelName(): String

    /** Human-readable description, including the source directory that declared it. */
    fun describe(): String
}
