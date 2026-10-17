package io.github.sansolo2000.genesisgym

import io.github.sansolo2000.genesisgym.Gimnasio.arr
import io.github.sansolo2000.genesisgym.Gimnasio.obj
import io.github.sansolo2000.genesisgym.Gimnasio.txt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Multiusuario, lado app (0.18.0; formatos en claude/CONTEXTO-MULTIUSUARIO-APP.md). Solo personas y datos inventados:
 * "persona-1", "persona-2" y los programas de prueba (perfil "prueba").
 */
class MultiusuarioTest {
    private fun recurso(n: String) = javaClass.classLoader!!.getResource(n)!!.readText()
    private val ahora = "2030-10-16T12:00:00.000Z"
    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun JsonObject.mas(vararg p: Pair<String, JsonElement>) = JsonObject(LinkedHashMap(this).also { m -> for ((k, v) in p) m[k] = v })
    private fun jp(s: String) = JsonPrimitive(s)

    private val rutina: JsonObject = Json.parseToJsonElement(recurso("validador-casos.json")).jsonArray
        .map { it.jsonObject }.first { it["nombre"]!!.jsonPrimitive.content == "valida" }["json"]!!.jsonPrimitive.content
        .replace("\"ejemplo-formato\"", "\"fuerza-prueba\"").let { json(it) }
    private val alimentacion = json(recurso("prueba-programa-con-compras.json"))   // perfil "prueba"
    private val apoyo = json(recurso("prueba-apoyo-programa.json"))                 // perfil "prueba"

    private val senales = buildJsonObject {
        put("pregunta", "¿Tuviste hoy alguna de estas señales?")
        put("senales", buildJsonArray { add(JsonPrimitive("señal inventada uno")); add(JsonPrimitive("señal inventada dos")) })
        put("accion", "Texto de acción inventado: avisa a tu médico.")
    }
    private fun perfil(id: String = "prueba", modulos: List<String> = Perfil.MODULOS, conSenales: Boolean = true) = buildJsonObject {
        put("id", id); put("nombre", "Persona de prueba")
        put("modulos", JsonArray(modulos.map { JsonPrimitive(it) }))
        if (conSenales) put("senales_alarma", senales)
    }
    private fun sobre(perfil: JsonElement, partes: Map<String, JsonObject> = mapOf("rutina" to rutina, "alimentacion" to alimentacion, "apoyo" to apoyo),
                      para: String? = "prueba", estado: String? = "entregado", generadoPor: String? = "Consolidador") = JsJson.stringify(buildJsonObject {
        put("tipo", Plan.TIPO); put("version_formato", "1.0"); put("perfil", perfil); put("generado_en", "2030-10-15T10:00:00-03:00")
        generadoPor?.let { put("generado_por", it) }; para?.let { put("para", it) }; estado?.let { put("estado", it) }
        for ((k, v) in partes) put(k, v)
    })

    // ---------- compatibilidad hacia atrás (§5 del diseño) ----------
    @Test fun unPlanDeAntesEsTodoComoSiempre() {
        val r = Plan.revisar(sobre(JsonPrimitive("prueba"), para = null, estado = null, generadoPor = null), null, null, null, "Prueba", ahora)
        assertTrue(r.errores.toString(), r.ok)
        assertNull(r.perfil)
        assertEquals(listOf("rutina", "alimentacion", "apoyo"), r.nuevas.map { it.clave })
        assertTrue(r.confirmar.isEmpty()); assertFalse(r.perfilCambia)
    }

    @Test fun unPerfilDeAntesTieneTodosLosModulos() {
        val d = Perfil.leer(json("""{"nombre":"Persona Uno","creado":"2030-10-01T10:00:00.000Z"}"""))
        assertEquals("persona-uno", d.id)                 // el mismo id que salía del nombre en los CSV
        assertFalse(d.conPlan); assertTrue(d.existe)
        assertEquals(Perfil.MODULOS, d.visibles)
        val nada = Perfil.leer(null)
        assertFalse(nada.existe); assertEquals("sin-perfil", nada.id); assertEquals(Perfil.MODULOS, nada.visibles)
        // Los avisos de siempre no cambian sin plan.
        val k = Recordatorios.Config(comidas = true, tareas = true, entrenamiento = true, reporte = true)
        assertEquals(k, Programador.conModulos(k, d))
    }

