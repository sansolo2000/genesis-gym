package io.github.sansolo2000.genesisgym.qa

import io.github.sansolo2000.genesisgym.Alertas
import io.github.sansolo2000.genesisgym.AvisosMedicamentos
import io.github.sansolo2000.genesisgym.JsJson
import io.github.sansolo2000.genesisgym.Recordatorios
import io.github.sansolo2000.genesisgym.Medicamentos as M
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * QA-T18 (Medicamentos 2, 0.18.0: decisiones de Héctor del 16-oct, claude/PLAN-PRUEBAS-3.0.md §25 y §28). Lo mantiene QA.
 * Solo datos inventados: medicamentos ficticios y fechas de 2030.
 */
class QaMedicamentos2Test {
    private val SCL = ZoneId.of("America/Santiago")
    private val qa1 = M.Med("QAFENO 5 mg comprimidos", listOf("8800001"), "COMPRIMIDOS", "comprimido", null, "Sí")

    private fun en(t: String) = ZonedDateTime.of(LocalDateTime.parse(t), SCL)
    private fun veces(vararg h: String) = M.Frecuencia(M.VECES_DIA, veces = h.size, horarios = h.toList())
    private fun base(f: M.Frecuencia, inicio: String, dur: M.Duracion = M.Duracion(M.DUR_INDEFINIDA), id: String = "qa-t") =
        M.Tratamiento(id, M.HECTOR, qa1, 1.0, "comprimido", f, LocalDate.parse(inicio), dur, true)
    /** Guardado como lo hace la pantalla: [ahora] es la hora en que se toca Guardar. */
    private fun nuevo(f: M.Frecuencia, inicio: String, ahora: String, dur: M.Duracion = M.Duracion(M.DUR_INDEFINIDA), id: String = "qa-t") =
        M.guardar(base(f, inicio, dur, id), null, en(ahora).toInstant(), SCL)
    private fun dias(n: Int) = M.Duracion(M.DUR_DIAS, dias = n)
    private fun todas(t: M.Tratamiento) = M.ocurrencias(t, en("2030-01-01T00:00"), en("2030-12-31T00:00"), SCL)
    private fun reg(t: M.Tratamiento, local: LocalDateTime, estado: String, seg: Long = 60): M.Registro {
        val oc = M.Ocurrencia(local, ZonedDateTime.of(local, SCL), null)
        val r = oc.cuando.toInstant().plusSeconds(seg)
        return M.registroDesde(M.rutaRegistro(t.id, local, estado, r.toEpochMilli()), M.registro(t, oc, estado, r, null, "alerta"))!!
    }
    private fun l(s: String) = LocalDateTime.parse(s)

    /** R-M01: 3 al día × 10 días, guardado a las 14:00: 30 tomas, la primera hoy a las 15:00 y la última a las 07:00 del día 11. */
    @Test fun qa_t18a_tres_al_dia_por_diez_dias() {
        val t = nuevo(veces("07:00", "15:00", "23:00"), "2030-03-05", "2030-03-05T14:00", dias(10))
        val oc = todas(t)
        assertEquals(30, oc.size)
        assertEquals(l("2030-03-05T15:00"), oc.first().local)
        assertEquals(l("2030-03-15T07:00"), oc.last().local)
        assertEquals(l("2030-03-15T07:00"), M.ultimaToma(t, SCL)!!.local)
        // No hay toma 31: después de la última no se programa nada.
        val regs = AvisosMedicamentos.porClave(oc.map { reg(t, it.local, M.CONFIRMADA) })
        assertTrue(AvisosMedicamentos.calcular(listOf(M.conHistorial(t, regs.values.flatten())), regs, en("2030-03-15T07:05")).isEmpty())
        assertNull(M.proxima(t, en("2030-03-15T07:05")))
    }

