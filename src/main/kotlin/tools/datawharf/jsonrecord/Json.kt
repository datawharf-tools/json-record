package tools.datawharf.jsonrecord

/** Minimal JSON model + parser with no dependencies, so it is unit-testable without the IDE. */
sealed class Json {
    data class Obj(val fields: LinkedHashMap<String, Json>) : Json()
    data class Arr(val items: List<Json>) : Json()
    data class Str(val value: String) : Json()
    data class Num(val raw: String) : Json() {
        val isInteger get() = !raw.contains('.') && !raw.contains('e') && !raw.contains('E')
    }
    data class Bool(val value: Boolean) : Json()
    object Null : Json()
}

class JsonParseException(message: String) : Exception(message)

object JsonParser {
    fun parse(text: String): Json {
        val p = Impl(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (p.pos != text.length) throw JsonParseException("Unexpected trailing content at position ${p.pos}")
        return v
    }

    private class Impl(val s: String) {
        var pos = 0
        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
        fun fail(msg: String): Nothing = throw JsonParseException("$msg at position $pos")

        fun value(): Json {
            if (pos >= s.length) fail("Unexpected end of input")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> Json.Str(str())
                't' -> lit("true", Json.Bool(true))
                'f' -> lit("false", Json.Bool(false))
                'n' -> lit("null", Json.Null)
                else -> if (c == '-' || c.isDigit()) num() else fail("Unexpected character '$c'")
            }
        }

        fun lit(word: String, v: Json): Json {
            if (!s.startsWith(word, pos)) fail("Invalid literal")
            pos += word.length
            return v
        }

        fun num(): Json {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val raw = s.substring(start, pos)
            if (raw.toDoubleOrNull() == null) fail("Invalid number '$raw'")
            return Json.Num(raw)
        }

        fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("Unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) fail("Bad escape")
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("Bad unicode escape")
                                val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: fail("Bad unicode escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("Bad escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun arr(): Json {
            pos++
            val items = ArrayList<Json>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return Json.Arr(items) }
            while (true) {
                skipWs(); items.add(value()); skipWs()
                if (pos >= s.length) fail("Unterminated array")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return Json.Arr(items)
                    else -> { pos--; fail("Expected ',' or ']'") }
                }
            }
        }

        fun obj(): Json {
            pos++
            val fields = LinkedHashMap<String, Json>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return Json.Obj(fields) }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') fail("Expected string key")
                val k = str(); skipWs()
                if (pos >= s.length || s[pos] != ':') fail("Expected ':'")
                pos++; skipWs()
                fields[k] = value(); skipWs()
                if (pos >= s.length) fail("Unterminated object")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return Json.Obj(fields)
                    else -> { pos--; fail("Expected ',' or '}'") }
                }
            }
        }
    }
}
