package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * Serializa JSON EXACTAMENTE como `JSON.stringify` de JavaScript (sin sangría).
 * Es la base de la huella SHA-256 de los respaldos: la nativa y la 2.0 deben calcular la misma huella
 * para que un respaldo pase de una app a la otra. Mantiene el orden de las claves tal como vienen.
 */
object JsJson {
    fun stringify(e: JsonElement): String = StringBuilder().also { escribir(e, it) }.toString()

    private fun escribir(e: JsonElement, sb: StringBuilder) {
        when (e) {
            is JsonNull -> sb.append("null")
            is JsonObject -> {
                sb.append('{'); var primero = true
                for ((k, v) in e) { if (!primero) sb.append(','); primero = false; texto(k, sb); sb.append(':'); escribir(v, sb) }
                sb.append('}')
            }
            is JsonArray -> {
                sb.append('['); e.forEachIndexed { i, v -> if (i > 0) sb.append(','); escribir(v, sb) }; sb.append(']')
            }
            is JsonPrimitive -> when {
                e.isString -> texto(e.content, sb)
                e.content == "true" || e.content == "false" -> sb.append(e.content)
                else -> sb.append(numero(e.content))
            }
        }
    }

    /** Escapa un texto como JSON.stringify: solo comillas, barra invertida, controles y sustitutos sueltos. */
    fun texto(s: String, sb: StringBuilder = StringBuilder()): StringBuilder {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { sb.append(c).append(s[i + 1]); i++ }
                Character.isSurrogate(c) -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
            i++
        }
        return sb.append('"')
    }

    /** Número con el formato de Number.prototype.toString de JavaScript (a partir de sus dígitos significativos). */
    fun numero(literal: String): String {
        val bd = try { BigDecimal(literal) } catch (e: NumberFormatException) { return literal }
        if (bd.signum() == 0) return "0"
        val neg = bd.signum() < 0
        val s0 = bd.abs().stripTrailingZeros()
        val digitos = s0.unscaledValue().toString()
        val k = digitos.length
        val n = k - s0.scale()          // posición del punto decimal
        val r = when {
            n in k..21 -> digitos + "0".repeat(n - k)
            n in 1..21 -> digitos.substring(0, n) + "." + digitos.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digitos
            else -> {
                val exp = n - 1
                val mant = if (k == 1) digitos else digitos[0] + "." + digitos.substring(1)
                mant + "e" + (if (exp >= 0) "+" else "-") + kotlin.math.abs(exp)
            }
        }
        return if (neg) "-$r" else r
    }

    /** Número creado en la app (por ejemplo, una carga escrita por Héctor) con el literal que usaría JavaScript. */
    fun primitivo(d: Double): JsonPrimitive {
        val bd = BigDecimal(d.toString())
        return if (bd.stripTrailingZeros().scale() <= 0 && bd.abs() < BigDecimal("1e15")) JsonPrimitive(bd.toLong()) else JsonPrimitive(bd.stripTrailingZeros())
    }

    fun sha256(texto: String): String =
        MessageDigest.getInstance("SHA-256").digest(texto.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