    /** R-M02: 1 al día × 10 días; una omitida y una "sin respuesta" alargan a 12 tomas; con 10 confirmadas termina. */
    @Test fun qa_t18b_omitidas_y_sin_respuesta_alargan() {
        val t = nuevo(veces("09:00"), "2030-03-05", "2030-03-05T10:00", dias(10))
        assertEquals(l("2030-03-06T09:00"), t.duracion.primera)   // hoy ya pasó: la primera es mañana
        val fechas = (6..17).map { l("2030-03-%02dT09:00".format(it)) }
        val regs = fechas.mapIndexed { i, f -> reg(t, f, when (i) { 1 -> M.OMITIDA; 2 -> M.SIN_RESPUESTA; else -> M.CONFIRMADA }) }
        val leido = M.conHistorial(t, regs)
        assertEquals(2, leido.duracion.extra)
        assertEquals(fechas, todas(leido).map { it.local })
        assertEquals(10, regs.count { it.estado == M.CONFIRMADA })
        assertTrue(leido.vencido(LocalDate.parse("2030-03-18")))
        assertTrue(AvisosMedicamentos.calcular(listOf(leido), AvisosMedicamentos.porClave(regs), en("2030-03-17T09:05")).isEmpty())
    }

    /** R-M02b: días de calendario desde la primera toma: cada 2 días × 10 = 5 tomas; L-M-V × 10 días = las de esos 10 días. */
    @Test fun qa_t18c_cada_n_dias_y_dias_de_la_semana() {
        val cada2 = nuevo(M.Frecuencia(M.INTERVALO_DIAS, intervaloDias = 2, horarios = listOf("09:00")), "2030-03-04", "2030-03-04T08:00", dias(10))
        assertEquals((listOf(4, 6, 8, 10, 12)).map { l("2030-03-%02dT09:00".format(it)) }, todas(cada2).map { it.local })
        // 2030-03-04 es lunes: L-M-V del 4 al 13 (10 días de calendario).
        assertEquals(java.time.DayOfWeek.MONDAY, LocalDate.parse("2030-03-04").dayOfWeek)
        val lmv = nuevo(M.Frecuencia(M.DIAS_SEMANA, dias = listOf(1, 3, 5), horarios = listOf("09:00")), "2030-03-04", "2030-03-04T08:00", dias(10))
        assertEquals((listOf(4, 6, 8, 11, 13)).map { l("2030-03-%02dT09:00".format(it)) }, todas(lmv).map { it.local })
    }

    /** R-M02c: la "fecha de término" sigue siendo una fecha (inclusive), sin contar tomas ni primera toma. */
    @Test fun qa_t18d_fecha_de_termino_sigue_igual() {
        val t = nuevo(veces("09:00", "21:00"), "2030-03-05", "2030-03-05T12:00", M.Duracion(M.DUR_FECHA, fin = LocalDate.parse("2030-03-07")))
        assertNull(t.duracion.primera)
        assertEquals(listOf("2030-03-05T09:00", "2030-03-05T21:00", "2030-03-06T09:00", "2030-03-06T21:00", "2030-03-07T09:00", "2030-03-07T21:00"), todas(t).map { it.local.toString() })
        assertNull(M.ultimaToma(t, SCL))
        assertEquals(LocalDate.parse("2030-03-07"), t.fin)
    }

    /** R-M03: cada 8 h × 5 días desde las 22:00 = 15 tomas en hora de reloj (O-39), también al cruzar el cambio de hora. */
    @Test fun qa_t18e_cada_ocho_horas_cruzando_el_cambio_de_hora() {
        val t = nuevo(M.Frecuencia(M.INTERVALO_HORAS, intervaloHoras = 8, horarios = listOf("22:00")), "2030-04-04", "2030-04-04T21:00", dias(5))
        val oc = todas(t)
        assertEquals(15, oc.size)
        assertEquals(l("2030-04-04T22:00"), oc.first().local)
        assertEquals(l("2030-04-09T14:00"), oc.last().local)
        assertEquals(setOf(22, 6, 14), oc.map { it.local.hour }.toSet())
        // Chile vuelve a la hora de invierno la madrugada del 7 de abril de 2030: un intervalo dura 9 h reales.
        val reales = oc.zipWithNext { a, b -> Duration.between(a.cuando.toInstant(), b.cuando.toInstant()).toHours() }
        assertEquals(1, reales.count { it == 9L }); assertEquals(13, reales.count { it == 8L })
    }

