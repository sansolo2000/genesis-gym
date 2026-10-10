package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Con el programa inventado de pruebas/datos-ejemplo (5 al 7 de octubre de 2026). */
class RecordatoriosTest {
    private val zona = ZoneId.of("America/Santiago")
    private fun programa(): JsonObject = Json.parseToJsonElement(javaClass.classLoader!!.getResource("prueba-comidas-escenario.json")!!.readText()).jsonObject["programa"]!!.jsonObject
    private val ahora = ZonedDateTime.of(2026, 10, 5, 6, 0, 0, 0, zona)   // lunes 5, 06:00
    private val sesiones = listOf(Triple("lunes", "a", "Sesión A"), Triple("miercoles", "b", "Sesión B"), Triple("viernes", "c", "Sesión C"))

    @Test fun todo_apagado_no_hay_avisos() {
        assertTrue(Recordatorios.calcular(Recordatorios.Config(), EstadoComidas(programa()), sesiones, emptySet(), ahora).isEmpty())
    }

    @Test fun comidas_con_minutos_antes_y_tareas() {
        val r = Recordatorios.calcular(Recordatorios.Config(comidas = true, minutosAntes = 10, tareas = true), EstadoComidas(programa()), sesiones, emptySet(), ahora)
        assertEquals(16, r.size)   // 3 días × 5 comidas + 1 tarea
        assertEquals(ZonedDateTime.of(2026, 10, 5, 6, 50, 0, 0, zona), r[0].cuando)
        assertEquals("Desayuno · 07:00", r[0].titulo)
        assertEquals("Avena con leche (ejemplo)", r[0].texto)
        val almuerzo = r.first { it.titulo.startsWith("Almuerzo") }
        assertEquals("Pollo con arroz (ejemplo) · desde el refrigerador", almuerzo.texto)
        assertEquals("Tarea de cocina · 21:00", r.first { it.categoria == Recordatorios.TAREA }.titulo)
    }

    @Test fun no_avisa_lo_ya_marcado_ni_lo_pasado_y_respeta_intercambios() {
        val est = EstadoComidas(programa())
        est.marcar("2026-10-05", "colacion_am", "indicado", "x")
        est.intercambiar("2026-10-05_cena", "2026-10-06_cena", "x")
        val tarde = ZonedDateTime.of(2026, 10, 5, 12, 0, 0, 0, zona)
        val r = Recordatorios.calcular(Recordatorios.Config(comidas = true), est, sesiones, emptySet(), tarde)
        assertEquals(listOf("Almuerzo · 13:30", "Colación PM · 17:00", "Cena · 20:30"), r.filter { it.cuando.toLocalDate().dayOfMonth == 5 }.map { it.titulo })
        assertEquals("Pollo con arroz (ejemplo)", r.first { it.titulo == "Cena · 20:30" }.texto)   // la cena del 6
    }

    @Test fun entrenamiento_solo_en_dias_de_la_rutina_y_si_no_esta_registrado() {
        val cfg = Recordatorios.Config(entrenamiento = true, horaEntrenamiento = "18:30")
        val r = Recordatorios.calcular(cfg, null, sesiones, setOf("2026-10-05_a"), ahora)
        assertEquals(listOf("2026-10-07", "2026-10-09"), r.map { it.cuando.toLocalDate().toString() })
        assertEquals("Sesión B", r[0].texto)
        assertTrue(Recordatorios.calcular(cfg.copy(horaEntrenamiento = ""), null, sesiones, emptySet(), ahora).isEmpty())
    }

    @Test fun reporte_diario_y_se_juntan_los_del_mismo_minuto() {
        val cfg = Recordatorios.Config(tareas = true, reporte = true, horaReporte = "21:00")
        val r = Recordatorios.calcular(cfg, EstadoComidas(programa()), sesiones, emptySet(), ahora)
        assertEquals(8, r.size)   // 7 reportes + 1 tarea
        val avisos = Recordatorios.juntar(r)
        assertEquals(7, avisos.size)
        assertEquals("2 avisos · 21:00", avisos[0].titulo)
        assertEquals("comidas", avisos[0].vista)
    }

    @Test fun horas_validas() {
        assertTrue(Recordatorios.horaValida("07:05") && !Recordatorios.horaValida("7:05") && !Recordatorios.horaValida("24:00"))
    }
}
