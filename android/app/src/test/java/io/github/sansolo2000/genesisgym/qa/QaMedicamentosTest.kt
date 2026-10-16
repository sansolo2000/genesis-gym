package io.github.sansolo2000.genesisgym.qa

import io.github.sansolo2000.genesisgym.Alertas
import io.github.sansolo2000.genesisgym.AvisosMedicamentos
import io.github.sansolo2000.genesisgym.JsJson
import io.github.sansolo2000.genesisgym.Recordatorios
import io.github.sansolo2000.genesisgym.Respaldo
import io.github.sansolo2000.genesisgym.Medicamentos as M
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * QA-T17 (módulo Medicamentos, claude/Vademecum.md §12 y claude/PLAN-PRUEBAS-3.0.md §6). Lo mantiene QA.
 * Solo datos inventados: ni el catálogo real ni medicamentos de nadie. Fechas de 2030.
 */
class QaMedicamentosTest {
    private val SCL = ZoneId.of("America/Santiago")
    private val MAD = ZoneId.of("Europe/Madrid")
    private val archivo = listOf(File("src/main/assets/medicamentos/config-medicamentos.json"), File("android/app/src/main/assets/medicamentos/config-medicamentos.json")).first { it.isFile }
    private val cfg: M.Config = M.configDeArchivo(Json.parseToJsonElement(archivo.readText()).jsonObject).first!!

    private val qa1 = M.Med("QAFENO 5 mg comprimidos", listOf("8800001"), "COMPRIMIDOS", "comprimido", null, "Sí")
    private val qa2 = M.Med("QAFENO 5 mg comprimidos", listOf("8800002", "8800003"), "COMPRIMIDOS", "comprimido", null, "Sí")
    private val entera = M.Med("CÁPSULA QA ENTERA", listOf("8800010"), "CÁPSULAS", "cápsula", null, "No")

    private fun en(t: String, z: ZoneId = SCL) = ZonedDateTime.of(LocalDateTime.parse(t), z)
    private fun veces(vararg h: String) = M.Frecuencia(M.VECES_DIA, veces = h.size, horarios = h.toList())
    private fun trat(f: M.Frecuencia, persona: String = M.HECTOR, med: M.Med = qa1, dur: M.Duracion = M.Duracion(M.DUR_INDEFINIDA),
                     alertas: Boolean = true, id: String = "qa-t") =
        M.guardar(M.Tratamiento(id, persona, med, 1.0, "comprimido", f, LocalDate.parse("2030-03-04"), dur, alertas), null, Instant.parse("2030-03-01T12:00:00Z"))
    private fun ms(t: String, z: ZoneId = SCL) = en(t, z).toInstant().toEpochMilli()
    private fun registro(t: M.Tratamiento, local: String, estado: String, respondida: String, pos: Int? = null): M.Registro {
        val oc = M.Ocurrencia(LocalDateTime.parse(local), en(local), null)
        val r = en(respondida).toInstant()
        return M.registroDesde(M.rutaRegistro(t.id, oc.local, estado, r.toEpochMilli()), M.registro(t, oc, estado, r, pos, "alerta"))!!
    }

    /** §12: tratamientos distintos para Héctor y Majo, cada uno con sus alertas. */
    @Test fun qa_t17a_hector_y_majo_cada_uno_con_sus_tomas() {
        val h = trat(veces("09:00"), id = "qa-h"); val m = trat(veces("10:00"), persona = M.MAJO, id = "qa-m")
        assertTrue(M.validar(h, cfg).isEmpty()); assertTrue(M.validar(m, cfg).isEmpty())
        val p = AvisosMedicamentos.calcular(listOf(h, m), emptyMap(), en("2030-03-05T08:00"))
        assertEquals(listOf("qa-h" to "2030-03-05T09:00", "qa-m" to "2030-03-05T10:00"), p.take(2).map { it.tratamientoId to it.local.toString() })
        assertFalse(M.validar(h.copy(persona = ""), cfg).isEmpty())   // la persona es obligatoria
    }