    /**
     * R-M04 (D-21): editar a mitad del tratamiento y cambiar la hora no pierde ni duplica tomas del total. Con la regla del
     * Coordinador (17-oct) también da 10: el término sigue el 14 y la toma del día 9 ya se confirmó.
     * 1 al día × 10 días a las 09:00; el día 5, a las 12:00 (ya tomada la de las 09:00), se pasa a las 20:00.
     */
    @Test fun qa_t18f_editar_la_hora_a_mitad_no_cambia_el_total() {
        val v1 = nuevo(veces("09:00"), "2030-03-05", "2030-03-05T08:00", dias(10))
        val tomadas = (5..9).map { reg(v1, l("2030-03-%02dT09:00".format(it)), M.CONFIRMADA) }
        val v2 = M.guardar(M.conHistorial(v1, tomadas).copy(frecuencia = veces("20:00")), v1, en("2030-03-09T12:00").toInstant(), SCL)
        val leido = M.conHistorial(v2, tomadas)
        val quedan = M.ocurrencias(leido, en("2030-03-09T12:00"), en("2030-12-31T00:00"), SCL)
        assertEquals(10, tomadas.size + quedan.size)
    }

    /** Igual que qa_t18f, pero pasando a una hora más temprana (20:00 → 09:00) después de la toma del día. */
    @Test fun qa_t18g_editar_a_una_hora_mas_temprana() {
        val v1 = nuevo(veces("20:00"), "2030-03-05", "2030-03-05T08:00", dias(10))
        val tomadas = (5..8).map { reg(v1, l("2030-03-%02dT20:00".format(it)), M.CONFIRMADA) }
        val v2 = M.guardar(M.conHistorial(v1, tomadas).copy(frecuencia = veces("09:00")), v1, en("2030-03-09T12:00").toInstant(), SCL)
        val quedan = M.ocurrencias(M.conHistorial(v2, tomadas), en("2030-03-09T12:00"), en("2030-12-31T00:00"), SCL)
        assertEquals(10, tomadas.size + quedan.size)
    }

    /** R-M05: Terminar, con duración y con tomas pendientes hoy, corta desde ahora y no se reabre al editar. */
    @Test fun qa_t18h_terminar_con_duracion() {
        val t = nuevo(veces("07:00", "15:00", "23:00"), "2030-03-05", "2030-03-05T06:00", dias(10))
        val fin = M.terminar(t, en("2030-03-05T16:00").toInstant(), SCL)
        assertTrue(fin.vencido(LocalDate.parse("2030-03-05")))
        assertEquals(listOf("2030-03-05T07:00", "2030-03-05T15:00"), todas(fin).map { it.local.toString() })
        assertNull(M.proxima(fin, en("2030-03-05T16:01")))
        assertTrue(AvisosMedicamentos.calcular(listOf(fin), emptyMap(), en("2030-03-05T22:00")).isEmpty())
        val editado = M.guardar(fin.copy(cantidad = 2.0, terminadoEn = null), fin, en("2030-03-05T17:00").toInstant(), SCL)
        assertTrue(editado.vencido(LocalDate.parse("2030-03-05")))
        assertTrue(todas(editado).none { it.local > l("2030-03-05T16:00") })
    }

    /**
     * R-M05b (Héctor, 17-oct): Terminar con una toma sonando la calla en ese momento, igual que Suspender.
     * Coordinador (17-oct, provisorio): esa toma queda "no tomada: tratamiento terminado", distinta de "sin respuesta", y no alarga.
     */
    @Test fun qa_t18q_terminar_calla_la_toma_que_suena() {
        val t = nuevo(veces("07:00", "15:00", "23:00"), "2030-03-05", "2030-03-05T06:00", dias(10))
        assertEquals(1, M.cadenasAbiertas(t, en("2030-03-05T15:04"), emptyMap()).size)
        // Como la pantalla: primero el cierre de la toma que suena y después Terminar.
        val cierre = M.cierreAlTerminar(t, en("2030-03-05T15:05"), emptyMap()).map { (ruta, doc) -> M.registroDesde(ruta, doc)!! }
        assertEquals(listOf(l("2030-03-05T15:00")), cierre.map { it.programada })
        assertTrue("no es sin respuesta ni omitida", cierre.none { it.estado == M.SIN_RESPUESTA || it.estado == M.OMITIDA || it.estado == M.CONFIRMADA })
        val fin = M.terminar(t, en("2030-03-05T15:05").toInstant(), SCL)
        assertEquals(0, M.conHistorial(fin, cierre).duracion.extra)   // no alarga
        val regs = AvisosMedicamentos.porClave(cierre)
        assertTrue(M.cadenasAbiertas(fin, en("2030-03-05T15:06"), regs).isEmpty())
        assertTrue(AvisosMedicamentos.calcular(listOf(fin), regs, en("2030-03-05T15:06")).isEmpty())
    }

