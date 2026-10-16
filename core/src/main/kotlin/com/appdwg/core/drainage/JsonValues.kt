package com.appdwg.core.drainage

/** JSON values the catalog loader understands. Object and array order follows the file. */
internal sealed interface Jv {
    data class Obj(val fields: Map<String, Jv>) : Jv
    data class Arr(val items: List<Jv>) : Jv
    data class Str(val text: String) : Jv
    data class Num(val n: Double) : Jv
    data object Yes : Jv
    data object No : Jv
    data object Null : Jv
}

internal fun Jv.obj(): Jv.Obj? = this as? Jv.Obj

internal fun Jv.Obj.str(key: String): String? = (fields[key] as? Jv.Str)?.text

internal fun Jv.Obj.num(key: String): Double? = (fields[key] as? Jv.Num)?.n

internal fun Jv.Obj.child(key: String): Jv.Obj? = fields[key] as? Jv.Obj

internal fun parseJv(text: String): Jv {
    val parser = JvParser(text)
    val value = parser.parseValue()
    parser.skip()
    if (!parser.end()) parser.fail()
    return value
}

private class JvParser(private val text: String) {
    private var i = 0

    init {
        if (text.startsWith("\uFEFF")) i = 1
    }

    fun end(): Boolean = i >= text.length

    fun parseValue(): Jv {
        skip()
        if (end()) fail()
        return when (val c = text[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> Jv.Str(parseString())
            't' -> literal("true", Jv.Yes)
            'f' -> literal("false", Jv.No)
            'n' -> literal("null", Jv.Null)
            else -> if (c == '-' || c.isDigit()) parseNumber() else fail()
        }
    }

    fun skip() {
        while (!end() && text[i].isWhitespace()) i++
    }

    fun fail(): Nothing = throw CatalogFormatException(CatalogFormatException.Kind.SYNTAX, i.toString())

    private fun parseObject(): Jv.Obj {
        expect('{')
        val fields = LinkedHashMap<String, Jv>()
        skip()
        if (peek() == '}') {
            i++
            return Jv.Obj(fields)
        }
        while (true) {
            skip()
            if (peek() != '"') fail()
            val key = parseString()
            skip()
            expect(':')
            fields[key] = parseValue()
            skip()
            when (peek()) {
                ',' -> i++
                '}' -> {
                    i++
                    return Jv.Obj(fields)
                }
                else -> fail()
            }
        }
    }

    private fun parseArray(): Jv.Arr {
        expect('[')
        val items = ArrayList<Jv>()
        skip()
        if (peek() == ']') {
            i++
            return Jv.Arr(items)
        }
        while (true) {
            items += parseValue()
            skip()
            when (peek()) {
                ',' -> i++
                ']' -> {
                    i++
                    return Jv.Arr(items)
                }
                else -> fail()
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (!end()) {
            when (val c = text[i++]) {
                '"' -> return out.toString()
                '\\' -> {
                    if (end()) fail()
                    when (val esc = text[i++]) {
                        '"', '\\', '/' -> out.append(esc)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (i + 4 > text.length) fail()
                            val hex = text.substring(i, i + 4)
                            i += 4
                            out.append(hex.toIntOrNull(16)?.toChar() ?: fail())
                        }
                        else -> fail()
                    }
                }
                else -> out.append(c)
            }
        }
        fail()
    }

    private fun parseNumber(): Jv.Num {
        val start = i
        if (peek() == '-') i++
        if (!peek().isDigit()) fail()
        while (peek().isDigit()) i++
        if (peek() == '.') {
            i++
            if (!peek().isDigit()) fail()
            while (peek().isDigit()) i++
        }
        if (peek() == 'e' || peek() == 'E') {
            i++
            if (peek() == '+' || peek() == '-') i++
            if (!peek().isDigit()) fail()
            while (peek().isDigit()) i++
        }
        val n = text.substring(start, i).toDoubleOrNull() ?: fail()
        if (!n.isFinite()) fail()
        return Jv.Num(n)
    }

    private fun literal(word: String, value: Jv): Jv {
        if (!text.startsWith(word, i)) fail()
        i += word.length
        return value
    }

    private fun expect(c: Char) {
        if (peek() != c) fail()
        i++
    }

    private fun peek(): Char = if (end()) '\u0000' else text[i]
}