    @Test fun elRespaldoLeeElPerfilNuevoYLasSenalesSinColeccionesNuevas() {
        val p = Perfil.aplicar(json("""{"nombre":"Prueba","creado":"2030-10-01T10:00:00.000Z"}"""), Perfil.revisarSeccion(json(sobre(perfil()))).first!!, emptySet(), ahora)
        val docs = listOf(Respaldo.Doc(Perfil.RUTA, p), Respaldo.Doc(Perfil.rutaSenal("2030-10-16"), Perfil.registroSenal("2030-10-16", false, "prueba", ahora)))
        val r = Respaldo.leer(Respaldo.crear(docs, "Prueba", "qa", ahora))
        assertTrue(r.toString(), r is Respaldo.Resultado.Ok)
        assertEquals(Respaldo.huella(docs), Respaldo.huella((r as Respaldo.Resultado.Ok).leido.docs))
        assertEquals(2, r.leido.conteos["config"])
    }

    // ---------- sección "perfil" del plan ----------
    @Test fun unPlanConPerfilTraeSoloLasPartesDeSusModulos() {
        val r = Plan.revisar(sobre(perfil(modulos = listOf(Perfil.ENTRENAMIENTO, Perfil.MEDICAMENTOS)), mapOf("rutina" to rutina)), null, null, null, null, ahora)
        assertTrue(r.errores.toString(), r.ok)
        assertEquals(listOf("rutina"), r.nuevas.map { it.clave })
        assertEquals("prueba", r.perfil!!.id)
        assertEquals("Consolidador", r.perfil!!.meta.generadoPor)
        assertTrue(r.perfilCambia)
        assertTrue(r.avisos.toString(), r.avisos.any { it.contains("catálogo de medicamentos") })   // sin catálogo en el celular
        // Si trae partes de módulos que la persona no usa, se avisa y no se importan.
        val conDeMas = Plan.revisar(sobre(perfil(modulos = listOf(Perfil.ENTRENAMIENTO))), null, null, null, null, ahora)
        assertTrue(conDeMas.ok); assertEquals(listOf("rutina"), conDeMas.partes.map { it.clave })
        assertTrue(conDeMas.avisos.any { it.contains("alimentacion") })
        // Si falta la parte de un módulo, se rechaza.
        assertFalse(Plan.revisar(sobre(perfil(modulos = listOf(Perfil.COMIDAS)), mapOf("rutina" to rutina)), null, null, null, null, ahora).ok)
    }

    /** Seguridad: sin las señales de alarma y la acción de la persona, el plan se rechaza; nunca se usan las de otra. */
    @Test fun sinSenalesDeAlarmaElPlanSeRechaza() {
        val r = Plan.revisar(sobre(perfil(conSenales = false)), null, null, null, null, ahora)
        assertFalse(r.ok)
        assertTrue(r.errores.toString(), r.errores.any { it.startsWith("perfil.senales_alarma") })
        val sinAccion = perfil().mas("senales_alarma" to senales.mas("accion" to jp("  ")))
        assertFalse(Plan.revisar(sobre(sinAccion), null, null, null, null, ahora).ok)
        // Un perfil con plan pero sin señales guardadas no toma la pregunta de Apoyo (que es de otra persona).
        val d = Perfil.leer(json("""{"nombre":"Prueba","id":"prueba","modulos":["apoyo"]}"""))
        assertNull(Perfil.senalesVigentes(d, EstadoApoyo(apoyo)))
    }

    @Test fun metadatosDelPlan() {
        assertTrue(Plan.revisar(sobre(perfil(), para = "persona-2"), null, null, null, null, ahora).errores.any { it.startsWith("para") })
        assertTrue(Plan.revisar(sobre(perfil(), estado = "aprobado"), null, null, null, null, ahora).errores.any { it.startsWith("estado") })
        assertTrue(Plan.revisar(sobre(perfil(), generadoPor = null), null, null, null, null, ahora).errores.any { it.startsWith("generado_por") })
        assertTrue(Plan.revisar(sobre(perfil(id = "Con Mayúsculas")), null, null, null, null, ahora).errores.any { it.startsWith("perfil.id") })
        assertTrue(Plan.revisar(sobre(perfil(modulos = listOf("gimnasio"))), null, null, null, null, ahora).errores.any { it.startsWith("perfil.modulos") })
        // Un borrador se puede importar, pero solo confirmando.
        val b = Plan.revisar(sobre(perfil(), estado = "borrador"), null, null, null, null, ahora)
        assertTrue(b.ok); assertTrue(b.confirmar.single().contains("borrador"))
    }

