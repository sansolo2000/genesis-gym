package io.github.sansolo2000.genesisgym

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.11.2 — D-03 y D-04: el aviso de fin de descanso solo se cancela de forma explícita. */
class DescansoTest {
    private val llamadas = mutableListOf<Long?>()
    private val reloj = RelojDescanso { fin, _ -> llamadas.add(fin) }
    private val t0 = 1_000_000L

    @Test fun iniciarProgramaElAvisoEnElFin() {
        reloj.iniciar(t0, 120, 1, 0, "2026-10-14_B", "Serie 2")
        assertEquals(listOf<Long?>(t0 + 120_000), llamadas)
        assertEquals(t0 + 120_000, reloj.fin)
        assertEquals(120, reloj.restanteSeg(t0))
        assertEquals(1 to 0, reloj.de)
    }

    @Test fun abrirLaMismaSesionNoCancelaElAviso() {
        reloj.iniciar(t0, 120, 0, 0, "2026-10-14_B", "x")
        reloj.alAbrirSesion("2026-10-14_B", t0 + 30_000)
        assertEquals(1, llamadas.size)            // solo la programación inicial
        assertTrue(reloj.activo(t0 + 30_000))
    }

    @Test fun abrirOtraSesionCancelaElAviso() {
        reloj.iniciar(t0, 120, 0, 0, "2026-10-14_B", "x")
        reloj.alAbrirSesion("2026-10-16_C", t0 + 30_000)
        assertEquals(listOf<Long?>(t0 + 120_000, null), llamadas)
        assertNull(reloj.fin)
    }

    @Test fun descansoVencidoSeLimpiaSinTocarLaAlarma() {
        reloj.iniciar(t0, 60, 0, 0, "2026-10-14_B", "x")
        reloj.alAbrirSesion("2026-10-16_C", t0 + 61_000)
        assertEquals(1, llamadas.size)
        assertNull(reloj.fin)
    }

    @Test fun llegarACeroNoCancelaLaAlarma() {
        reloj.iniciar(t0, 60, 0, 0, "s", "x")
        reloj.vencer()
        assertEquals(1, llamadas.size)
        assertNull(reloj.fin)
        assertFalse(reloj.activo(t0))
    }

    @Test fun terminarCancelaUnaSolaVez() {
        reloj.iniciar(t0, 60, 0, 0, "s", "x")
        reloj.cancelar(); reloj.cancelar()
        assertEquals(listOf<Long?>(t0 + 60_000, null), llamadas)
    }

    @Test fun masTreintaReprogramaElAviso() {
        reloj.iniciar(t0, 90, 0, 0, "s", "x")
        reloj.mas30("x")
        assertEquals(listOf<Long?>(t0 + 90_000, t0 + 120_000), llamadas)
        assertEquals(120, reloj.total)
    }

    @Test fun masTreintaSinDescansoNoHaceNada() {
        reloj.mas30("x")
        assertTrue(llamadas.isEmpty())
    }
}
