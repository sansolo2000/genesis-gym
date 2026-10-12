package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Módulo Apoyo psicológico (contrato v2), con el programa inventado `prueba-apoyo-programa.json`. Solo datos inventados. */
class ApoyoTest {
    private val zona = ZoneId.of("America/Santiago")
    private fun texto() = javaClass.classLoader!!.getResource("prueba-apoyo-programa.json")!!.readText()
    private fun programa(): JsonObject = Json.parseToJsonElement(texto()).jsonObject
    private fun en(f: String, h: Int, m: Int = 0): ZonedDateTime = ZonedDateTime.of(java.time.LocalDate.parse(f), java.time.LocalTime.of(h, m), zona)
    private fun cambiar(o: JsonObject, k: String, v: kotlinx.serialization.json.JsonElement?) = JsonObject(LinkedHashMap(o).also { if (v == null) it.remove(k) else it[k] = v })

    private val todas = EstadoApoyo.Comidas5(Comidas.TIPOS.associateWith { "indicado" }, "manual")
    private val cuatro = EstadoApoyo.Comidas5(Comidas.TIPOS.associateWith { if (it == "cena") "no-comi" else "otro" }, "manual")
    private fun resp(energia: Int = 3, hambre: Int = 3, animo: Int = 3, agua: Boolean = true, alarma: Boolean = false, extra: Map<String, Any?> = emptyMap()) =
        mapOf("energia" to energia, "hambre" to hambre, "animo" to animo, "agua" to agua, "alarma" to alarma) + extra

    private fun guardar(e: EstadoApoyo, f: String, r: Map<String, Any?> = resp(), c: EstadoApoyo.Comidas5 = todas, ahora: ZonedDateTime = en(f, 21)): JsonObject {
        val (doc, err) = e.armarReporte(f, r, c, e.entrenoPrecargado(f, emptyList()), ahora)
        assertTrue(err.toString(), err.isEmpty())
        e.guardar(f, doc!!)
        return doc
    }

    // ---------- validación del programa ----------
    @Test fun programa_de_prueba_es_valido_y_avisa_la_pregunta_desconocida() {
        val r = Apoyo.validarPrograma(programa())
        assertTrue(r.errores.toString(), r.ok)
        assertEquals(1, r.avisos.size)
        assertTrue(r.avisos[0].contains("pasos_extra"))
    }

    @Test fun programa_con_errores_se_rechaza_completo() {
        var p = programa()
        p = cambiar(p, "version_formato", JsonPrimitive("2.0"))
        p = cambiar(p, "horario", Apoyo.objeto("reporte" to JsonPrimitive("9:00"), "plazo_dia_siguiente" to JsonPrimitive("12:00")))
        val preg = (p["preguntas"] as JsonArray).filter { (it as JsonObject)["id"] != JsonPrimitive("agua") }
        p = cambiar(p, "preguntas", JsonArray(preg))
        p = cambiar(p, "textos", JsonObject((p["textos"] as JsonObject).filterKeys { it != "alerta_hambre" }))
        val r = Apoyo.validarPrograma(p)
        assertFalse(r.ok)
        assertTrue(r.errores.any { it.startsWith("version_formato") })
        assertTrue(r.errores.any { it.startsWith("horario.reporte") })
        assertTrue(r.errores.any { it.contains("falta \"agua\"") })
        assertTrue(r.errores.any { it.contains("alerta_hambre") })
    }

    @Test fun escala_sin_etiqueta_y_estado_de_comida_invalido() {
        var p = programa()
        val preg = (p["preguntas"] as JsonArray).map { q -> q as JsonObject
            if (q["id"] == JsonPrimitive("energia")) cambiar(q, "etiquetas", Apoyo.objeto("1" to JsonPrimitive("x"))) else q }
        p = cambiar(p, "preguntas", JsonArray(preg))
        p = cambiar(p, "comidas", cambiar(p["comidas"] as JsonObject, "cuenta_como_hecha", JsonArray(listOf(JsonPrimitive("comi")))))
        val r = Apoyo.validarPrograma(p)
        assertTrue(r.errores.any { it.contains("etiqueta del valor 2") })
        assertTrue(r.errores.any { it.contains("\"comi\"") })
    }

