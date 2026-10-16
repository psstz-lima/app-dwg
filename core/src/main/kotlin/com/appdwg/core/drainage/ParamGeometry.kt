package com.appdwg.core.drainage

/**
 * A point of a device cross-section, in metres. Y grows upward; the terrain reference is y = 0
 * when the catalog draws the section that way.
 */
data class SectionPoint(val x: Double, val y: Double)

/**
 * The section produced by evaluating the catalog's vertex expressions.
 * The first ring is the one the album labels `vertices`, or `inner` when that is the outline.
 */
data class CrossSection(
    val type: String,
    val units: String,
    val rings: List<List<SectionPoint>>,
) {
    val points: List<SectionPoint> get() = rings.firstOrNull().orEmpty()

    /** Area of the first ring, in square metres. */
    val areaM2: Double? get() = points.takeIf { it.size >= 3 }?.let { polygonArea(it) }

    /**
     * Excavation in cubic metres per metre when the ring is the cut itself
     * (open sections and rectangular trenches). A lined ring is the waterway;
     * its tabulated excavation stays on [Quantities].
     */
    val excavationM3PerM: Double?
        get() = if (type.endsWith("_open") || type == "rect_trench") areaM2 else null
}

/** Evaluates `+ - * / ( )` and parameter names. Anything else yields null — there is no script eval. */
object ParamExpr {
    fun eval(expression: String, variables: Map<String, Double>): Double? = try {
        Expr(expression.trim(), variables).parse()
    } catch (_: ExprError) {
        null
    }
}

internal fun bindings(vararg objects: Jv.Obj?): Map<String, Double> {
    val env = HashMap<String, Double>()
    for (obj in objects) {
        if (obj == null) continue
        for ((key, value) in obj.fields) {
            val n = (value as? Jv.Num)?.n ?: continue
            env.putIfAbsent(key, n)
            if ((key.endsWith("_cm") || key.endsWith("_m")) && key.length > 3) {
                env.putIfAbsent(key.dropLast(3), n)
            }
        }
    }
    return env
}

internal fun crossSection(geometry: Jv.Obj?, env: Map<String, Double>): CrossSection? {
    if (geometry == null) return null
    val type = geometry.str("type") ?: ""
    val units = geometry.str("units") ?: "cm"
    val keys = LinkedHashSet<String>()
    keys.add("vertices")
    keys.add("inner")
    keys.add("outer")
    keys.addAll(geometry.fields.keys)
    val rings = ArrayList<List<SectionPoint>>()
    for (key in keys) {
        val array = geometry.fields[key] as? Jv.Arr ?: continue
        val texts = array.items.mapNotNull { (it as? Jv.Str)?.text }
        if (texts.none { '(' in it && ',' in it }) continue
        val ring = ring(texts, env, units) ?: continue
        if (ring.size >= 3) rings += ring
    }
    return CrossSection(type, units, rings)
}

/** Section of [device] after numeric parameter edits. An empty override map keeps the catalog section. */
fun resolveSection(device: DeviceDef, overrides: Map<String, Double>): CrossSection? {
    if (overrides.isEmpty() || device.outline.isEmpty()) return device.section
    val env = HashMap<String, Double>()
    for (parameter in device.parameters) {
        val number = parameter.number ?: continue
        env.putIfAbsent(parameter.key, number)
        if ((parameter.key.endsWith("_cm") || parameter.key.endsWith("_m")) && parameter.key.length > 3) {
            env.putIfAbsent(parameter.key.dropLast(3), number)
        }
    }
    for ((key, value) in overrides) {
        env[key] = value
        if ((key.endsWith("_cm") || key.endsWith("_m")) && key.length > 3) env[key.dropLast(3)] = value
    }
    val ring = ring(device.outline, env, device.outlineUnits) ?: return device.section
    val type = device.outlineType.ifEmpty { device.section?.type ?: "" }
    return CrossSection(type, device.outlineUnits, listOf(ring))
}

internal fun ring(texts: List<String>, env: Map<String, Double>, units: String): List<SectionPoint>? {
    val points = ArrayList<SectionPoint>(texts.size)
    for (raw in texts) {
        val pair = coordinate(raw) ?: return null
        val x = ParamExpr.eval(pair.first, env) ?: return null
        val y = ParamExpr.eval(pair.second, env) ?: return null
        points += SectionPoint(toMetres(x, units), toMetres(y, units))
    }
    return points
}

/** The first `(x, y)` pair. A label after the closing parenthesis is ignored. */
internal fun coordinate(raw: String): Pair<String, String>? {
    val open = raw.indexOf('(')
    if (open < 0) return null
    var depth = 0
    var comma = -1
    var i = open
    while (i < raw.length) {
        when (raw[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) {
                    if (comma < 0) return null
                    return raw.substring(open + 1, comma).trim() to raw.substring(comma + 1, i).trim()
                }
            }
            ',' -> if (depth == 1 && comma < 0) comma = i
        }
        i++
    }
    return null
}

private fun toMetres(value: Double, units: String): Double = when (units.trim().lowercase()) {
    "mm" -> value / 1000.0
    "cm" -> value / 100.0
    else -> value
}

private fun polygonArea(points: List<SectionPoint>): Double {
    var sum = 0.0
    for (i in points.indices) {
        val a = points[i]
        val b = points[(i + 1) % points.size]
        sum += a.x * b.y - b.x * a.y
    }
    return kotlin.math.abs(sum) / 2.0
}

private class ExprError : Exception()

private class Expr(private val text: String, private val env: Map<String, Double>) {
    private var i = 0

    fun parse(): Double {
        val value = add()
        skip()
        if (i != text.length) throw ExprError()
        return value
    }

    private fun add(): Double {
        var value = mul()
        while (true) {
            skip()
            val op = peek()
            if (op == '+' || op == '-') {
                i++
                val right = mul()
                value = if (op == '+') value + right else value - right
            } else break
        }
        return value
    }

    private fun mul(): Double {
        var value = unary()
        while (true) {
            skip()
            val op = peek()
            if (op == '*' || op == '/') {
                i++
                val right = unary()
                if (op == '/' && right == 0.0) throw ExprError()
                value = if (op == '*') value * right else value / right
            } else break
        }
        return value
    }

    private fun unary(): Double {
        skip()
        val op = peek()
        if (op == '+' || op == '-') {
            i++
            val value = unary()
            return if (op == '+') value else -value
        }
        return primary()
    }

    private fun primary(): Double {
        skip()
        val c = peek()
        if (c == '(') {
            i++
            val value = add()
            skip()
            if (peek() != ')') throw ExprError()
            i++
            return value
        }
        if (c.isDigit() || c == '.') return number()
        if (c.isLetter() || c == '_') return variable()
        throw ExprError()
    }

    private fun number(): Double {
        val start = i
        while (peek().isDigit() || peek() == '.') i++
        return text.substring(start, i).toDoubleOrNull() ?: throw ExprError()
    }

    private fun variable(): Double {
        val start = i
        while (peek().isLetterOrDigit() || peek() == '_') i++
        val name = text.substring(start, i)
        return env[name] ?: throw ExprError()
    }

    private fun skip() {
        while (peek().isWhitespace()) i++
    }

    private fun peek(): Char = if (i < text.length) text[i] else '\u0000'
}