    /** Anti-meta: un programa de otra persona no entra, aunque venga en el plan. */
    @Test fun losProgramasDebenSerDeLaMismaPersona() {
        val r = Plan.revisar(sobre(perfil(id = "persona-2"), para = "persona-2"), null, null, null, null, ahora)
        assertFalse(r.ok)
        assertTrue(r.errores.toString(), r.errores.any { it.startsWith("alimentacion") } && r.errores.any { it.startsWith("apoyo") })
    }

    @Test fun elPlanDeOtraPersonaPideConfirmacion() {
        val mio = json("""{"nombre":"Persona Uno","id":"persona-1","modulos":["entrenamiento"],"ocultos":[]}""")
        val otro = Plan.revisar(sobre(perfil()), null, null, null, "Persona Uno", ahora, null, mio)
        assertTrue(otro.ok); assertEquals(1, otro.confirmar.size)
        val mismo = Plan.revisar(sobre(perfil()), null, null, null, "Persona de prueba", ahora, null, json("""{"nombre":"Persona de prueba","id":"prueba"}"""))
        assertTrue(mismo.confirmar.isEmpty())
        // Plan de antes de otra persona: avisa (como siempre) y ahora también pide confirmar.
        val antes = Plan.revisar(sobre(JsonPrimitive("otra-persona"), para = null, estado = null, generadoPor = null), null, null, null, "Persona Uno", ahora)
        assertTrue(antes.ok); assertEquals(1, antes.avisos.size); assertEquals(1, antes.confirmar.size)
    }

    // ---------- ocultar módulos ----------
    @Test fun ocultarNoSeReactivaSinPreguntar() {
        val del = Perfil.revisarSeccion(json(sobre(perfil(modulos = listOf(Perfil.ENTRENAMIENTO, Perfil.COMIDAS))))).first!!
        var doc = Perfil.aplicar(null, del, emptySet(), ahora)
        doc = Perfil.ocultar(doc, Perfil.COMIDAS, true)
        val d = Perfil.leer(doc)
        assertFalse(d.visible(Perfil.COMIDAS)); assertTrue(d.visible(Perfil.ENTRENAMIENTO)); assertFalse(d.visible(Perfil.APOYO))
        // Un plan nuevo que trae Comidas no la vuelve a mostrar sola: se pregunta.
        assertEquals(listOf(Perfil.COMIDAS), Perfil.ocultosQueTrae(d, del))
        assertFalse(Perfil.leer(Perfil.aplicar(doc, del, emptySet(), ahora)).visible(Perfil.COMIDAS))
        assertTrue(Perfil.leer(Perfil.aplicar(doc, del, setOf(Perfil.COMIDAS), ahora)).visible(Perfil.COMIDAS))
        // Avisos: un módulo oculto no avisa aunque esté encendido en Avisos.
        val k = Programador.conModulos(Recordatorios.Config(comidas = true, tareas = true, entrenamiento = true, reporte = true), d)
        assertFalse(k.comidas); assertFalse(k.tareas); assertTrue(k.entrenamiento); assertFalse(k.reporte)
        // Sin el módulo Salud, la pestaña (y el aviso de Hoy) dice "Datos".
        assertEquals("Datos", Perfil.nombrePestanaSalud(d))
        assertEquals("Salud", Perfil.nombrePestanaSalud(Perfil.leer(null)))
        // Volver a mostrar.
        assertTrue(Perfil.leer(Perfil.ocultar(doc, Perfil.COMIDAS, false)).visible(Perfil.COMIDAS))
        // Importar lo mismo otra vez no cambia el perfil.
        assertFalse(Perfil.cambia(Perfil.aplicar(null, del, emptySet(), ahora), del))
    }

