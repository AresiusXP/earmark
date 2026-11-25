package xyz.aresius.audiobooks.core.design

/** The in-app theme choice (Settings > Appearance), per device. [System] follows the phone and is the default. */
enum class ThemeMode(val key: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    /** Whether the dark (ink) palette is active for this choice, given the phone's own setting. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }

    companion object {
        /** Unknown or missing stored values fall back to [System]. */
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: System
    }
}

/** The full-screen player is always dark (cover-derived ink) unless the user lets it follow the theme. */
fun playerIsDark(appDark: Boolean, followsTheme: Boolean): Boolean = !followsTheme || appDark
