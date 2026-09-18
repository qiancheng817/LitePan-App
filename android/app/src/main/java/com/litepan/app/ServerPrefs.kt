package com.litepan.app

import android.content.Context
import android.net.Uri

/**
 * 服务器地址持久化与输入规范化。
 */
object ServerPrefs {
    private const val PREF_NAME = "litepan_app"
    private const val KEY_SERVER_URL = "server_url"

    fun getServerUrl(context: Context): String =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_URL, "") ?: ""

    fun setServerUrl(context: Context, url: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVER_URL, url).apply()
    }

    /**
     * 规范化用户输入：补全 http(s) 前缀、去除末尾斜杠并校验。
     * @return 合法地址；输入为空或非法时返回 null
     */
    fun normalize(input: String?): String? {
        var value = input?.trim().orEmpty().trimEnd('/')
        if (value.isEmpty()) return null
        if (!value.contains("://")) value = "http://$value"
        val uri = Uri.parse(value)
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (host.isBlank()) return null
        return value
    }
}
