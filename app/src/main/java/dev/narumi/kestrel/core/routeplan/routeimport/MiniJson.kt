package dev.narumi.kestrel.core.routeplan.routeimport

internal class JsonSyntaxException(
    message: String,
) : Exception(message)

/**
 * Minimal, dependency-free JSON reader for untrusted input. Objects become `Map<String, Any?>`,
 * arrays `List<Any?>`, numbers `Double`, plus `String`, `Boolean` and `null`. Nesting is capped so
 * hostile input cannot overflow the stack.
 */
internal object MiniJson {
    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.readValue(0)
        reader.skipWhitespace()
        if (!reader.atEnd()) reader.fail("unexpected content after the JSON value")
        return value
    }

    private class Reader(
        private val s: String,
    ) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun fail(what: String): Nothing = throw JsonSyntaxException("$what at position $i")

        fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("nesting is too deep")
            skipWhitespace()
            if (atEnd()) fail("unexpected end of input")
            return when (s[i]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> readNumber()
            }
        }

        private fun literal(
            word: String,
            value: Any?,
        ): Any? {
            if (!s.startsWith(word, i)) fail("invalid value")
            i += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            i++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') return map.also { i++ }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("expected a string key")
                val key = readString()
                skipWhitespace()
                if (peek() != ':') fail("expected ':'")
                i++
                map[key] = readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    '}' -> return map.also { i++ }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            i++ // [
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') return list.also { i++ }
            while (true) {
                list += readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    ']' -> return list.also { i++ }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun peek(): Char = if (i < s.length) s[i] else fail("unexpected end of input")

        private fun readString(): String {
            i++ // opening quote
            val out = StringBuilder()
            while (true) {
                val c = peek()
                i++
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> out.append(readEscape())
                    else -> out.append(c)
                }
            }
        }

        private fun readEscape(): Char {
            val c = peek()
            i++
            return when (c) {
                '"', '\\', '/' -> c
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> readUnicode()
                else -> fail("invalid escape")
            }
        }

        private fun readUnicode(): Char {
            if (i + UNICODE_DIGITS > s.length) fail("truncated \\u escape")
            val code = s.substring(i, i + UNICODE_DIGITS).toIntOrNull(HEX_RADIX) ?: fail("invalid \\u escape")
            i += UNICODE_DIGITS
            return code.toChar()
        }

        private fun readNumber(): Double {
            val start = i
            while (i < s.length && s[i] in NUMBER_CHARS) i++
            if (i == start) fail("invalid value")
            return s.substring(start, i).toDoubleOrNull() ?: fail("invalid number")
        }
    }

    private const val NUMBER_CHARS = "+-0123456789.eE"
    private const val UNICODE_DIGITS = 4
    private const val HEX_RADIX = 16
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.asJsonObject(): Map<String, Any?>? = this as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun Any?.asJsonArray(): List<Any?>? = this as? List<Any?>
