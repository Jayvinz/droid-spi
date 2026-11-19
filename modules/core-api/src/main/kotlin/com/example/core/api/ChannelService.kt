package com.example.core.api

/**
 * Reports which product flavor supplied this build's implementation.
 *
 * The demo UI self-check uses it to prove that a `free` build sees the `free` provider and a
 * `paid` build sees the `paid` provider — the two implementations live in disjoint flavor
 * source sets and must never end up in the same table.
 */
interface ChannelService {
    /** Flavor name this implementation was compiled for (`free` / `paid`). */
    fun channelName(): String

    /** Human-readable description, including the source directory that declared it. */
    fun describe(): String
}
