package com.emerge.app.analysis

/** Avoid creating a native regex matcher for the usual already-normalized field.
 * The guard is a superset of both JVM and Android regex whitespace; the existing
 * expression still decides which characters actually collapse on each runtime. */
internal object LiteralWhitespace {
    private val expression = Regex("\\s+")

    fun collapse(value: String): String =
        if (value.any { it.isWhitespace() || it == '\u0085' }) value.replace(expression, " ") else value
}