    @Test fun aplicarConservaLoDelCelular() {
        val del = Perfil.revisarSeccion(json(sobre(perfil()))).first!!
        val doc = Perfil.aplicar(json("""{"nombre":"Prueba","creado":"2030-01-01T00:00:00.000Z"}"""), del, emptySet(), ahora)
        assertEquals("2030-01-01T00:00:00.000Z", doc.txt("creado"))
        assertEquals("prueba", doc.txt("id"))
        assertEquals("entregado", doc.obj("plan")!!.txt("estado"))
        assertEquals(ahora, doc.obj("plan")!!.txt("importado_en"))
        // Cambiar el nombre a mano conserva el id y los módulos.
        val renombrado = Perfil.leer(Perfil.conNombre(doc, "Otro Nombre", ahora))
        assertEquals("prueba", renombrado.id); assertEquals(Perfil.MODULOS, renombrado.modulos)
    }

    // ---------- señales de alarma (componente común) ----------
    @Test fun senalesDeAlarma() {
        // Perfil de antes: la pregunta y la acción de Apoyo, como hoy.
        val antes = Perfil.senalesVigentes(Perfil.leer(json("""{"nombre":"Prueba"}""")), EstadoApoyo(apoyo))!!
        assertEquals("Alarma (prueba)", antes.pregunta)
        assertEquals(EstadoApoyo(apoyo).textoAlarma(), antes.accion)
        assertNull(Perfil.senalesVigentes(Perfil.leer(json("""{"nombre":"Prueba"}""")), null))   // sin Apoyo no hay de dónde sacarlas
        // Con plan: las de la persona, con cualquier combinación de módulos (aquí sin Apoyo).
        val del = Perfil.revisarSeccion(json(sobre(perfil(modulos = listOf(Perfil.MEDICAMENTOS)), emptyMap()))).first!!
        val s = Perfil.senalesVigentes(Perfil.leer(Perfil.aplicar(null, del, emptySet(), ahora)), EstadoApoyo(apoyo))!!
        assertEquals("¿Tuviste hoy alguna de estas señales?", s.pregunta); assertEquals(2, s.senales.size)
        val reg = Perfil.registroSenal("2030-10-16", true, "prueba", ahora)
        assertEquals(true, Perfil.respuestaSenal(reg)); assertNull(Perfil.respuestaSenal(null))
        assertEquals("config/senales_2030-10-16", Perfil.rutaSenal("2030-10-16"))
    }

    // ---------- Parte 3: "no está disponible" ----------
    /** FORMATO-RUTINA 1.2: las alternativas exigen "version_formato": "1.2". */
    private fun rutinaConAlternativas(alts: JsonElement, version: String = "1.2"): JsonObject {
        val ses = rutina.arr("sesiones")
        val lunes = ses[0]; val ej0 = lunes.arr("ejercicios")[0].mas("alternativas" to alts)
        val lunesNuevo = lunes.mas("ejercicios" to JsonArray(listOf(ej0) + lunes.arr("ejercicios").drop(1)))
        return rutina.mas("sesiones" to JsonArray(listOf(lunesNuevo) + ses.drop(1)), "version_formato" to jp(version))
    }

    @Test fun formatoRutina12() {
        // Con alternativas, la rutina declara 1.2; una 1.0 con alternativas se rechaza con un mensaje claro.
        val en10 = ValidadorRutina.validar(rutinaConAlternativas(buildJsonArray { add(alt("ejemplo-b")) }, "1.0"))
        assertFalse(en10.ok); assertTrue(en10.errores.toString(), en10.errores.any { it.contains("requiere \"version_formato\": \"1.2\"") })
        // Una 1.2 sin alternativas también vale; otra versión sigue con el mensaje de siempre.
        assertTrue(ValidadorRutina.validar(rutina.mas("version_formato" to jp("1.2"))).ok)
        assertEquals(listOf("raíz.version_formato: esta app acepta solo \"1.0\""), ValidadorRutina.validar(rutina.mas("version_formato" to jp("1.3"))).errores)
        // Dos alternativas iguales: error.
        val repetidas = ValidadorRutina.validar(rutinaConAlternativas(buildJsonArray { add(alt("ejemplo-b")); add(alt("ejemplo-b")) }))
        assertFalse(repetidas.ok); assertTrue(repetidas.errores.toString(), repetidas.errores.any { it.contains("repetida") })
        // Un ejercicio que solo se usa como alternativa no genera "ninguna sesión lo usa".
        val soloAlt = rutinaConAlternativas(buildJsonArray { add(alt("ejemplo-c")) })
        assertTrue(ValidadorRutina.validar(soloAlt).avisos.none { it.contains("ninguna sesión lo usa") })
    }