    /**
     * Coordinador (17-oct, provisorio): al cambiar la frecuencia a mitad de un tratamiento por días, el término por calendario no
     * cambia y el total es lo confirmado + lo que falta con la frecuencia nueva. QA lo lee así para el día de la edición: cuenta
     * solo con las tomas nuevas que faltan para completar las tomas por día nuevas (las ya confirmadas hoy se descuentan).
     * 1 al día × 10 días a las 09:00 (del 5 al 14); el día 9, a las 12:00 y ya tomada la de las 09:00, pasa a 09:00 y 21:00.
     */
    @Test fun qa_t18r_cambiar_la_frecuencia_a_mitad() {
        val v1 = nuevo(veces("09:00"), "2030-03-05", "2030-03-05T08:00", dias(10))
        val tomadas = (5..9).map { reg(v1, l("2030-03-%02dT09:00".format(it)), M.CONFIRMADA) }
        val v2 = M.guardar(M.conHistorial(v1, tomadas).copy(frecuencia = veces("09:00", "21:00")), v1, en("2030-03-09T12:00").toInstant(), SCL)
        val quedan = M.ocurrencias(M.conHistorial(v2, tomadas), en("2030-03-09T12:00"), en("2030-12-31T00:00"), SCL).map { it.local }
        val esperado = listOf(l("2030-03-09T21:00")) + (10..14).flatMap { listOf(l("2030-03-%02dT09:00".format(it)), l("2030-03-%02dT21:00".format(it))) }
        assertEquals(esperado, quedan)
        assertEquals(16, tomadas.size + quedan.size)
        assertEquals(LocalDate.parse("2030-03-14"), M.conHistorial(v2, tomadas).fin)
    }

    /** R-M06: Suspender y Reanudar: en la pausa no suena ni queda "sin respuesta"; las de la pausa se pierden y el término no cambia. */
    @Test fun qa_t18i_suspender_y_reanudar_con_duracion() {
        val t = nuevo(veces("09:00", "21:00"), "2030-03-05", "2030-03-05T08:00", dias(5))
        val ultima = M.ultimaToma(t, SCL)!!.local
        val s = M.suspender(t, en("2030-03-06T08:00").toInstant())
        assertTrue(AvisosMedicamentos.calcular(listOf(s), emptyMap(), en("2030-03-06T08:30")).isEmpty())
        assertTrue(M.cadenasAbiertas(s, en("2030-03-06T21:30"), emptyMap()).isEmpty())
        val r = M.reanudar(s, en("2030-03-07T10:00").toInstant())
        assertTrue(M.cerradasSinRespuesta(r, en("2030-03-07T12:00"), emptySet()).none { it.local in l("2030-03-06T08:00")..l("2030-03-07T10:00") })
        assertEquals(l("2030-03-07T21:00"), AvisosMedicamentos.calcular(listOf(r), emptyMap(), en("2030-03-07T10:00")).first().local)
        assertEquals(ultima, M.ultimaToma(M.conHistorial(r, emptyList()), SCL)!!.local)
        // El respaldo conserva la suspensión.
        val vuelta = M.tratamientoDesdeJson(Json.parseToJsonElement(JsJson.stringify(M.tratamientoAJson(s))).jsonObject)!!
        assertTrue(vuelta.suspendido); assertFalse(vuelta.avisa)
    }

