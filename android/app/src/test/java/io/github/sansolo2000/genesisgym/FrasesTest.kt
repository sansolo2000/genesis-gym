package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** M-06: frase del día según CONTEXTO-MODULO-APOYO §5.1. Solo datos inventados. */
class FrasesTest {
    private val base = Json.parseToJsonElement(javaClass.classLoader!!.getResource("prueba-apoyo-programa.json")!!.readText()).jsonObject
    private fun l(p: String, n: Int) = JsonArray((0 until n).map { JsonPrimitive("$p$it") })
    private fun con(fr: JsonObject?) = if (fr == null) base else JsonObject(base + ("frases_diarias" to fr))
    private fun fr(desde: String = "2026-10-14", f: Int = 10, m: Int = 10, d: Int = 10) = JsonObject(mapOf(
        "rotacion" to JsonPrimitive("por_tipo_dia"), "desde" to JsonPrimitive(desde), "fuerza" to l("F", f), "mtb" to l("M", m), "descanso" to l("D", d)))
    private fun dia(s: String) = LocalDate.parse(s)

    @Test fun ejemploDelContrato() {
        val p = con(fr())   // plan: lun/mié/vie fuerza, mar/jue descanso, sáb/dom mtb
        assertEquals(listOf("F0", "D0", "F1", "M0", "M1", "F2"),
            listOf("2026-10-14", "2026-10-15", "2026-10-16", "2026-10-17", "2026-10-18", "2026-10-19").map { Frases.delDia(p, dia(it)) })
    }

    @Test fun mismaFraseTodoElDiaYCambiaAlSiguiente() {
        val p = con(fr())
        assertEquals(Frases.delDia(p, dia("2026-10-21")), Frases.delDia(p, dia("2026-10-21")))
        assertEquals("F3", Frases.delDia(p, dia("2026-10-21")))
    }

    @Test fun vuelveAlInicioAlTerminarLaLista() {
        val p = con(fr(f = 2))
        assertEquals(listOf("F0", "F1", "F0", "F1"), listOf("2026-10-14", "2026-10-16", "2026-10-19", "2026-10-21").map { Frases.delDia(p, dia(it)) })
    }

    @Test fun calculoLargoCoincideConContarDiaADia() {
        val p = con(fr(f = 7, m = 3, d = 5))
        var x = dia("2026-10-14"); val k = mutableMapOf<String, Int>()
        while (x.isBefore(dia("2027-03-01"))) {
            val t = Frases.tipoDe(p, x)!!; val n = k.getOrDefault(t, 0)
            val largo = mapOf("fuerza" to 7, "mtb" to 3, "descanso" to 5)[t]!!
            assertEquals(x.toString(), "${t.first().uppercaseChar()}${n % largo}", Frases.delDia(p, x))
            k[t] = n + 1; x = x.plusDays(1)
        }
    }

    @Test fun antesDeDesdeUsaLaPrimera() = assertEquals("D0", Frases.delDia(con(fr()), dia("2026-10-13")))

    @Test fun sinCampoOListaVaciaNoHayFrase() {
        assertNull(Frases.delDia(con(null), dia("2026-10-14")))
        assertNull(Frases.delDia(con(fr(f = 0)), dia("2026-10-14")))
        assertNull(Frases.delDia(null, dia("2026-10-14")))
    }

    @Test fun validacionDelCampo() {
        val (_, ok) = Apoyo.revisarTexto(JsJson.stringify(con(fr())), null)
        assertTrue(ok.errores.toString(), ok.ok)
        val malo = JsonObject(fr() + ("rotacion" to JsonPrimitive("al_azar")) + ("desde" to JsonPrimitive("14-10-2026")))
        val (_, r) = Apoyo.revisarTexto(JsJson.stringify(con(malo)), null)
        assertFalse(r.ok); assertEquals(2, r.errores.count { it.startsWith("frases_diarias") })
        val largo = JsonObject(fr() + ("fuerza" to JsonArray(listOf(JsonPrimitive("x".repeat(141))))))
        assertFalse(Apoyo.revisarTexto(JsJson.stringify(con(largo)), null).second.ok)
    }

    @Test fun advertencias() {
        val sinMtb = JsonObject(fr().filterKeys { it != "mtb" } + ("descanso" to JsonArray(listOf(JsonPrimitive("Hola {nombre}")))))
        val (_, r) = Apoyo.revisarTexto(JsJson.stringify(con(sinMtb)), null)
        assertTrue(r.errores.toString(), r.ok)
        assertTrue(r.avisos.any { it.contains("mtb") }); assertTrue(r.avisos.any { it.contains("{ o }") })
    }
}