    @Test fun alternativaConOtroEquipoSinSeriesAvisa() {
        val ejs = rutina.arr("ejercicios").map { if (it.txt("id") == "ejemplo-b") it.mas("equipo" to jp("Mancuernas")) else it }
        val conEquipo = { alts: JsonElement -> rutinaConAlternativas(alts).mas("ejercicios" to JsonArray(ejs)) }
        val sin = ValidadorRutina.validar(conEquipo(buildJsonArray { add(alt("ejemplo-b")) }))
        assertTrue(sin.ok)   // es un aviso, no un error
        assertTrue(sin.avisos.toString(), sin.avisos.any { it.contains("usa otro equipo") })
        assertTrue(ValidadorRutina.validar(conEquipo(buildJsonArray { add(alt("ejemplo-b", true)) })).avisos.none { it.contains("usa otro equipo") })
        // Mismo equipo (el resto de la prueba usa "e"): sin aviso.
        assertTrue(ValidadorRutina.validar(rutinaConAlternativas(buildJsonArray { add(alt("ejemplo-c")) })).avisos.none { it.contains("usa otro equipo") })
    }

    @Test fun elAvisoDeLosDiasFijosSoloEnUnPerfilDeAntes() {
        val soloLunes = rutina.mas("sesiones" to JsonArray(rutina.arr("sesiones").take(1)))
        val texto = JsJson.stringify(soloLunes)
        assertTrue(Gimnasio.revisarImportacion(texto, null).resultado.avisos.contains(ValidadorRutina.AVISO_DIAS))
        assertFalse(Gimnasio.revisarImportacion(texto, null, diasFijos = false).resultado.avisos.contains(ValidadorRutina.AVISO_DIAS))
        // En el plan: con perfil nuevo no aparece; con el plan de antes sí.
        val nuevo = Plan.revisar(sobre(perfil(modulos = listOf(Perfil.ENTRENAMIENTO)), mapOf("rutina" to soloLunes)), null, null, null, null, ahora)
        assertTrue(nuevo.ok); assertFalse(nuevo.partes.single().avisos.contains(ValidadorRutina.AVISO_DIAS))
        val antes = Plan.revisar(sobre(JsonPrimitive("prueba"), mapOf("rutina" to soloLunes, "alimentacion" to alimentacion, "apoyo" to apoyo), null, null, null), null, null, null, null, ahora)
        assertTrue(antes.partes.first { it.clave == "rutina" }.avisos.contains(ValidadorRutina.AVISO_DIAS))
    }
    private fun alt(id: String, conSeries: Boolean = false) = buildJsonObject {
        put("ejercicio_id", id)
        if (conSeries) put("series", buildJsonArray { add(buildJsonObject { put("n", 1); put("tipo", "efectiva"); put("reps", 15); put("carga_kg", kotlinx.serialization.json.JsonNull); put("carga_indicacion", "peso corporal") }) })
    }

    @Test fun elValidadorAceptaAlternativas() {
        assertTrue(ValidadorRutina.validar(rutinaConAlternativas(buildJsonArray { add(alt("ejemplo-b")) })).ok)
        assertTrue(ValidadorRutina.validar(rutinaConAlternativas(buildJsonArray { add(alt("ejemplo-b", true)); add(alt("ejemplo-c")) })).ok)
        val noExiste = ValidadorRutina.validar(rutinaConAlternativas(buildJsonArray { add(alt("no-existe")) }))
        assertFalse(noExiste.ok); assertTrue(noExiste.errores.toString(), noExiste.errores.any { it.contains("alternativas[0].ejercicio_id") })
        assertFalse(ValidadorRutina.validar(rutinaConAlternativas(buildJsonArray { add(alt("ejemplo-a")) })).ok)   // el mismo ejercicio
        assertFalse(ValidadorRutina.validar(rutinaConAlternativas(buildJsonArray { add(alt("ejemplo-b")); add(alt("ejemplo-c")); add(alt("ejemplo-b")) })).ok)
        assertFalse(ValidadorRutina.validar(rutinaConAlternativas(JsonArray(emptyList()))).ok)
        // Una rutina sin alternativas sigue igual.
        assertTrue(ValidadorRutina.validar(rutina).ok)
    }

