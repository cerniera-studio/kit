package com.cerniera.kit

/**
 * The family look's decisions, pure so they are tested on the JVM (26 Nov 2026). See [Family].
 */
object FamilyRules {
    /** Where the look a widget wears came from. */
    enum class Source {
        /** Read just now from Fold Widgets: the one look of the family. */
        HOST,
        /** Fold Widgets is installed but could not answer this time: the last look it gave. */
        REMEMBERED,
        /** No Fold Widgets on the phone: the app's own choice. */
        OWN
    }

    /** A look as Fold Widgets gives it: a finish's name, the corners in dp, and its version. */
    data class Answer(val finish: String, val radius: Int, val stamp: Long)

    data class Decision(val finish: String, val radius: Int, val source: Source)

    /**
     * The look to wear. With Fold Widgets installed, its answer always wins. When it cannot answer
     * (its process starting, a moment's failure) or names a finish this build doesn't know, the last
     * look it gave is kept. Before 26 Nov a follower fell back to Vetro at 24 there, so one widget on
     * the home screen could sit in a different finish from the rest until something redrew it. The
     * app's own choice is used only when there is no Fold Widgets at all.
     */
    fun decide(hostInstalled: Boolean, answer: Answer?, remembered: Answer?, own: String, ownRadius: Int, known: Set<String>): Decision {
        if (hostInstalled) {
            if (answer != null && answer.finish in known) return Decision(answer.finish, clampRadius(answer.radius), Source.HOST)
            if (remembered != null && remembered.finish in known) return Decision(remembered.finish, clampRadius(remembered.radius), Source.REMEMBERED)
        }
        return Decision(if (own in known) own else "VETRO", clampRadius(ownRadius), Source.OWN)
    }

    fun clampRadius(dp: Int) = dp.coerceIn(0, 40)

    /** Cerniera's own apps: the only ones Fold Widgets shares the wallpaper and the seats with. */
    fun isFamily(pkg: String?): Boolean = pkg != null && (pkg.startsWith("com.cerniera.") || pkg.startsWith("com.fergusonconsultancy."))

    /**
     * Gives each place seen by Fold Widgets' live mode to one widget: in the order seen, the
     * unclaimed widget at the least [distance]. Never two places to one widget.
     */
    fun <S> assign(seen: List<S>, ids: List<Int>, distance: (S, Int) -> Float): Map<Int, S> {
        val out = LinkedHashMap<Int, S>()
        for (s in seen) {
            val id = ids.filter { it !in out }.minByOrNull { distance(s, it) } ?: break
            out[id] = s
        }
        return out
    }

    /** The glass's image from Fold Widgets for one screen, versioned so a change is never served from cache. */
    fun imageUri(authority: String, screen: String, stamp: Long) = "content://$authority/image/$screen?v=$stamp"
}
