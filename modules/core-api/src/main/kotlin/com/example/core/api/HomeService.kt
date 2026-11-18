package com.example.core.api

/**
 * Home page content. Implemented by `:modules:feature-home`.
 */
interface HomeService {
    fun title(): String
    fun recommendations(): List<String>
}
