package com.example.core.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一个普通的常驻 library，只为让示例具备多模块层级（app → feature → core-common） */
object Trace {

    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun line(tag: String, message: String): String =
        "${format.format(Date())} [$tag] $message"
}
