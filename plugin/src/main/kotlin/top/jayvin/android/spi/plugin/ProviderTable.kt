package top.jayvin.android.spi.plugin

import org.gradle.api.GradleException

/**
 * Validates and merges the raw entries read from every module manifest.
 *
 * Rules:
 * - the empty (default) key may own several distinct providers; identical provider lines are
 *   deduplicated because the same implementation may legitimately be declared twice (annotation
 *   plus a copied `META-INF/services` entry);
 * - any explicit key is unique per service: two providers competing for the same non-empty key
 *   fail the build.
 */
internal object ProviderTable {

    /**
     * Stable, layout-independent order. The provider FQN is the final tiebreaker, so several
     * default implementations of one service are ordered by name regardless of which module
     * produced them. Runtime `load()` returns the first *available* entry in this order.
     */
    fun sort(entries: List<ProviderEntry>): List<ProviderEntry> =
        entries.sortedWith(compareBy({ it.service }, { it.key }, { it.provider }))

    fun merge(raw: List<ProviderEntry>): List<ProviderEntry> {
        val out = ArrayList<ProviderEntry>(raw.size)
        val explicitSlots = HashMap<Pair<String, String>, String>()
        val defaultProviders = HashMap<String, MutableSet<String>>()

        for (entry in raw) {
            if (entry.key.isEmpty()) {
                if (defaultProviders.getOrPut(entry.service) { HashSet() }.add(entry.provider)) {
                    out += entry
                }
            } else {
                val previous = explicitSlots.put(entry.service to entry.key, entry.provider)
                if (previous != null) {
                    throw GradleException(
                        "Duplicate registration: service ${entry.service} key=\"${entry.key}\" is " +
                            "implemented by both $previous and ${entry.provider} " +
                            "(only the default key may have multiple providers)"
                    )
                }
                out += entry
            }
        }
        return out
    }
}