    @Test fun mismo_programa_ya_activo() {
        val (_, r) = Apoyo.revisarTexto(texto(), programa())
        assertTrue(r.yaActivo); assertFalse(r.ok)
    }

    // ---------- reporte ----------
    @Test fun reporte_con_formato_del_contrato() {
        val e = EstadoApoyo(programa())
        val r = guardar(e, "2026-10-05", resp(extra = mapOf("palabra" to "tranquilo", "sueno_h" to 7.5, "pasos_extra" to 1200.0)))
        assertEquals("reporte-diario", r["formato"]?.let { (it as JsonPrimitive).content })
        assertEquals(JsonPrimitive("prueba"), r["perfil"])
        assertEquals(JsonPrimitive(1), r["version"])
        assertEquals(JsonPrimitive(false), r["tardio"])
        assertEquals("2026-10-05T21:00:00-03:00", (r["registrado_en"] as JsonPrimitive).content)
        assertEquals(JsonPrimitive(7.5), r["sueno_h"])
        assertEquals(JsonPrimitive(5), (r["comidas"] as JsonObject)["hechas"])
        assertEquals(JsonPrimitive("fuerza"), (r["entrenamiento"] as JsonObject)["planificado"])   // lunes
        assertEquals(JsonPrimitive(1200), (r["extra"] as JsonObject)["pasos_extra"])
        val calc = r["calculado"] as JsonObject
        assertEquals(JsonPrimitive(true), calc["dia_minimo"]); assertEquals(JsonPrimitive(1), calc["racha"])
        // Orden de campos del contrato §6
        assertEquals(listOf("formato", "version_formato", "perfil", "fecha", "registrado_en", "version", "tardio", "programa", "energia", "hambre", "animo",
            "palabra", "sueno_h", "agua", "alarma", "comidas", "entrenamiento", "novedad", "nota", "calculado", "reto_vigente", "extra"), r.keys.toList())
    }

    @Test fun validaciones_al_guardar() {
        val e = EstadoApoyo(programa())
        val ent = e.entrenoPrecargado("2026-10-05", emptyList())
        val (d1, err1) = e.armarReporte("2026-10-05", mapOf("energia" to 3), todas, ent, en("2026-10-05", 21))
        assertNull(d1); assertTrue(err1.any { it.contains("Hambre") }); assertTrue(err1.any { it.contains("Agua") })
        val (_, err2) = e.armarReporte("2026-10-05", resp(energia = 6, extra = mapOf("sueno_h" to 7.3, "palabra" to "demasiado largo")), todas, ent, en("2026-10-05", 21))
        assertEquals(3, err2.size)
        val (_, err3) = e.armarReporte("2026-10-06", resp(), todas, ent, en("2026-10-05", 21))
        assertTrue(err3.any { it.contains("futuro") })
    }

    @Test fun racha_dia_minimo_y_otro_cuenta_como_hecha() {
        val e = EstadoApoyo(programa())
        guardar(e, "2026-10-03"); guardar(e, "2026-10-04")
        val r = guardar(e, "2026-10-05", c = EstadoApoyo.Comidas5(Comidas.TIPOS.associateWith { "otro" }, "manual"))
        assertEquals(JsonPrimitive(3), (r["calculado"] as JsonObject)["racha"])
        val sinAgua = guardar(e, "2026-10-06", resp(agua = false))
        assertEquals(JsonPrimitive(false), (sinAgua["calculado"] as JsonObject)["dia_minimo"])
        assertEquals(3, e.mejorRacha())
        val noComi = guardar(e, "2026-10-07", c = cuatro)
        assertEquals(JsonPrimitive(4), (noComi["comidas"] as JsonObject)["hechas"])
        assertEquals(JsonPrimitive(false), (noComi["calculado"] as JsonObject)["dia_minimo"])
    }

