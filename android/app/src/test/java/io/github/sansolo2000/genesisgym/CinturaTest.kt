package io.github.sansolo2000.genesisgym

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** M-07: registro de cintura y CSV semanal (formato del Evaluador #5). Solo datos inventados. */
class CinturaTest {
    private val hoy = LocalDate.parse("2030-10-18")
    private fun armar(t: List<String>, solo: Boolean = false, n: String = "2", fecha: String = "2030-10-18", hora: String = "07:05", nota: String = "") =
        Cintura.armar(fecha, hora, t, solo, n, true, nota, hoy)

    @Test fun leeComaOPunto() {
        assertEquals(70.5, Cintura.leerCm("70,5")!!, 0.0); assertEquals(70.5, Cintura.leerCm(" 70.5 ")!!, 0.0)
        assertNull(Cintura.leerCm("70,55")); assertNull(Cintura.leerCm("abc")); assertNull(Cintura.leerCm(""))
    }

    @Test fun terceraSoloSiDifierenMasDeUnCm() {
        assertFalse(Cintura.pideTercera(70.0, 71.0)); assertTrue(Cintura.pideTercera(70.0, 71.1)); assertFalse(Cintura.pideTercera(67.1, 66.1))
    }

    @Test fun promedioDeDos() {
        val (r, e) = armar(listOf("70,0", "70,5"))
        assertTrue(e.toString(), e.isEmpty()); assertEquals(70.25, r!!.cm, 0.0); assertEquals(2, r.n)
    }

    @Test fun conDiferenciaGrandeExigeYUsaLaTercera() {
        val (r1, e1) = armar(listOf("70,0", "71,5", ""))
        assertNull(r1); assertTrue(e1.single().contains("3.ª"))
        val (r2, e2) = armar(listOf("70,0", "71,5", "70,6"))
        assertTrue(e2.toString(), e2.isEmpty()); assertEquals(3, r2!!.n); assertEquals(70.6, r2.cm, 0.0)   // Evaluador #7: mediana
    }

    @Test fun tresMedicionesUsaLaMedianaSinImportarElOrden() {   // Evaluador #7
        assertEquals(70.4, Cintura.valor(listOf(72.0, 70.0, 70.4)), 0.0)
        assertEquals(70.4, Cintura.valor(listOf(70.4, 72.0, 70.0)), 0.0)
        assertEquals(70.25, Cintura.valor(listOf(70.0, 70.5)), 0.0)   // con 2 sigue el promedio, 2 decimales
    }

    @Test fun datoAnteriorSoloPromedio() {
        val (r, e) = armar(listOf("70,0"), solo = true, n = "2", fecha = "2030-10-15")
        assertTrue(e.toString(), e.isEmpty()); assertEquals(70.0, r!!.cm, 0.0); assertEquals(2, r.n); assertTrue(r.mediciones.isEmpty())
    }

    @Test fun datoAnteriorSinHora() {   // O-13 (QA #24)
        val (r, e) = armar(listOf("71,5"), solo = true, fecha = "2030-10-09", hora = "")
        assertTrue(e.toString(), e.isEmpty()); assertEquals("cintura/2030-10-09_sin-hora", Cintura.ruta(r!!))
        assertTrue(Cintura.csv(listOf(r)).contains("2030-10-09;;71,5;2;;;;si;\r\n"))
        assertTrue(armar(listOf("70", "70"), hora = "").second.any { it.startsWith("Hora") })   // con mediciones, la hora sigue obligatoria
    }

    @Test fun errores() {
        assertTrue(armar(listOf("70", "70"), fecha = "2030-10-19").second.any { it.contains("futura") })
        assertTrue(armar(listOf("70", "70"), hora = "7:05").second.any { it.startsWith("Hora") })
        assertTrue(armar(listOf("12", "70")).second.any { it.contains("entre") })
        assertTrue(armar(listOf("70"), solo = true, n = "5").second.any { it.startsWith("Mediciones") })
    }

    @Test fun idaYVueltaJson() {
        val r = armar(listOf("70,0", "71,5", "70,6"), nota = "después del café").first!!
        assertEquals(r, Cintura.desdeJson(Cintura.aJson(r, "2030-10-18T10:05:00.000Z")))
        assertEquals("cintura/2030-10-18_0705", Cintura.ruta(r))
    }

    @Test fun csvSemanal() {
        val a = armar(listOf("70,0", "70,5")).first!!
        val b = armar(listOf("70,0"), solo = true, fecha = "2030-10-15", hora = "06:30", nota = "con; punto y coma").first!!
        val fuera = armar(listOf("72", "72"), fecha = "2030-10-11").first!!
        val semana = Cintura.deSemana(listOf(a, fuera, b), LocalDate.parse("2030-10-12"))
        assertEquals(listOf(b, a), semana)
        val t = Cintura.csv(semana)
        assertTrue(t.startsWith("﻿fecha;hora;cintura_cm;n_mediciones;medicion_1_cm;medicion_2_cm;medicion_3_cm;ayunas;nota\r\n"))
        assertTrue(t.contains("2030-10-15;06:30;70,0;2;;;;si;\"con; punto y coma\"\r\n"))
        assertTrue(t.contains("2030-10-18;07:05;70,25;2;70,0;70,5;;si;\r\n"))
        assertEquals("cintura-hector-2030-10-12-2030-10-18.csv", Cintura.nombre("hector", LocalDate.parse("2030-10-12"), 1))
        assertEquals("cintura-hector-2030-10-12-2030-10-18-v2.csv", Cintura.nombre("hector", LocalDate.parse("2030-10-12"), 2))
    }
}
