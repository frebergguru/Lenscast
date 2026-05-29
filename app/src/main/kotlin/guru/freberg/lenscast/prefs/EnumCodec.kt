package guru.freberg.lenscast.prefs

/**
 * Case-insensitive [Enum] lookup by name, shared by the settings JSON codec and the
 * web-control field setters — both parse stringly-typed enum values off the wire and need
 * the same lenient matching. Returns null when [name] is null, blank, or matches no constant.
 */
inline fun <reified T : Enum<T>> enumValueOrNull(name: String?): T? {
    if (name.isNullOrEmpty()) return null
    return enumValues<T>().firstOrNull { it.name.equals(name, ignoreCase = true) }
}