    /**
     * R-M06b: suspender con una toma sonando la calla y no la deja "sin respuesta". La toma de las 09:00 suena desde las 09:00;
     * se suspende a las 09:05 y se reanuda a las 09:30, dentro de sus 2 horas.
     */
    @Test fun qa_t18j_suspender_con_una_toma_sonando() {
        val t = nuevo(veces("09:00"), "2030-03-05", "2030-03-01T12:00")
        assertEquals(1, M.cadenasAbiertas(t, en("2030-03-05T09:04"), emptyMap()).size)
        val s = M.suspender(t, en("2030-03-05T09:05").toInstant())
        assertTrue(M.cadenasAbiertas(s, en("2030-03-05T09:06"), emptyMap()).isEmpty())
        val r = M.reanudar(s, en("2030-03-05T09:30").toInstant())
        assertTrue("al reanudar no vuelve a sonar", M.cadenasAbiertas(r, en("2030-03-05T09:31"), emptyMap()).isEmpty())
        assertTrue("no queda sin respuesta", M.cerradasSinRespuesta(r, en("2030-03-05T11:30"), emptySet()).none { it.local == l("2030-03-05T09:00") })
    }

    /** R-M14 y R-M15 (O-41): la toma que suena se mueve a la hora nueva: una sola cadena y un solo "sin respuesta", a las 10:00. */
    @Test fun qa_t18k_cambiar_la_hora_mientras_suena() {
        val v1 = nuevo(veces("09:00"), "2030-03-05", "2030-03-01T12:00")
        val v2 = M.guardar(v1.copy(frecuencia = veces("10:00")), v1, en("2030-03-05T09:05").toInstant(), SCL)
        assertTrue(M.cadenasAbiertas(v2, en("2030-03-05T09:30"), emptyMap()).isEmpty())
        assertEquals(listOf(l("2030-03-05T10:00")), M.cadenasAbiertas(v2, en("2030-03-05T10:30"), emptyMap()).map { it.first.local })
        assertEquals(listOf(l("2030-03-05T10:00")), M.cerradasSinRespuesta(v2, en("2030-03-05T12:01"), emptySet()).map { it.local })
        // El registro de esa toma copia la versión nueva (la de las 10:00).
        assertEquals(2, M.versionDe(v2, en("2030-03-05T10:00").toInstant()).version)
        // Solo la dosis, misma hora: sigue la cadena de las 09:00 (D-15, qa_t17l).
        val v3 = M.guardar(v1.copy(cantidad = 2.0), v1, en("2030-03-05T09:05").toInstant(), SCL)
        assertEquals(listOf(l("2030-03-05T09:00")), M.cadenasAbiertas(v3, en("2030-03-05T09:30"), emptyMap()).map { it.first.local })
    }

    /** R-M10 a R-M12 (O-40): archivar quita las alertas y conserva el historial; borrar solo sin historial. */
    @Test fun qa_t18l_archivar_y_borrar() {
        val t = nuevo(veces("09:00"), "2030-03-05", "2030-03-01T12:00")
        val r = reg(t, l("2030-03-05T09:00"), M.CONFIRMADA)
        assertTrue(M.puedeBorrar(t, emptyList())); assertFalse(M.puedeBorrar(t, listOf(r)))
        val a = t.copy(archivado = true)
        assertTrue(AvisosMedicamentos.calcular(listOf(a), emptyMap(), en("2030-03-05T09:30")).isEmpty())   // también la que sonaba
        assertTrue(M.cerradasSinRespuesta(a, en("2030-03-06T12:00"), emptySet()).isEmpty())
        val vuelta = M.tratamientoDesdeJson(Json.parseToJsonElement(JsJson.stringify(M.tratamientoAJson(a))).jsonObject)!!
        assertTrue(vuelta.archivado)
        // Editar un archivado no lo desarchiva.
        assertTrue(M.guardar(a.copy(cantidad = 2.0, archivado = false), a, en("2030-03-06T12:00").toInstant(), SCL).archivado)
    }

