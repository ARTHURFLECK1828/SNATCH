
package com.example.snatch

import android.content.Context

object LimitStore {
    private const val PREFS = "snitch_limits"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getLimitMinutes(context: Context, packageName: String): Int? {
        val value = prefs(context).getInt(packageName, -1)
        return value.takeIf { it >= 0 }
    }

    fun setLimitMinutes(
        context: Context,
        packageName: String,
        minutes: Int
    ) {
        require(minutes in 1..1440) {
            "Limit must be between 1 and 1440 minutes"
        }

        prefs(context).edit()
            .putInt(packageName, minutes)
            .apply()
    }

    fun removeLimit(context: Context, packageName: String) {
        prefs(context).edit().remove(packageName).apply()
    }

    fun allLimits(context: Context): Map<String, Int> =
        prefs(context).all.mapNotNull { (key, value) ->
            val minutes = value as? Int ?: return@mapNotNull null
            if (minutes > 0) key to minutes else null
        }.toMap()
}