    /** §12: coincidencias parciales, sin mayúsculas ni tildes, por id, y sin devolver todo el catálogo. */
    @Test fun qa_t17b_buscador_parcial_y_acotado() {
        val muchos = (1..5000).map { M.Med("QA GENERICO $it mg", listOf("77${"%05d".format(it)}")) }
        val cat = M.Catalogo(listOf(qa1, qa2, entera) + muchos)
        assertEquals(2, cat.buscar("qafe").first.size)
        assertEquals(2, cat.buscar("Fenó").first.size)
        assertEquals("CÁPSULA QA ENTERA", cat.buscar("capsula entera").first.single().nombre)
        assertEquals("CÁPSULA QA ENTERA", cat.buscar("8800010").first.single().nombre)
        val (res, total) = cat.buscar("generico", 100)
        assertEquals(100, res.size); assertEquals(5000, total)
        assertEquals(setOf(listOf("8800001"), listOf("8800002", "8800003")), cat.buscar("qafeno").first.map { it.ids }.toSet())
    }

    /** §12: la dosis rechaza cantidades inválidas; fraccionable solo en cuartos y mitades; no fraccionable solo enteros. */
    @Test fun qa_t17c_dosis() {
        for (malo in listOf(null, 0.0, -0.5)) assertNotNull("$malo", M.validarDosis(cfg, malo, "comprimido", "Sí"))
        for (ok in listOf(0.25, 0.5, 0.75, 1.0, 2.5)) assertNull("$ok", M.validarDosis(cfg, ok, "comprimido", "Sí"))
        for (malo in listOf(0.3, 0.1, 1.2)) assertNotNull("$malo", M.validarDosis(cfg, malo, "comprimido", "Sí"))
        assertNotNull(M.validarDosis(cfg, 0.5, "cápsula", "No")); assertNull(M.validarDosis(cfg, 2.0, "cápsula", "No"))
        assertNull(M.leerCantidad("1/2")); assertEquals(0.75, M.leerCantidad(" 0,75 ")!!, 0.0)
    }

    /** §12: "veces al día" e "intervalo de horas" no se usan juntas; los horarios sugeridos se editan. */
    @Test fun qa_t17d_modalidades_excluyentes_y_horarios_editables() {
        assertFalse(M.validarFrecuencia(M.Frecuencia(M.VECES_DIA, veces = 1, intervaloHoras = 6, horarios = listOf("09:00")), cfg).isEmpty())
        assertFalse(M.validarFrecuencia(M.Frecuencia(M.INTERVALO_HORAS, veces = 1, intervaloHoras = 6, horarios = listOf("09:00")), cfg).isEmpty())
        assertEquals(listOf("07:00", "15:00", "23:00"), M.sugerir(cfg, M.VECES_DIA, 3))
        val editada = veces("06:45", "14:10", "22:05")
        assertTrue(M.validarFrecuencia(editada, cfg).isEmpty())
        val p = AvisosMedicamentos.calcular(listOf(trat(editada)), emptyMap(), en("2030-03-05T12:00"))
        assertEquals("2030-03-05T14:10", p.first().local.toString())
    }

    /** §12: la primera alerta es el siguiente horario válido (lo que de verdad se programa, no solo lo que se muestra). */
    @Test fun qa_t17e_primera_alerta_programada() {
        val t = trat(veces("07:00", "15:00", "23:00"))
        val p = AvisosMedicamentos.calcular(listOf(t), emptyMap(), en("2030-03-05T14:00"))
        assertEquals(ms("2030-03-05T15:00"), p.first().ms)
        assertEquals(M.proxima(t, en("2030-03-05T14:00"))!!.local, p.first().local)
        // Ya pasaron todas (y la de las 23:00 cerró sus 2 h): la primera del día siguiente.
        val tarde = AvisosMedicamentos.calcular(listOf(t), emptyMap(), en("2030-03-06T01:30"))
        assertEquals("2030-03-06T07:00", tarde.first().local.toString())
    }

