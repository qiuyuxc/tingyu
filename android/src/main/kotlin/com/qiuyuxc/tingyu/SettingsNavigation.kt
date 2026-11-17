package com.qiuyuxc.tingyu

/** A preference change rebuilds the current page; only a navigation action changes this stack. */
internal class SettingsNavigation {
    var page: String = "home"
        private set

    private val stack = mutableListOf<String>()
    var returnTab: Int? = null
        private set

    val history: List<String>
        get() = stack.toList()

    fun open(next: String, fromTab: Int = 1) {
        val destination = canonicalPage(next)
        require(destination in PAGES)
        if (fromTab != 1) {
            reset()
            returnTab = 0
        }
        if (destination == page) return
        stack += page
        page = destination
    }

    fun back(): Boolean {
        if (stack.isEmpty()) return false
        page = stack.removeAt(stack.lastIndex)
        return true
    }

    fun reset() {
        page = "home"
        stack.clear()
        returnTab = null
    }

    fun takeReturnTab(): Int? {
        if (page != "home") return null
        return returnTab.also { returnTab = null }
    }

    fun restore(current: String?, previous: List<String>, origin: Int? = null) {
        page = current?.let(::canonicalPage)?.takeIf { it in PAGES } ?: "home"
        stack.clear()
        stack.addAll(previous.map(::canonicalPage).filter { it in PAGES }.take(16))
        returnTab = origin?.takeIf { it == 0 && page != "home" }
    }

    companion object {
        private fun canonicalPage(page: String): String =
            if (page == "encryption") "security" else page

        private val PAGES =
            setOf(
                "home",
                "preferences",
                "accounts",
                "appearance",
                "playback",
                "storage",
                "cache",
                "about",
                "directories",
                "cloud",
                "security",
            )
    }
}