    /** R-M20: un tratamiento guardado por la 0.17.0 (sin los campos nuevos) se lee con valores por defecto y se escribe igual. */
    @Test fun qa_t18m_documento_de_la_0170() {
        val viejo = base(veces("09:00"), "2030-03-05", dias(7)).copy(version = 1, vigenteDesde = "2030-03-01T12:00:00Z", creadoEn = "2030-03-01T12:00:00Z")
        val json = M.tratamientoAJson(viejo)
        assertEquals(setOf("tipo", "id", "persona", "medicamento", "dosis", "frecuencia", "inicio", "duracion", "fin", "alertas", "version", "vigente_desde", "creado_en"), json.keys)
        assertEquals(setOf("tipo", "dias", "fin"), (json["duracion"] as JsonObject).keys)
        val leido = M.tratamientoDesdeJson(Json.parseToJsonElement(JsJson.stringify(json)).jsonObject)!!
        assertFalse(leido.archivado); assertNull(leido.terminadoEn); assertTrue(leido.suspensiones.isEmpty()); assertNull(leido.duracion.primera)
        assertTrue(leido.avisa)
        // Sin primera toma guardada sigue contando días desde el inicio, como en la 0.17.0.
        assertEquals(LocalDate.parse("2030-03-11"), leido.fin)
        assertEquals(7, todas(leido).size)
        assertEquals(json, M.tratamientoAJson(leido))
    }

    /** R-M20 y R-M04: editar solo la dosis de un tratamiento de la 0.17.0 con "cantidad de días" no mueve su término. */
    @Test fun qa_t18p_editar_un_tratamiento_de_la_0170_no_alarga() {
        val viejo = base(veces("09:00"), "2030-03-05", dias(10)).copy(version = 1, vigenteDesde = "2030-03-01T12:00:00Z", creadoEn = "2030-03-01T12:00:00Z")
        assertEquals(LocalDate.parse("2030-03-14"), viejo.fin)
        val editado = M.guardar(viejo.copy(cantidad = 2.0), viejo, en("2030-03-08T12:00").toInstant(), SCL)
        assertEquals(LocalDate.parse("2030-03-14"), editado.fin)
    }

    /** R-M07b y D-18: cada alerta suena sus 2 minutos; entre las que no llegaron a su tope, suena la de mayor prioridad. */
    @Test fun qa_t18n_cada_alerta_sus_dos_minutos() {
        val med = Alertas.MEDICAMENTO; val rep = Recordatorios.REPORTE
        val tope = Alertas.TOPE_MS
        // Dos medicamentos: uno que se repite desde 0 y otro nuevo a los 110 s. El nuevo suena hasta los 230 s.
        val dos = listOf(Alertas.Activa(med, 0L), Alertas.Activa(med, 110_000L))
        for (s in listOf(0L, 60_000L, 119_999L, 120_000L, 229_999L)) assertEquals("$s", med, Alertas.queSuena(dos, s))
        assertNull(Alertas.queSuena(dos, 230_000L))
        // Si se confirma el primero (sale de la lista), el otro sigue (O-38).
        assertEquals(med, Alertas.queSuena(dos.drop(1), 150_000L))
        // El reporte llega 1 s antes del tope del medicamento: suena después, hasta sus propios 2 min.
        val mezcla = listOf(Alertas.Activa(med, 0L), Alertas.Activa(rep, tope - 1_000L))
        assertEquals(med, Alertas.queSuena(mezcla, tope - 1L))
        assertEquals(rep, Alertas.queSuena(mezcla, tope))
        assertEquals(rep, Alertas.queSuena(mezcla, 2 * tope - 1_001L))
        assertNull(Alertas.queSuena(mezcla, 2 * tope - 1_000L))
        assertEquals(tope, Alertas.proximoTope(mezcla, 0L)); assertEquals(2 * tope - 1_000L, Alertas.proximoTope(mezcla, tope))
    }

    /** O-35: "Detener" no alcanza a una alerta que llegó hace menos de 3 s; sí a la que llegó hace 3 s o más. */
    @Test fun qa_t18o_detener_y_la_que_acaba_de_llegar() {
        assertFalse(Alertas.detenerAlcanza(100_000L, 102_999L))
        assertTrue(Alertas.detenerAlcanza(100_000L, 103_000L))
        assertEquals(3_000L, Alertas.GRACIA_MS)
    }
}