    /** §12: sin respuesta se repite cada 10 min hasta 2 h; confirmar u omitir cierra; posponer abre otra cadena. */
    @Test fun qa_t17f_cadena_de_alertas_programada() {
        val t = trat(veces("09:00"))
        val k = M.clave(t.id, LocalDateTime.parse("2030-03-05T09:00"))
        fun proximo(ahora: String, regs: Map<String, List<M.Registro>> = emptyMap()) =
            AvisosMedicamentos.calcular(listOf(t), regs, en(ahora)).firstOrNull { it.local.toString() == "2030-03-05T09:00" }?.ms
        assertEquals(ms("2030-03-05T09:30"), proximo("2030-03-05T09:25"))
        assertEquals(ms("2030-03-05T11:00"), proximo("2030-03-05T10:55"))     // el último aviso (10:50) lleva al cierre de las 11:00
        assertNull(proximo("2030-03-05T11:00"))                                // a las 2 h ya no hay avisos
        assertNull(proximo("2030-03-05T09:25", mapOf(k to listOf(registro(t, "2030-03-05T09:00", M.CONFIRMADA, "2030-03-05T09:05")))))
        assertNull(proximo("2030-03-05T09:25", mapOf(k to listOf(registro(t, "2030-03-05T09:00", M.OMITIDA, "2030-03-05T09:05")))))
        val pos = registro(t, "2030-03-05T09:00", M.POSTERGADA, "2030-03-05T09:05", 60)
        assertEquals(ms("2030-03-05T10:05"), proximo("2030-03-05T09:06", mapOf(k to listOf(pos))))
        assertEquals(ms("2030-03-05T10:25"), proximo("2030-03-05T10:16", mapOf(k to listOf(pos))))   // la cadena nueva también repite cada 10
        assertEquals(listOf(15, 30, 60, 90), M.POSTERGAR)
        // Cerrada sin respuesta: un solo registro (ruta fija).
        assertTrue(M.cerradasSinRespuesta(t, en("2030-03-05T11:01"), emptySet()).any { it.local.toString() == "2030-03-05T09:00" })
        assertTrue(M.cerradasSinRespuesta(t, en("2030-03-05T11:01"), setOf(k)).none { it.local.toString() == "2030-03-05T09:00" })
    }

    /** §12: los tratamientos vencidos o sin alertas no programan nada. */
    @Test fun qa_t17g_vencidos_y_apagados_no_programan() {
        val vencido = trat(veces("09:00", "21:00"), dur = M.Duracion(M.DUR_DIAS, dias = 2))   // 4 y 5 de marzo
        assertEquals(LocalDate.parse("2030-03-05"), vencido.fin)
        assertTrue(AvisosMedicamentos.calcular(listOf(vencido), emptyMap(), en("2030-03-06T08:00")).isEmpty())
        assertEquals(listOf("2030-03-05T21:00"), AvisosMedicamentos.calcular(listOf(vencido), emptyMap(), en("2030-03-05T12:00")).map { it.local.toString() })
        assertTrue(AvisosMedicamentos.calcular(listOf(trat(veces("09:00"), alertas = false)), emptyMap(), en("2030-03-05T08:00")).isEmpty())
    }

    /** §12: editar no altera el historial; el registro guarda la versión que regía. */
    @Test fun qa_t17h_editar_no_altera_el_historial() {
        val v1 = trat(veces("09:00"))
        val r = registro(v1, "2030-03-05T09:00", M.CONFIRMADA, "2030-03-05T09:02")
        val antes = JsJson.stringify(r.data)
        val v2 = M.guardar(v1.copy(cantidad = 2.0, frecuencia = veces("08:00")), v1, en("2030-03-05T12:00").toInstant())
        assertEquals(2, v2.version); assertEquals(v1.id, v2.id)
        assertEquals(antes, JsJson.stringify(r.data))
        // Las tomas futuras usan la versión nueva.
        assertEquals("2030-03-06T08:00", AvisosMedicamentos.calcular(listOf(v2), emptyMap(), en("2030-03-05T12:01")).first().local.toString())
    }

