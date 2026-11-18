package com.example.core.api

/**
 * Home page content. Implemented by `:modules:feature-home`.
 */
interface HomeService {
    /** Returns the home page title. */
    fun title(): String

    /** Returns the recommendation list shown on the home page. */
    fun recommendations(): List<String>
}
