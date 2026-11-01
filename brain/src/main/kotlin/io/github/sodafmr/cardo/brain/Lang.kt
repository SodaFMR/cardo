package io.github.sodafmr.cardo.brain

enum class Lang {
    EN,
    ES,
    ;

    companion object {
        /**
         * Maps a BCP 47 / POSIX locale tag (`es-ES`, `en_US`, `EN`) to a supported language by its
         * language subtag, ignoring case. Returns null for unsupported languages.
         */
        fun fromTag(tag: String): Lang? {
            val language = tag.substringBefore('-').substringBefore('_')
            return entries.firstOrNull { it.name.equals(language, ignoreCase = true) }
        }
    }
}