    /** §12: la toma de las 09:00 sigue a las 09:00 locales al cambiar de zona (lo que se programa). */
    @Test fun qa_t17i_zona_horaria_en_lo_programado() {
        val t = trat(veces("09:00"))
        val scl = AvisosMedicamentos.calcular(listOf(t), emptyMap(), en("2030-03-05T06:00")).first()
        val mad = AvisosMedicamentos.calcular(listOf(t), emptyMap(), en("2030-03-05T06:00", MAD)).first()
        assertEquals(ms("2030-03-05T09:00"), scl.ms)
        assertEquals(ms("2030-03-05T09:00", MAD), mad.ms)
        assertEquals(scl.local, mad.local)
    }

    /** §12: la información sale en el respaldo (exportable) y vuelve igual. */
    @Test fun qa_t17j_tratamiento_e_historial_en_el_respaldo() {
        val t = trat(M.Frecuencia(M.EVENTOS, eventos = listOf(M.Toma("desayuno", "07:00"))), persona = M.MAJO, med = qa2)
        val r = registro(t, "2030-03-05T07:00", M.POSTERGADA, "2030-03-05T07:03", 15)
        val docs = listOf(Respaldo.Doc(M.rutaTratamiento(t.id), M.tratamientoAJson(t)), Respaldo.Doc(r.ruta, r.data))
        val leido = Respaldo.leer(Respaldo.crear(docs, "Persona QA", "qa", "2030-03-05T12:00:00.000Z"))
        assertTrue(leido.toString(), leido is Respaldo.Resultado.Ok)
        val l = (leido as Respaldo.Resultado.Ok).leido
        assertEquals(Respaldo.huella(docs), Respaldo.huella(l.docs))
        assertEquals(t, M.tratamientoDesdeJson(l.docs.single { it.ruta == M.rutaTratamiento(t.id) }.data))
        assertEquals(M.POSTERGADA, M.registroDesde(r.ruta, l.docs.single { it.ruta == r.ruta }.data)!!.estado)
    }

    /** M-10 sigue igual con el tipo nuevo: tope de 2 min, Posponer 5/10/15, orden de los demás tipos y el Descanso suena una vez. */
    @Test fun qa_t17k_m10_no_cambia() {
        assertEquals(120_000L, Alertas.TOPE_MS)
        assertEquals(listOf(5, 10, 15), Alertas.POSPONER)
        assertEquals(listOf(Recordatorios.ENTRENAMIENTO, Recordatorios.COMIDA, Recordatorios.TAREA, Recordatorios.REPORTE), Alertas.PRIORIDAD.filter { it != Alertas.MEDICAMENTO })
        assertFalse(Alertas.insiste(Alertas.DESCANSO))
        assertEquals(Recordatorios.ENTRENAMIENTO, Alertas.ganador(listOf(Recordatorios.COMIDA, Recordatorios.ENTRENAMIENTO, Recordatorios.REPORTE)))
        assertTrue(Alertas.activo(Recordatorios.Config(), Alertas.MEDICAMENTO))
        // Un respaldo de Avisos anterior (sin Medicamentos) no inventa ese tipo.
        val base = Alertas.inicial(null, 70, false)
        assertNull(Alertas.desdeJson(Alertas.aJson(mapOf(Recordatorios.COMIDA to base)), base)[Alertas.MEDICAMENTO])
    }

    /**
     * D-15 (mayor): editar un tratamiento (o "Terminar hoy") mientras la toma de las 09:00 está en sus 2 horas corta la cadena:
     * no vuelve a avisar y nunca queda "sin respuesta". CONTEXTO-MODULO-MEDICAMENTOS §8 dice que sigue su cadena.
     * Se activa con la corrección.
     */
    @Ignore("D-15: se activa con la corrección")
    @Test fun qa_t17l_editar_no_corta_la_cadena_que_esta_sonando() {
        val v1 = trat(veces("09:00"))
        val v2 = M.guardar(v1.copy(cantidad = 2.0), v1, en("2030-03-05T09:05").toInstant())
        val p = AvisosMedicamentos.calcular(listOf(v2), emptyMap(), en("2030-03-05T09:06"))
        assertEquals(ms("2030-03-05T09:10"), p.firstOrNull { it.local.toString() == "2030-03-05T09:00" }?.ms)
        assertTrue(M.cerradasSinRespuesta(v2, en("2030-03-05T11:01"), emptySet()).any { it.local.toString() == "2030-03-05T09:00" })
    }
}
