package com.example.snatch

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeExtensionStore {
    private const val PREFS = "snitch_time_extensions"
    private const val KEY_DATE = "_last_grant_date"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun todayString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(Date())
    }

    private fun checkAndResetIfNewDay(context: Context) {
        val p = prefs(context)
        val storedDate = p.getString(KEY_DATE, null)
        val today = todayString()
        if (storedDate != today) {
            p.edit().clear().putString(KEY_DATE, today).apply()
        }
    }

    fun getExtraMinutes(context: Context, packageName: String): Int {
        checkAndResetIfNewDay(context)
        val value = prefs(context).getInt(packageName, 0)
        return value.coerceAtLeast(0)
    }

    fun addExtraMinutes(context: Context, packageName: String, minutes: Int) {
        if (minutes <= 0) return
        checkAndResetIfNewDay(context)
        val current = getExtraMinutes(context, packageName)
        prefs(context).edit()
            .putInt(packageName, current + minutes)
            .apply()
    }

    fun setExtraMinutes(context: Context, packageName: String, minutes: Int) {
        checkAndResetIfNewDay(context)
        prefs(context).edit()
            .putInt(packageName, minutes.coerceAtLeast(0))
            .apply()
    }

    fun allExtraMinutes(context: Context): Map<String, Int> {
        checkAndResetIfNewDay(context)
        return prefs(context).all.mapNotNull { (key, value) ->
            if (key == KEY_DATE) return@mapNotNull null
            val minutes = value as? Int ?: return@mapNotNull null
            if (minutes > 0) key to minutes else null
        }.toMap()
    }

    fun getEffectiveLimitMinutes(context: Context, packageName: String): Int? {
        val base = LimitStore.getLimitMinutes(context, packageName) ?: return null
        val extra = getExtraMinutes(context, packageName)
        return base + extra
    }
}
