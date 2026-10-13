package io.github.sansolo2000.genesisgym

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** M-07: registro de cintura y CSV semanal (formato del Evaluador #5). Solo datos inventados. */
class CinturaTest {
    private val hoy = LocalDate.parse("2026-10-16")
    private fun armar(t: List<String>, solo: Boolean = false, n: String = "2", fecha: String = "2026-10-16", hora: String = "07:05", nota: String = "") =
        Cintura.armar(fecha, hora, t, solo, n, true, nota, hoy)

    @Test fun leeComaOPunto() {
        assertEquals(93.5, Cintura.leerCm("93,5")!!, 0.0); assertEquals(93.5, Cintura.leerCm(" 93.5 ")!!, 0.0)
        assertNull(Cintura.leerCm("93,55")); assertNull(Cintura.leerCm("abc")); assertNull(Cintura.leerCm(""))
    }

    @Test fun terceraSoloSiDifierenMasDeUnCm() {
        assertFalse(Cintura.pideTercera(93.0, 94.0)); assertTrue(Cintura.pideTercera(93.0, 94.1)); assertFalse(Cintura.pideTercera(90.1, 89.1))
    }

    @Test fun promedioDeDos() {
        val (r, e) = armar(listOf("93,0", "93,5"))
        assertTrue(e.toString(), e.isEmpty()); assertEquals(93.25, r!!.cm, 0.0); assertEquals(2, r.n)
    }

    @Test fun conDiferenciaGrandeExigeYUsaLaTercera() {
        val (r1, e1) = armar(listOf("93,0", "94,5", ""))
        assertNull(r1); assertTrue(e1.single().contains("3.ª"))
        val (r2, e2) = armar(listOf("93,0", "94,5", "93,6"))
        assertTrue(e2.toString(), e2.isEmpty()); assertEquals(3, r2!!.n); assertEquals(93.6, r2.cm, 0.0)   // Evaluador #7: mediana
    }

    @Test fun tresMedicionesUsaLaMedianaSinImportarElOrden() {   // Evaluador #7
        assertEquals(93.4, Cintura.valor(listOf(95.0, 93.0, 93.4)), 0.0)
        assertEquals(93.4, Cintura.valor(listOf(93.4, 95.0, 93.0)), 0.0)
        assertEquals(93.25, Cintura.valor(listOf(93.0, 93.5)), 0.0)   // con 2 sigue el promedio, 2 decimales
    }

    @Test fun datoAnteriorSoloPromedio() {
        val (r, e) = armar(listOf("93,0"), solo = true, n = "2", fecha = "2026-10-13")
        assertTrue(e.toString(), e.isEmpty()); assertEquals(93.0, r!!.cm, 0.0); assertEquals(2, r.n); assertTrue(r.mediciones.isEmpty())
    }

    @Test fun datoAnteriorSinHora() {   // O-13 (QA #24)
        val (r, e) = armar(listOf("94,5"), solo = true, fecha = "2026-10-07", hora = "")
        assertTrue(e.toString(), e.isEmpty()); assertEquals("cintura/2026-10-07_sin-hora", Cintura.ruta(r!!))
        assertTrue(Cintura.csv(listOf(r)).contains("2026-10-07;;94,5;2;;;;si;\r\n"))
        assertTrue(armar(listOf("93", "93"), hora = "").second.any { it.startsWith("Hora") })   // con mediciones, la hora sigue obligatoria
    }

    @Test fun errores() {
        assertTrue(armar(listOf("93", "93"), fecha = "2026-10-17").second.any { it.contains("futura") })
        assertTrue(armar(listOf("93", "93"), hora = "7:05").second.any { it.startsWith("Hora") })
        assertTrue(armar(listOf("12", "93")).second.any { it.contains("entre") })
        assertTrue(armar(listOf("93"), solo = true, n = "5").second.any { it.startsWith("Mediciones") })
    }

    @Test fun idaYVueltaJson() {
        val r = armar(listOf("93,0", "94,5", "93,6"), nota = "después del café").first!!
        assertEquals(r, Cintura.desdeJson(Cintura.aJson(r, "2026-10-16T10:05:00.000Z")))
        assertEquals("cintura/2026-10-16_0705", Cintura.ruta(r))
    }

    @Test fun csvSemanal() {
        val a = armar(listOf("93,0", "93,5")).first!!
        val b = armar(listOf("93,0"), solo = true, fecha = "2026-10-13", hora = "06:30", nota = "con; punto y coma").first!!
        val fuera = armar(listOf("95", "95"), fecha = "2026-10-09").first!!
        val semana = Cintura.deSemana(listOf(a, fuera, b), LocalDate.parse("2026-10-10"))
        assertEquals(listOf(b, a), semana)
        val t = Cintura.csv(semana)
        assertTrue(t.startsWith("﻿fecha;hora;cintura_cm;n_mediciones;medicion_1_cm;medicion_2_cm;medicion_3_cm;ayunas;nota\r\n"))
        assertTrue(t.contains("2026-10-13;06:30;93,0;2;;;;si;\"con; punto y coma\"\r\n"))
        assertTrue(t.contains("2026-10-16;07:05;93,25;2;93,0;93,5;;si;\r\n"))
        assertEquals("cintura-hector-2026-10-10-2026-10-16.csv", Cintura.nombre("hector", LocalDate.parse("2026-10-10"), 1))
        assertEquals("cintura-hector-2026-10-10-2026-10-16-v2.csv", Cintura.nombre("hector", LocalDate.parse("2026-10-10"), 2))
    }
}