    @Test fun un_dia_sin_reporte_corta_la_racha_solo_al_vencer_su_plazo() {
        val e = EstadoApoyo(programa())
        guardar(e, "2026-10-03"); guardar(e, "2026-10-04")
        // Lunes 5 sin reporte. El martes 6 a las 11:59 el plazo del lunes sigue abierto: la racha sigue en 2.
        assertEquals(2, e.rachaActual(en("2026-10-06", 11, 59)))
        assertFalse(e.ayerFuera("2026-10-06", en("2026-10-06", 11, 59)))
        // A las 12:01 venció: racha 0, ayer fuera y reinicio.
        assertEquals(0, e.rachaActual(en("2026-10-06", 12, 1)))
        assertTrue(e.ayerFuera("2026-10-06", en("2026-10-06", 12, 1)))
        assertTrue(e.rachaReinicio("2026-10-06", en("2026-10-06", 12, 1)))
        assertEquals(2, e.mejorRacha())
    }

    @Test fun hoy_cuenta_apenas_se_guarda() {
        val e = EstadoApoyo(programa())
        guardar(e, "2026-10-04")
        assertEquals(1, e.rachaActual(en("2026-10-05", 20)))
        guardar(e, "2026-10-05")
        assertEquals(2, e.rachaActual(en("2026-10-05", 21, 5)))
    }

    @Test fun reporte_tardio_se_guarda_pero_no_suma_y_ya_no_cambia() {
        val e = EstadoApoyo(programa())
        guardar(e, "2026-10-04")
        val r = guardar(e, "2026-10-05", ahora = en("2026-10-06", 12, 30))
        assertEquals(JsonPrimitive(true), r["tardio"])
        assertEquals(JsonPrimitive(false), (r["calculado"] as JsonObject)["dia_minimo"])
        assertEquals("info" to "Tardío (prueba).", e.mensajesAlGuardar(r).first())
        val (d, err) = e.armarReporte("2026-10-05", resp(), todas, e.entrenoPrecargado("2026-10-05", emptyList()), en("2026-10-06", 13))
        assertNull(d); assertTrue(err[0].contains("venció"))
    }

    @Test fun editar_antes_del_plazo_sube_la_version() {
        val e = EstadoApoyo(programa())
        guardar(e, "2026-10-05")
        val r2 = guardar(e, "2026-10-05", resp(animo = 4), ahora = en("2026-10-06", 9))
        assertEquals(JsonPrimitive(2), r2["version"]); assertEquals(JsonPrimitive(false), r2["tardio"])
    }

    @Test fun alerta_con_tres_dias_seguidos_y_un_dia_sin_reporte_interrumpe() {
        val e = EstadoApoyo(programa())
        guardar(e, "2026-10-03", resp(animo = 2)); guardar(e, "2026-10-04", resp(animo = 1))
        val r = guardar(e, "2026-10-05", resp(animo = 2, hambre = 4))
        assertEquals(JsonArray(listOf(JsonPrimitive("animo-bajo"))), (r["calculado"] as JsonObject)["alertas_activas"])
        assertTrue(e.mensajesAlGuardar(r).contains("alerta" to "Alerta ánimo (prueba)."))
        // 7 sin reporte: el 8 no hereda la secuencia.
        val r8 = guardar(e, "2026-10-08", resp(animo = 1))
        assertEquals(JsonArray(emptyList()), (r8["calculado"] as JsonObject)["alertas_activas"])
    }

    @Test fun alarma_se_muestra_en_el_momento_y_primero() {
        val e = EstadoApoyo(programa())
        val r = guardar(e, "2026-10-05", resp(alarma = true))
        assertEquals(JsonPrimitive(true), (r["calculado"] as JsonObject)["alarma_mostrada"])
        assertEquals("alarma" to "Alarma física (prueba).", e.mensajesAlGuardar(r)[0])
        assertEquals("ok" to "Mínimo. Racha: 1.", e.mensajesAlGuardar(r)[1])
    }

