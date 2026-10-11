package io.github.sansolo2000.genesisgym.qa

import io.github.sansolo2000.genesisgym.Recordatorios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** QA-T04 (defecto D-01, claude/PLAN-PRUEBAS-3.0.md §5 y §6). Lo mantiene QA. */
class QaRecordatoriosTest {
    private val zona = ZoneId.of("America/Santiago")
    private val sesiones = listOf(Triple("lunes", "qa-a", "QA A"), Triple("miercoles", "qa-b", "QA B"), Triple("viernes", "qa-c", "QA C"))
    private val cfg = Recordatorios.Config(entrenamiento = true, horaEntrenamiento = "18:30")
    private val miercoles = ZonedDateTime.of(2026, 10, 14, 8, 0, 0, 0, zona)

    @Test fun qa_t04b_sin_registros_avisa_la_sesion_del_dia() {
        val r = Recordatorios.calcular(cfg, null, sesiones, emptySet(), miercoles, dias = 1)
        assertEquals(listOf("QA B"), r.map { it.texto })
    }

    @Ignore("D-01: QA quita este @Ignore cuando Desarrollador 3 suba la corrección (no avisar si cualquier sesión del día está registrada)")
    @Test fun qa_t04_sin_aviso_si_otra_sesion_del_dia_esta_registrada() {
        val r = Recordatorios.calcular(cfg, null, sesiones, setOf("2026-10-14_qa-a"), miercoles, dias = 1)
        assertTrue(r.toString(), r.isEmpty())
    }
}
