package io.github.sansolo2000.genesisgym.qa

import io.github.sansolo2000.genesisgym.Gimnasio
import io.github.sansolo2000.genesisgym.JsJson
import io.github.sansolo2000.genesisgym.Plan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA-T13 (claude/PLAN-PRUEBAS-3.0.md §6). Lo mantiene QA.
 * Importador del plan Génesis: casos que PlanTest (Desarrollador 3) no cubre.
 * Solo datos inventados: qa/qa-forma-v3.json y los programas de ejemplo de src/test/resources.
 */
class QaPlanTest {
    private fun recurso(n: String) = javaClass.classLoader!!.getResource(n)!!.readText()
    private fun obj(n: String): JsonObject = Json.parseToJsonElement(recurso(n)).jsonObject
    private val ahora = "2026-10-13T12:00:00.000Z"

    private val rutina = obj("qa/qa-forma-v3.json")
    private val alimentacion = obj("prueba-programa-con-compras.json")
    private val apoyo = obj("prueba-apoyo-programa.json")

    /** config/rutina_activa tal como la guarda la app al activar [r]. */
    private fun rutinaActiva(r: JsonObject): JsonObject = Gimnasio.documentosActivacion(r, ahora).first().data

    private fun con(o: JsonObject, k: String, v: kotlinx.serialization.json.JsonElement) = JsonObject(o + (k to v))

    private fun sobre(version: String = "1.0", perfil: String? = "prueba", ru: JsonObject = rutina, al: JsonObject = alimentacion, ap: JsonObject = apoyo) =
        JsJson.stringify(buildJsonObject {
            put("tipo", "plan-genesis"); put("version_formato", version)
            if (perfil != null) put("perfil", perfil)
            put("generado_en", "2026-10-13T08:00:00-03:00")
            put("rutina", ru); put("alimentacion", al); put("apoyo", ap)
        })

    @Test fun qa_t13a_version_formato_distinta_se_rechaza_sin_revisar_partes() {
        val r = Plan.revisar(sobre(version = "2.0"), null, null, null, "Prueba", ahora)
        assertFalse(r.ok)
        assertTrue(r.errores.toString(), r.errores.any { it.startsWith("version_formato") })
        assertTrue(r.partes.isEmpty())
    }

    @Test fun qa_t13b_sin_perfil_se_rechaza() {
        val r = Plan.revisar(sobre(perfil = null), null, null, null, "Prueba", ahora)
        assertFalse(r.ok)
        assertTrue(r.errores.toString(), r.errores.any { it.startsWith("perfil") })
    }

    @Test fun qa_t13c_mismo_programa_id_con_contenido_distinto_reemplaza_sin_duplicar() {
        val prog = alimentacion["programa"]!!.jsonObject
        val nueva = con(alimentacion, "programa", con(prog, "nombre", JsonPrimitive("Programa de ejemplo (ficticio) — corregido")))
        val r = Plan.revisar(sobre(al = nueva), rutinaActiva(rutina), alimentacion, apoyo, "Prueba", ahora)
        assertTrue(r.errores.toString(), r.ok)
        assertEquals(mapOf("rutina" to "igual", "alimentacion" to "nueva", "apoyo" to "igual"), r.partes.associate { it.clave to it.estado })
        val parte = r.nuevas.single()
        // Mismas rutas que la importación por separado: el programa activo y su id se reemplazan, no se agrega otro.
        assertEquals(listOf("alimentacion/programa_activo", "programas_alimentacion/programa-ejemplo"), parte.docs.map { it.ruta })
    }

    @Test fun qa_t13d_dos_partes_con_error_no_importa_nada_y_lista_ambas() {
        val rutinaMala = con(rutina, "formato", JsonPrimitive("otro"))
        val apoyoMalo = con(apoyo, "formato", JsonPrimitive("otro"))
        val r = Plan.revisar(sobre(ru = rutinaMala, ap = apoyoMalo), null, null, null, "Prueba", ahora)
        assertFalse(r.ok)
        val estados = r.partes.associate { it.clave to it.estado }
        assertEquals("error", estados["rutina"]); assertEquals("error", estados["apoyo"])
        assertTrue(r.partes.filter { it.estado == "error" }.all { it.errores.isNotEmpty() && it.docs.isEmpty() })
    }

    @Test fun qa_t13e_archivo_con_bom_se_acepta() {
        val r = Plan.revisar("﻿" + sobre(), null, null, null, "Prueba", ahora)
        assertTrue(r.errores.toString(), r.ok)
        assertEquals(3, r.nuevas.size)
    }
}