    @Test fun precarga_de_entrenamiento_desde_sesiones_o_plan() {
        val e = EstadoApoyo(programa())
        val ses = Apoyo.objeto("fecha" to JsonPrimitive("2026-10-07"), "sesion_id" to JsonPrimitive("a"), "sesion_nombre" to JsonPrimitive("Sesión A"),
            "rpe_sesion" to JsonPrimitive(6), "duracion_min" to JsonPrimitive(62))
        val x = e.entrenoPrecargado("2026-10-07", listOf(ses))
        assertEquals(EstadoApoyo.Entreno("fuerza", "fuerza", 62, 6, "Sesión A", "app"), x)
        assertEquals(EstadoApoyo.Entreno("mtb", "mtb", null, null, null, "manual"), e.entrenoPrecargado("2026-10-04", listOf(ses)))
    }

    @Test fun comidas_marcadas_despues_del_reporte_generan_version_nueva_hasta_el_plazo() {
        val prog = Json.parseToJsonElement(javaClass.classLoader!!.getResource("prueba-comidas-escenario.json")!!.readText()).jsonObject["programa"]!!.jsonObject
        val com = EstadoComidas(prog)
        val f = "2026-10-05"
        for (t in Comidas.TIPOS.dropLast(1)) com.marcar(f, t, "indicado", "x")
        val e = EstadoApoyo(programa())
        val c = e.comidasDesdeApp(com, f)!!
        assertEquals("app", c.origen); assertNull(c.detalle["cena"])
        val r1 = guardar(e, f, c = c, ahora = en(f, 20))
        assertEquals(JsonPrimitive(false), (r1["calculado"] as JsonObject)["dia_minimo"])
        com.marcar(f, "cena", "indicado", "x")
        val d = e.sincronizarComidas(f, com, en(f, 21))
        assertNotNull(d)
        assertEquals(JsonPrimitive(2), d!!.data["version"])
        assertEquals(JsonPrimitive(true), (d.data["calculado"] as JsonObject)["dia_minimo"])
        assertNull(e.sincronizarComidas(f, com, en(f, 21, 30)))   // nada cambió
        com.marcar(f, "cena", "no-comi", "x")
        assertNull(e.sincronizarComidas(f, com, en("2026-10-06", 12, 1)))   // venció el plazo
    }

    @Test fun envio_primero_sin_sufijo_y_correccion_con_vN() {
        val e = EstadoApoyo(programa())
        guardar(e, "2026-10-05")
        assertEquals(listOf("reporte" to "2026-10-05"), e.pendientes())
        assertEquals("reporte-prueba-2026-10-05.json", e.nombreArchivo("reporte", "2026-10-05"))
        assertFalse(e.textoEnvio("reporte", "2026-10-05").contains("enviado"))
        e.marcarEnviado("reporte", "2026-10-05", "id1", "t")
        assertTrue(e.pendientes().isEmpty())
        guardar(e, "2026-10-05", resp(animo = 5), ahora = en("2026-10-05", 22))
        assertEquals("reporte-prueba-2026-10-05-v2.json", e.nombreArchivo("reporte", "2026-10-05"))
    }