    @Test fun noEstaDisponibleConAlternativaYSinElla() {
        val archivo = rutinaConAlternativas(buildJsonArray { add(alt("ejemplo-b", true)) })
        val lunes = archivo.arr("sesiones")[0]
        val b = Gimnasio.nuevoBorrador("2030-10-14", lunes, archivo, ahora)
        assertEquals(1, b.arr("ejercicios")[0].arr("alternativas").size)
        // Sin alternativas en la rutina, el registro de la sesión es el de siempre (no aparece el campo).
        assertNull(Gimnasio.nuevoBorrador("2030-10-14", rutina.arr("sesiones")[0], rutina, ahora).arr("ejercicios")[0]["alternativas"])
        // Con la alternativa: cambia el ejercicio y sus series; queda registrado el original.
        val r = Gimnasio.noDisponible(b, 0, 0, ahora)!!
        val e = r.arr("ejercicios")[0]
        assertEquals("ejemplo-b", e.txt("ejercicio_id")); assertEquals(1, e.arr("series").size)
        assertEquals("ejemplo-a", e.obj("no_disponible")!!.txt("ejercicio_id")); assertEquals("ejemplo-b", e.obj("no_disponible")!!.txt("reemplazo"))
        assertFalse(Gimnasio.saltado(e))
        assertEquals(b, Gimnasio.deshacerNoDisponible(r, 0))   // deshacer deja el original tal cual
        // Saltarlo: cuenta como listo y no queda pendiente.
        val s = Gimnasio.noDisponible(b, 0, null, ahora)!!
        assertTrue(Gimnasio.saltado(s.arr("ejercicios")[0])); assertTrue(Gimnasio.ejercicioListo(s.arr("ejercicios")[0]))
        assertEquals(1, Gimnasio.primerPendiente(s))
        // Con una serie anotada ya no se puede.
        val anotada = Gimnasio.conValorSerie(b, 0, 0, "reps_reales", 10.0)
        assertNull(Gimnasio.noDisponible(anotada, 0, null, ahora))
        // Lo que vuelve al Evaluador (manifest de los datos diarios).
        val nd = Gimnasio.noDisponiblesDelDia(listOf(r, s.mas("fecha" to jp("2030-10-13"))), "2030-10-14")
        assertEquals(1, nd.size); assertEquals("ejemplo-b", nd[0].txt("reemplazo")); assertNull(nd[0]["original"])
    }

    @Test fun elManifestLlevaLoNuevoSoloSiHay() {
        val d = LocalDate.parse("2030-10-16"); val gen = ZonedDateTime.of(2030, 10, 17, 9, 0, 0, 0, DatosDiarios.ZONA)
        val lect = DatosDiarios.LecturaHc(null, false, emptyList())
        val sin = json(DatosDiarios.manifest("prueba", d, 1, gen, "0.18.0", 29, "manual", emptyMap(), emptyList(), lect, null))
        assertNull(sin["ejercicios_no_disponibles"]); assertNull(sin["senal_alarma"])
        val nd = listOf(buildJsonObject { put("sesion_id", "s"); put("ejercicio_id", "ejemplo-a"); put("reemplazo", "ejemplo-b") })
        val con = json(DatosDiarios.manifest("prueba", d, 1, gen, "0.18.0", 29, "manual", emptyMap(), emptyList(), lect, null, nd, true))
        assertEquals(1, (con["ejercicios_no_disponibles"] as JsonArray).size)
        assertEquals("true", con.obj("senal_alarma")!!["respuesta"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(sin), JsonObject(con.filterKeys { it != "ejercicios_no_disponibles" && it != "senal_alarma" }))
        assertNotNull(DatosDiarios.nombreManifest("prueba", d, 1))
    }
}
