package io.github.sansolo2000.genesisgym

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** E6 — CSV de Health Connect para el Evaluador. Solo datos inventados. */
class SaludCsvTest {
    private val z = ZoneId.of("America/Santiago")
    private fun t(d: String, h: Int, m: Int = 0): Instant = ZonedDateTime.of(LocalDate.parse(d), java.time.LocalTime.of(h, m), z).toInstant()
    private val sab = LocalDate.parse("2026-10-17")
    private val g = "com.garmin.android.apps.connectmobile"

    @Test fun semana_de_sabado_a_viernes() {
        assertEquals(LocalDate.parse("2026-10-17"), SaludCsv.sabadoDe(LocalDate.parse("2026-10-21")))
        assertEquals(LocalDate.parse("2026-10-10"), SaludCsv.semanaPorDefecto(LocalDate.parse("2026-10-19")))   // lunes: la semana cerrada
        assertEquals(LocalDate.parse("2026-10-17"), SaludCsv.semanaPorDefecto(LocalDate.parse("2026-10-23")))   // viernes: la en curso
    }

    @Test fun noche_con_etapas_y_sin_etapas() {
        val e = listOf(SaludCsv.Etapa(t("2026-10-17", 23), t("2026-10-18", 1), SaludCsv.LIGERO),
            SaludCsv.Etapa(t("2026-10-18", 1), t("2026-10-18", 2), SaludCsv.PROFUNDO),
            SaludCsv.Etapa(t("2026-10-18", 2), t("2026-10-18", 2, 15), SaludCsv.DESPIERTO),
            SaludCsv.Etapa(t("2026-10-18", 2, 15), t("2026-10-18", 3), SaludCsv.REM))
        val n = SaludCsv.noche(SaludCsv.Sueno(t("2026-10-17", 23), t("2026-10-18", 3), e, g))
        assertEquals(SaludCsv.Noche(225, 60, 45, 120, 15), n)
        assertEquals(SaludCsv.Noche(420, null, null, null, null), SaludCsv.noche(SaludCsv.Sueno(t("2026-10-17", 23), t("2026-10-18", 6), emptyList(), g)))
    }

    @Test fun diario_siete_filas_y_vacio_si_falta() {
        val fc = listOf(SaludCsv.Fc(t("2026-10-18", 8), 58, g), SaludCsv.Fc(t("2026-10-18", 20), 57, g))
        val su = listOf(SaludCsv.Sueno(t("2026-10-17", 23), t("2026-10-18", 6), emptyList(), g))
        val pe = listOf(SaludCsv.Peso(t("2026-10-21", 6, 30), 55.45, "com.moving.movinglife"), SaludCsv.Peso(t("2026-10-21", 21), 56.1, "com.moving.movinglife"))
        val f = SaludCsv.diario(sab, fc, su, pe, z)
        assertEquals(7, f.size)
        assertEquals(57L, f[1].fc)                       // el último del día
        assertEquals(420L, f[1].noche?.total)            // asignado al día en que despierta
        assertEquals("Garmin Connect", f[1].origen)
        assertEquals(55.45, f[4].peso!!.kg, 0.0)         // el primero del día (en ayunas)
        assertNull(f[0].fc); assertNull(f[0].noche)
        val csv = SaludCsv.csvDiario(f, z)
        assertTrue(csv.startsWith("﻿fecha;fc_reposo_lpm;"))
        val lineas = csv.removePrefix("﻿").split("\r\n")
        assertEquals(9, lineas.size)                     // cabecera + 7 + final vacío
        assertEquals("2026-10-17;;;;;;;;;", lineas[1])
        assertEquals("2026-10-18;57;420;;;;;;;Garmin Connect", lineas[2])
        assertEquals("2026-10-21;;;;;;;55,45;06:30;movinglife", lineas[5])
    }

    @Test fun actividades_solo_de_la_semana() {
        val a = listOf(
            SaludCsv.Actividad(t("2026-10-18", 9, 5), t("2026-10-18", 11, 35), SaludCsv.BICICLETA, null, 32456.0, 812.4, 141.6, g),
            SaludCsv.Actividad(t("2026-10-16", 9), t("2026-10-16", 10), SaludCsv.BICICLETA, null, null, null, null, g),   // semana anterior
            SaludCsv.Actividad(t("2026-10-22", 18), t("2026-10-22", 18, 30), 79, null, null, null, null, g))
        val f = SaludCsv.actividades(sab, a, z)
        assertEquals(2, f.size)
        val csv = SaludCsv.csvActividades(f).removePrefix("﻿").split("\r\n")
        assertEquals("fecha;hora_inicio;tipo;duracion_min;distancia_km;desnivel_m;fc_media_lpm;origen", csv[0])
        assertEquals("2026-10-18;09:05;MTB;150;32,46;812;142;Garmin Connect", csv[1])
        assertEquals("2026-10-22;18:00;otro;30;;;;Garmin Connect", csv[2])
    }

    @Test fun nombres_de_archivo() {
        assertEquals("hc-hector-diario-2026-10-17-2026-10-23.csv", SaludCsv.nombre(SaludCsv.slug("Héctor"), "diario", sab, 1))
        assertEquals("hc-hector-actividades-2026-10-17-2026-10-23-v2.csv", SaludCsv.nombre("hector", "actividades", sab, 2))
        assertEquals("10", SaludCsv.numero(10.0, 2)); assertEquals("", SaludCsv.numero(null, 2))
    }
}
