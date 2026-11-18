package com.example.core.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A plain resident library; only here to give the sample a multi-level layout (app → feature → core-common). */
object Trace {

    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Formats [message] with a timestamp and [tag]. */
    fun line(tag: String, message: String): String =
        "${format.format(Date())} [$tag] $message"
}
