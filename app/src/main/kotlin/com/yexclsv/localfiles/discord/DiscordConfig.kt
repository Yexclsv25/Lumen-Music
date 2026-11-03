package com.yexclsv.localfiles.discord

import android.content.Context
import android.content.pm.PackageManager

object DiscordConfig {
    /** Application ID from gradle.properties (discordAppId), or 0 if not set. */
    fun appId(ctx: Context): Long = try {
        val meta = ctx.packageManager.getApplicationInfo(ctx.packageName, PackageManager.GET_META_DATA).metaData
        meta?.getString("discord_app_id")?.removePrefix("id-")?.toLongOrNull() ?: 0L
    } catch (_: Exception) {
        0L
    }
}
