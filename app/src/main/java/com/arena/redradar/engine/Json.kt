package com.arena.redradar.engine

/**
 * Минимальный JSON-парсер: дерево из Map, List, String, Double, Boolean, null.
 * Не зависит от org.json — работает и на Android, и на настольной JVM.
 */
object Json {

    class JsonError(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val p = P(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (!p.eof()) throw JsonError("лишний текст после значения, позиция ${p.i}")
        return v
    }

    private class P(val s: String) {
        var i = 0

        fun eof() = i >= s.length

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\r' || s[i] == '\n')) i++
        }

        fun value(): Any? {
            if (eof()) throw JsonError("неожиданный конец текста")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (i + word.length > s.length || s.substring(i, i + word.length) != word)
                throw JsonError("ожидалось '$word', позиция $i")
            i += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            i++ // {
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (!eof() && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (eof() || s[i] != '"') throw JsonError("ожидался ключ-строка, позиция $i")
                val k = str()
                ws()
                if (eof() || s[i] != ':') throw JsonError("ожидалось ':' после ключа, позиция $i")
                i++
                ws()
                m[k] = value()
                ws()
                if (eof()) throw JsonError("незакрытый объект")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw JsonError("ожидались ',' или '}', позиция $i")
                }
            }
        }

        fun arr(): List<Any?> {
            i++ // [
            val a = ArrayList<Any?>()
            ws()
            if (!eof() && s[i] == ']') { i++; return a }
            while (true) {
                ws()
                a.add(value())
                ws()
                if (eof()) throw JsonError("незакрытый массив")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return a }
                    else -> throw JsonError("ожидались ',' или ']', позиция $i")
                }
            }
        }

        fun str(): String {
            i++ // "
            val b = StringBuilder()
            while (true) {
                if (eof()) throw JsonError("незакрытая строка")
                val c = s[i]
                if (c == '"') { i++; return b.toString() }
                if (c == '\\') {
                    i++
                    if (eof()) throw JsonError("незакрытая строка")
                    when (val e = s[i]) {
                        '"' -> b.append('"')
                        '\\' -> b.append('\\')
                        '/' -> b.append('/')
                        'b' -> b.append('\b')
                        'f' -> b.append('\u000C')
                        'n' -> b.append('\n')
                        'r' -> b.append('\r')
                        't' -> b.append('\t')
                        'u' -> {
                            if (i + 4 >= s.length) throw JsonError("обрезанная \\u-последовательность")
                            val hex = s.substring(i + 1, i + 5)
                            b.append(hex.toIntOrNull(16)?.toChar() ?: throw JsonError("плохая \\u-последовательность: $hex"))
                            i += 4
                        }
                        else -> throw JsonError("неизвестный экранирующий символ: $e")
                    }
                    i++
                } else {
                    b.append(c)
                    i++
                }
            }
        }

        fun num(): Any {
            val start = i
            if (!eof() && (s[i] == '-' || s[i] == '+')) i++
            while (!eof() && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '-' || s[i] == '+')) i++
            val t = s.substring(start, i)
            val d = t.toDoubleOrNull() ?: throw JsonError("плохое число: '$t', позиция $start")
            return d
        }
    }
}
