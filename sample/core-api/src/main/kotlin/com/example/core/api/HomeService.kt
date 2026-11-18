package com.example.core.api

/**
 * 首页内容。由 `:feature-home` 提供实现。
 */
interface HomeService {
    fun title(): String
    fun recommendations(): List<String>
}
