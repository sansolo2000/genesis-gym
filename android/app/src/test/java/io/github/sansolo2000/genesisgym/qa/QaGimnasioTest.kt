package io.github.sansolo2000.genesisgym.qa

import io.github.sansolo2000.genesisgym.Gimnasio
import io.github.sansolo2000.genesisgym.Gimnasio.arr
import io.github.sansolo2000.genesisgym.Gimnasio.txt
import io.github.sansolo2000.genesisgym.ValidadorRutina
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA-T07 y QA-T08 (claude/PLAN-PRUEBAS-3.0.md §6). Lo mantiene QA.
 * qa/qa-forma-v3.json es una rutina inventada con la forma de la v3; los errores esperados los dio validador-rutina.js.
 */
class QaGimnasioTest {
    private fun recurso(n: String) = javaClass.classLoader!!.getResource(n)!!.readText()

    @Test fun qa_t07_forma_de_la_v3_es_valida_y_cada_rotura_da_su_error() {
        val texto = recurso("qa/qa-forma-v3.json")
        assertEquals(ValidadorRutina.Resultado(true, emptyList(), emptyList()), ValidadorRutina.validarTexto(texto))

        val sinIndicacion = texto.replaceFirst("\"carga_kg\": null, \"carga_indicacion\": \"suave\"", "\"carga_kg\": null")
        assertTrue(sinIndicacion != texto)
        assertEquals(listOf("sesiones[0] (qa-a).ejercicios[0].series[0]: si \"carga_kg\" es null, \"carga_indicacion\" es obligatoria (p. ej. \"peso corporal\")"),
            ValidadorRutina.validarTexto(sinIndicacion).errores)

        val diaRepetido = texto.replaceFirst("\"dia_semana\": \"miercoles\"", "\"dia_semana\": \"lunes\"")
        assertTrue(diaRepetido != texto)
        val r = ValidadorRutina.validarTexto(diaRepetido)
        assertEquals(listOf("sesiones[1] (qa-b).dia_semana: \"lunes\" tiene más de una sesión"), r.errores)
        assertEquals(listOf("No hay sesión para todos los días de fuerza del plan (lunes, miércoles y viernes)"), r.avisos)

        val rango = texto.replaceFirst("\"reps_min\": 10", "\"reps_min\": 16")
        assertTrue(rango != texto)
        assertEquals(listOf("sesiones[0] (qa-a).ejercicios[1].series[1]: \"reps_min\" y \"reps_max\" enteros (1–100) con reps_min < reps_max"),
            ValidadorRutina.validarTexto(rango).errores)
    }

    /** Esta semana la sesión A (del lunes) se registra el miércoles 14 con "Registrar otra sesión hoy". */
    @Test fun qa_t08_sesion_a_registrada_un_miercoles_sale_asi_en_el_csv() {
        val rutina = Json.parseToJsonElement(recurso("qa/qa-forma-v3.json")).jsonObject
        val a = rutina.arr("sesiones").first { it.txt("id") == "qa-a" }
        val b = Gimnasio.nuevoBorrador("2026-10-14", a, rutina, "2026-10-14T12:00:00.000Z")
        assertEquals("miercoles", b.txt("dia_semana"))
        assertEquals("qa-a", b.txt("sesion_id"))
        val filas = Gimnasio.csv(listOf(b)).split("\r\n")
        assertEquals(Gimnasio.COLUMNAS.joinToString(";"), filas[0])
        assertEquals(5, filas.size)   // encabezado + 4 series de la sesión A
        for (f in filas.drop(1)) assertTrue(f, f.startsWith("2026-10-14;miercoles;qa-a;QA A;qa-forma-v3;3;en_curso;"))
    }
}
