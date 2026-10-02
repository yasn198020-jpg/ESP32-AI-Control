package com.yasn198020.aicontrol

/**
 * Direct JNI bridge to the app's own llama.cpp build.
 *
 * The native library is intentionally loaded lazily by this object so the
 * application can still fall back to the old AAR if the native backend
 * cannot be loaded on a particular device.
 */
object MarfaLlamaNative {
    private val available: Boolean

    init {
        available = try {
            System.loadLibrary("marfa_llama")
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun isAvailable(): Boolean = available

    external fun nativeInitBackends(nativeLibDir: String): Boolean

    external fun nativeLoadModel(
        modelPath: String,
        threads: Int,
        contextSize: Int
    ): Long

    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        systemPrompt: String,
        maxTokens: Int
    ): String

    external fun nativeBenchmarkPrompt(
        handle: Long,
        prompt: String,
        systemPrompt: String
    ): String

    external fun nativeRelease(handle: Long)

    external fun nativeVersion(handle: Long): String
}
