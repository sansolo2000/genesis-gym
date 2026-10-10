package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compara el validador Kotlin con el de JavaScript (1.0): cada caso de validador-casos.json trae el archivo
 * y los errores y avisos que dio el validador JS. Deben coincidir exactamente, en el mismo orden.
 */
class ValidadorRutinaTest {
    private val casos: JsonArray = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("validador-casos.json")!!.readText()
    ).jsonArray

    @Test fun hay_casos() = assertTrue(casos.size >= 70)

    @Test fun mismos_errores_y_avisos_que_el_validador_js() {
        val fallas = mutableListOf<String>()
        for (c in casos) {
            c as JsonObject
            val nombre = c["nombre"]!!.jsonPrimitive.content
            val r = ValidadorRutina.validarTexto(c["json"]!!.jsonPrimitive.content)
            val errores = c["errores"]!!.jsonArray.map { it.jsonPrimitive.content }
            val avisos = c["avisos"]!!.jsonArray.map { it.jsonPrimitive.content }
            if (r.ok != c["ok"]!!.jsonPrimitive.boolean || r.errores != errores || r.avisos != avisos)
                fallas.add("[$nombre]\n  esperado: $errores / $avisos\n  obtenido: ${r.errores} / ${r.avisos}")
        }
        assertEquals("Casos que no coinciden:\n" + fallas.joinToString("\n"), 0, fallas.size)
    }

    @Test fun texto_que_no_es_json() {
        val r = ValidadorRutina.validarTexto("{ esto no es json")
        assertFalse(r.ok)
        assertEquals(listOf("El archivo no es un JSON válido"), r.errores)
    }
}
