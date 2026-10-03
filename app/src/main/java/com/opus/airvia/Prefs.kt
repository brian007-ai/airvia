package com.opus.airvia

import android.content.Context

/** Small SharedPreferences store: per-speaker volume + last speaker. */
object Prefs {
    private const val FILE = "airvia"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun volumeFor(ctx: Context, sp: Speaker): Int? {
        val key = "vol:${sp.key}"
        return if (prefs(ctx).contains(key)) prefs(ctx).getInt(key, 100) else null
    }

    fun saveVolume(ctx: Context, sp: Speaker, pct: Int) {
        prefs(ctx).edit().putInt("vol:${sp.key}", pct).apply()
    }

    fun saveLastSpeaker(ctx: Context, sp: Speaker) {
        prefs(ctx).edit()
            .putString("last_name", sp.name)
            .putString("last_host", sp.host)
            .putInt("last_port", sp.port)
            .apply()
    }

    fun lastSpeaker(ctx: Context): Speaker? {
        val p = prefs(ctx)
        val host = p.getString("last_host", null) ?: return null
        return Speaker(
            p.getString("last_name", host) ?: host,
            host,
            p.getInt("last_port", 7000),
        )
    }

    // --- EQ ------------------------------------------------------------

    fun eqGains(ctx: Context): DoubleArray {
        val raw = prefs(ctx).getString("eq_gains", null)
            ?: return DoubleArray(DspProcessor.BAND_COUNT)
        val parts = raw.split(",")
        if (parts.size != DspProcessor.BAND_COUNT) {
            return DoubleArray(DspProcessor.BAND_COUNT)
        }
        return DoubleArray(DspProcessor.BAND_COUNT) {
            parts[it].toDoubleOrNull() ?: 0.0
        }
    }

    fun eqPreamp(ctx: Context): Double =
        prefs(ctx).getFloat("eq_preamp", 0f).toDouble()

    fun saveEq(ctx: Context, gains: DoubleArray, preampDb: Double) {
        prefs(ctx).edit()
            .putString("eq_gains", gains.joinToString(","))
            .putFloat("eq_preamp", preampDb.toFloat())
            .apply()
    }

    /** Load the persisted EQ into the live processor. */
    fun loadEq(ctx: Context) {
        Eq.apply(eqGains(ctx), eqPreamp(ctx))
    }

    // --- Per-app capture -------------------------------------------------

    /** Packages chosen for per-app capture; empty = capture all apps. */
    fun capturePackages(ctx: Context): Set<String> =
        prefs(ctx).getStringSet("capture_packages", emptySet()) ?: emptySet()

    fun saveCapturePackages(ctx: Context, packages: Set<String>) {
        prefs(ctx).edit().putStringSet("capture_packages", packages).apply()
    }
}