    @Test fun resumen_semanal() {
        val e = EstadoApoyo(programa())
        val semana = (0..6).map { Apoyo.dia("2026-10-03", it.toLong()) }
        guardar(e, semana[0], resp(energia = 2, extra = mapOf("novedad" to "Ruta nueva (prueba)")))
        guardar(e, semana[1], resp(energia = 4, alarma = true))
        guardar(e, semana[2], c = cuatro)
        // 6 y 7 sin reporte
        guardar(e, semana[5], resp(hambre = 4)); guardar(e, semana[6], resp(hambre = 5))
        assertFalse(e.puedeResumir("2026-10-08"))
        assertTrue(e.puedeResumir("2026-10-09"))
        val (sinBarrera, err) = e.armarResumen("2026-10-09", EstadoApoyo.Cierre(null, null, null, null, ""), en("2026-10-09", 21, 30))
        assertNull(sinBarrera); assertEquals(2, err.size)
        val (r, _) = e.armarResumen("2026-10-09", EstadoApoyo.Cierre(null, "cansancio", " ", "Cocinar el domingo", "Caminar 10 minutos"), en("2026-10-09", 21, 30))
        r!!
        assertEquals(JsonPrimitive("2026-10-03"), r["desde"])
        assertEquals(JsonPrimitive(true), r["parcial"]); assertEquals(JsonPrimitive(false), r["tardio"])
        assertEquals(JsonPrimitive(5), r["dias_reportados"]); assertEquals(JsonPrimitive(4), r["dias_minimos"])
        assertEquals(Apoyo.objeto("hechas" to JsonPrimitive(24), "de" to JsonPrimitive(35), "no_comi" to JsonPrimitive(1), "sin_marcar" to JsonPrimitive(10)), r["comidas"])
        assertEquals(JsonPrimitive(3), (r["promedios"] as JsonObject)["energia"])   // (2+4+3+3+3)/5
        assertEquals(Apoyo.objeto("energia_le2" to JsonPrimitive(1), "animo_le2" to JsonPrimitive(0), "hambre_ge4" to JsonPrimitive(2)), r["dias_bajos"])
        assertEquals(JsonArray(listOf(JsonPrimitive("2026-10-04"))), r["alarmas"])
        assertEquals(Apoyo.objeto("texto" to Apoyo.txt(null), "cumplido" to JsonPrimitive("sin-reto")), r["reto"])
        assertEquals(Apoyo.objeto("actual" to JsonPrimitive(2), "mejor" to JsonPrimitive(2)), r["racha"])
        e.guardarResumen("2026-10-09", r)
        // La semana siguiente el reto vigente es el que se escribió.
        assertEquals("Caminar 10 minutos", e.retoVigente("2026-10-10"))
        val r10 = guardar(e, "2026-10-10")
        assertEquals(JsonPrimitive("Caminar 10 minutos"), r10["reto_vigente"])
        // Resumen después del sábado a las 12:00: tardío.
        val (t, _) = e.armarResumen("2026-10-09", EstadoApoyo.Cierre(null, "otro", null, null, "Otro reto"), en("2026-10-10", 12, 30))
        assertEquals(JsonPrimitive(true), t!!["tardio"]); assertEquals(JsonPrimitive(2), t["version"])
    }

    @Test fun fechas_de_semana() {
        assertEquals("2026-10-10", Apoyo.sabadoDe("2026-10-12"))
        assertEquals("2026-10-10", Apoyo.sabadoDe("2026-10-10"))
        assertEquals("2026-10-16", Apoyo.viernesDe("2026-10-11"))
        assertEquals("lunes", Apoyo.diaPlan("2026-10-12"))
        assertEquals("Racha 3, mejor 5 {x}", Apoyo.rellenar("Racha {racha}, mejor {mejor_racha} {x}", 3, 5))
    }

    @Test fun no_avisa_el_reporte_ya_guardado_y_usa_el_texto_del_programa() {
        val cfg = Recordatorios.Config(reporte = true, horaReporte = "21:00")
        val ahora = en("2026-10-05", 8)
        val r = Recordatorios.calcular(cfg, null, emptyList(), emptySet(), ahora, dias = 2, reportados = setOf("2026-10-05"), textoReporte = "Texto (prueba)")
        assertEquals(1, r.size)
        assertEquals("2026-10-06", r[0].cuando.toLocalDate().toString())
        assertEquals("Texto (prueba)", r[0].texto); assertEquals("reporte", r[0].vista)
    }

    @Test fun respaldo_acepta_las_colecciones_del_modulo() {
        val docs = listOf(Respaldo.Doc("programas_apoyo/activo", Apoyo.objeto("x" to JsonPrimitive(1))),
            Respaldo.Doc("reportes_apoyo/2026-10-05", Apoyo.objeto("x" to JsonPrimitive(1))),
            Respaldo.Doc("resumenes_apoyo/2026-10-09", Apoyo.objeto("x" to JsonPrimitive(1))))
        assertTrue(Respaldo.leer(Respaldo.crear(docs, null, "t", "t")) is Respaldo.Resultado.Ok)
    }
}
