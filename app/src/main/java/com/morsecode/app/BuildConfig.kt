package com.morsecode.app

/**
 * Mirrors the Gradle generated BuildConfig so the offline (Gradle-free) build and the
 * Gradle build expose the same constants. Values are kept in sync with
 * `tools/offline_build.py` and `app/build.gradle.kts`.
 */
object BuildConfig {
    const val APPLICATION_ID = "com.morsecode.app"
    const val VERSION_NAME = "1.0.0"
    const val VERSION_CODE = 1
    const val DEBUG = false
    const val BUILD_TYPE = "release"
    const val PREHASH_LIMIT = 256L * 1024 * 1024
    const val UDP_DISCOVERY_PORT = 33457
    const val TCP_PORT = 33456
    const val WEB_PORT = 33455
    const val APP_UUID = "7d2f0c11-6a7c-4f2b-9d0e-1f4b8a0c9e33"
}
