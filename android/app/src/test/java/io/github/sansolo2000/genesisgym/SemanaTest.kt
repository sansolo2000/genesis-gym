package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** M-05: cada sesión de la rutina se hace una vez por semana, de lunes a domingo. Datos inventados. */
class SemanaTest {
    private fun reg(fecha: String, id: String, estado: String = "cerrada"): JsonObject =
        buildJsonObject { put("fecha", fecha); put("sesion_id", id); put("estado", estado) }
    private fun ses(id: String, dia: String): JsonObject = buildJsonObject { put("id", id); put("dia_semana", dia) }
    private val rutina = listOf(ses("A", "lunes"), ses("B", "miercoles"), ses("C", "viernes"))

    @Test fun lunesDeLaSemana() {
        assertEquals("2026-10-12", Gimnasio.lunesDe("2026-10-12"))   // lunes
        assertEquals("2026-10-12", Gimnasio.lunesDe("2026-10-14"))   // miércoles
        assertEquals("2026-10-12", Gimnasio.lunesDe("2026-10-18"))   // domingo
        assertEquals("2026-10-19", Gimnasio.lunesDe("2026-10-19"))
    }

    @Test fun sesionesDeLaSemanaOrdenadasSinLaSemanaAnterior() {
        val todas = listOf(reg("2026-10-14", "B", "en_curso"), reg("2026-10-12", "A"), reg("2026-10-09", "C"), reg("2026-10-19", "A"))
        assertEquals(listOf("A", "B"), Gimnasio.sesionesDeSemana(todas, "2026-10-14").map { it["sesion_id"].toString().trim('"') })
    }

    @Test fun registrarOtraMuestraSoloLasQueFaltan() {
        val todas = listOf(reg("2026-10-12", "A"))
        // Miércoles: la del día (B) va en su tarjeta; A ya está; queda C.
        assertEquals(listOf("C"), Gimnasio.pendientesDeSemana(rutina, todas, "2026-10-14", "B").map { it["id"].toString().trim('"') })
    }

    @Test fun elLunesSiguienteVuelvenTodas() {
        val todas = listOf(reg("2026-10-12", "A"), reg("2026-10-14", "B"), reg("2026-10-16", "C"))
        assertEquals(listOf("B", "C"), Gimnasio.pendientesDeSemana(rutina, todas, "2026-10-19", "A").map { it["id"].toString().trim('"') })
    }

    @Test fun sesionEnCursoCuentaComoRegistrada() {
        val todas = listOf(reg("2026-10-13", "C", "en_curso"))
        assertEquals(listOf("B"), Gimnasio.pendientesDeSemana(rutina, todas, "2026-10-12", "A").map { it["id"].toString().trim('"') })
    }
}
