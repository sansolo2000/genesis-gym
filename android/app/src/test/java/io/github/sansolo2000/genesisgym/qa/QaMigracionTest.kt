package io.github.sansolo2000.genesisgym.qa

import io.github.sansolo2000.genesisgym.Gimnasio
import io.github.sansolo2000.genesisgym.Gimnasio.txt
import io.github.sansolo2000.genesisgym.Respaldo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA-T09 (claude/PLAN-PRUEBAS-3.0.md §6). Lo mantiene QA.
 * qa/qa-ejemplo-en-curso-desde-1.0.json: herramientas/migrar.js sobre una copia de pruebas/datos-ejemplo/base-1.0
 * (inventada) con la sesión del 12-oct en_curso, sin RPE ni duración y con 1 serie sin registrar (como la del 9-oct real).
 */
class QaMigracionTest {
    private fun recurso(n: String) = javaClass.classLoader!!.getResource(n)!!.readText()

    @Test fun qa_t09_sesion_en_curso_migrada_se_restaura_tal_cual() {
        val r = Respaldo.leer(recurso("qa/qa-ejemplo-en-curso-desde-1.0.json"))
        assertTrue(r.toString(), r is Respaldo.Resultado.Ok)
        val l = (r as Respaldo.Resultado.Ok).leido
        assertEquals(mapOf("config" to 1, "rutinas" to 1, "sesiones" to 2, "imagenes" to 2), l.conteos)
        val ses = l.docs.filter { it.ruta.startsWith("sesiones/") }.map { it.data }
        val enCurso = ses.single { it.txt("estado") == "en_curso" }
        assertEquals("2026-10-12", enCurso.txt("fecha"))
        assertEquals(2, Gimnasio.seriesRegistradas(enCurso))   // se migra tal cual: no se completa nada
        assertEquals(3, Gimnasio.seriesTotales(enCurso))
        assertEquals(1, Gimnasio.primerPendiente(enCurso))     // se puede retomar en el ejercicio que falta
        val filas = Gimnasio.csv(ses).split("\r\n").drop(1).filter { it.startsWith("2026-10-12;") }
        assertEquals(3, filas.size)
        for (f in filas) assertTrue(f, f.split(";")[6] == "en_curso")
    }
}
