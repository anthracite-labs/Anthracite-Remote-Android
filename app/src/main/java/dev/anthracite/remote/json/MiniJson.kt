package dev.anthracite.remote.json

/**
 * A strict, bounded JSON reader.
 *
 * The app talks to the GitHub API and to CLI tools that emit JSON. Android's
 * `org.json` is a stub in JVM unit tests, so relying on it would make every
 * parser untestable off-device. This reader is small, dependency-free and
 * deliberately strict:
 *
 * * depth, string length, element count and total size are capped, so a hostile
 *   or corrupted response cannot exhaust the phone;
 * * trailing data, duplicate-free semantics and non-finite numbers are rejected
 *   instead of silently accepted.
 */
internal sealed class JsonValue {
    data class Obj(val entries: Map<String, JsonValue>) : JsonValue() {
        operator fun get(key: String): JsonValue? = entries[key]
        fun string(key: String): String? = (entries[key] as? Str)?.value
        fun long(key: String): Long? = (entries[key] as? Num)?.long
        fun int(key: String): Int? = (entries[key] as? Num)?.long?.toInt()
        fun bool(key: String): Boolean? = (entries[key] as? Bool)?.value
        fun obj(key: String): Obj? = entries[key] as? Obj
        fun array(key: String): Arr? = entries[key] as? Arr
    }

    data class Arr(val items: List<JsonValue>) : JsonValue()

    data class Str(val value: String) : JsonValue()

    data class Num(val value: Double) : JsonValue() {
        val long: Long get() = value.toLong()
    }

    data class Bool(val value: Boolean) : JsonValue()

    object Null : JsonValue()

    fun asString(): String? = when (this) {
        is Str -> value
        is Num -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is Bool -> value.toString()
        else -> null
    }

    fun asArray(): Arr? = this as? Arr

    fun asObject(): Obj? = this as? Obj
}

internal class JsonException(message: String) : Exception(message)

internal object MiniJson {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_DEPTH = 64
    const val MAX_ELEMENTS = 100_000
    const val MAX_STRING = 1 * 1024 * 1024

    fun parse(text: String): JsonValue {
        if (text.length > MAX_BYTES) throw JsonException("JSON document is too large")
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.readValue(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) throw JsonException("trailing data at offset ${parser.position}")
        return value
    }

    private class Parser(private val text: String) {
        var position = 0
            private set
        private var elements = 0

        fun atEnd(): Boolean = position >= text.length

        fun skipWhitespace() {
            while (position < text.length) {
                when (text[position]) {
                    ' ', '\t', '\n', '\r' -> position++
                    else -> return
                }
            }
        }

        fun readValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) throw JsonException("JSON nesting is too deep")
            if (atEnd()) throw JsonException("unexpected end of JSON")
            return when (val char = text[position]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> JsonValue.Str(readString())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> if (char == '-' || char.isDigit()) readNumber() else {
                    throw JsonException("unexpected character '$char' at offset $position")
                }
            }
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, position)) throw JsonException("invalid literal at offset $position")
            position += word.length
            return value
        }

        private fun readObject(depth: Int): JsonValue {
            position++ // {
            val entries = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (!atEnd() && text[position] == '}') {
                position++
                return JsonValue.Obj(entries)
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || text[position] != '"') throw JsonException("object key must be a string at offset $position")
                val key = readString()
                skipWhitespace()
                if (atEnd() || text[position] != ':') throw JsonException("expected ':' at offset $position")
                position++
                skipWhitespace()
                entries[key] = readValue(depth + 1)
                countElement()
                skipWhitespace()
                if (atEnd()) throw JsonException("unterminated object")
                when (text[position]) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return JsonValue.Obj(entries)
                    }
                    else -> throw JsonException("expected ',' or '}' at offset $position")
                }
            }
        }

        private fun readArray(depth: Int): JsonValue {
            position++ // [
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (!atEnd() && text[position] == ']') {
                position++
                return JsonValue.Arr(items)
            }
            while (true) {
                skipWhitespace()
                items += readValue(depth + 1)
                countElement()
                skipWhitespace()
                if (atEnd()) throw JsonException("unterminated array")
                when (text[position]) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return JsonValue.Arr(items)
                    }
                    else -> throw JsonException("expected ',' or ']' at offset $position")
                }
            }
        }

        private fun countElement() {
            elements++
            if (elements > MAX_ELEMENTS) throw JsonException("JSON document has too many elements")
        }

        private fun readString(): String {
            position++ // opening quote
            val builder = StringBuilder(32)
            while (true) {
                if (atEnd()) throw JsonException("unterminated string")
                val char = text[position++]
                when {
                    char == '"' -> return builder.toString()
                    char == '\\' -> {
                        if (atEnd()) throw JsonException("unterminated escape")
                        when (val escape = text[position++]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000c')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (position + 4 > text.length) throw JsonException("truncated unicode escape")
                                val hex = text.substring(position, position + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonException("invalid unicode escape \"$hex\"")
                                position += 4
                                builder.append(code.toChar())
                            }
                            else -> throw JsonException("invalid escape \\$escape")
                        }
                    }
                    char.code < 0x20 -> throw JsonException("unescaped control character in string")
                    else -> builder.append(char)
                }
                if (builder.length > MAX_STRING) throw JsonException("JSON string is too long")
            }
        }

        private fun readNumber(): JsonValue {
            val start = position
            if (!atEnd() && text[position] == '-') position++
            if (atEnd() || !text[position].isDigit()) throw JsonException("expected a digit at offset $position")
            // JSON forbids a leading zero (except for "0" itself).
            if (text[position] == '0' && position + 1 < text.length && text[position + 1].isDigit()) {
                throw JsonException("leading zero at offset $position")
            }
            while (!atEnd() && text[position].isDigit()) position++
            if (!atEnd() && text[position] == '.') {
                position++
                if (atEnd() || !text[position].isDigit()) {
                    throw JsonException("expected a digit after the decimal point at offset $position")
                }
                while (!atEnd() && text[position].isDigit()) position++
            }
            if (!atEnd() && (text[position] == 'e' || text[position] == 'E')) {
                position++
                if (!atEnd() && (text[position] == '+' || text[position] == '-')) position++
                if (atEnd() || !text[position].isDigit()) {
                    throw JsonException("expected a digit in the exponent at offset $position")
                }
                while (!atEnd() && text[position].isDigit()) position++
            }
            val raw = text.substring(start, position)
            val value = raw.toDoubleOrNull() ?: throw JsonException("invalid number \"$raw\"")
            if (!value.isFinite()) throw JsonException("number is not finite")
            return JsonValue.Num(value)
        }
    }
}
