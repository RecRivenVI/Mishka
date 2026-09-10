package top.yukonga.mishka.domain.model

import kotlinx.serialization.Serializable

/**
 * 订阅类型枚举。Room 中通过 ProfileTypeConverter 转为 TEXT 列存储。
 */
@Serializable
enum class ProfileType {
    File, Url, External, Ninja;

    val isRemote: Boolean get() = this == Url || this == Ninja

    fun effectiveUserAgent(value: String): String =
        if (this == Ninja) NINJA_USER_AGENT else value.trim()

    companion object {
        const val NINJA_USER_AGENT = "clash-ninja/v2.4.0"

        fun fromStringOrDefault(value: String?, default: ProfileType = Url): ProfileType =
            runCatching { value?.let { valueOf(it) } }.getOrNull() ?: default
    }
}
