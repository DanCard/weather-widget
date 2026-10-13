package com.weatherwidget.shared.util

/**
 * The guard tests' rule for [UserRefreshRunner]: which fetch calls in a source file are NOT inside a
 * `…RefreshRunner.start(…) { … }` block. Android and desktop each pin their UI code with it, so
 * "inside the runner" means one thing on both platforms.
 *
 * A block runs from `start(` through its matching `)` and, when one follows, its trailing lambda —
 * so a call inside `afterDetached = { … }` counts as inside too. `//` line comments and the contents
 * of string literals (templates included) are skipped.
 */
object UserRefreshCallSites {
    private val startCall = Regex("""\w*[Rr]efreshRunner\.start\(""")

    /** 1-based line numbers of [fetchCall] matches outside every runner block in [source]. */
    fun uncovered(source: String, fetchCall: Regex): List<Int> {
        val code = stripLineComments(source)
        val blocks = startCall.findAll(code).map { blockRange(code, it.range.first, it.range.last) }.toList()
        return fetchCall.findAll(code)
            .filter { m -> blocks.none { m.range.first in it } }
            .map { m -> code.substring(0, m.range.first).count { it == '\n' } + 1 }
            .distinct()
            .toList()
    }

    /** Count of [fetchCall] matches in [source] — lets a guard prove it scanned something. */
    fun count(source: String, fetchCall: Regex): Int = fetchCall.findAll(stripLineComments(source)).count()

    private fun blockRange(code: String, start: Int, openParen: Int): IntRange {
        val closeParen = matching(code, openParen, '(', ')')
        var i = closeParen + 1
        while (i < code.length && code[i].isWhitespace()) i++
        val end = if (i < code.length && code[i] == '{') matching(code, i, '{', '}') else closeParen
        return start..end
    }

    private fun matching(code: String, open: Int, openCh: Char, closeCh: Char): Int {
        var depth = 0
        var inString = false
        for (i in open until code.length) {
            val c = code[i]
            if (c == '"' && code[i - 1] != '\\') inString = !inString
            if (inString) continue
            when (c) {
                openCh -> depth++
                closeCh -> if (--depth == 0) return i
            }
        }
        return code.length - 1
    }

    /** Blanks `//` comments (keeping line breaks and offsets); `//` inside a string is left alone. */
    private fun stripLineComments(source: String): String =
        source.lines().joinToString("\n") { line ->
            var inString = false
            var cut = line.length
            var i = 0
            while (i < line.length - 1) {
                val c = line[i]
                if (c == '"' && (i == 0 || line[i - 1] != '\\')) inString = !inString
                if (!inString && c == '/' && line[i + 1] == '/') { cut = i; break }
                i++
            }
            line.substring(0, cut) + " ".repeat(line.length - cut)
        }
}
