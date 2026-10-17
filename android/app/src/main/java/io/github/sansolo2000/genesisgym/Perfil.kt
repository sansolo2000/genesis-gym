package io.github.sansolo2000.genesisgym

import io.github.sansolo2000.genesisgym.Apoyo.a
import io.github.sansolo2000.genesisgym.Apoyo.b
import io.github.sansolo2000.genesisgym.Apoyo.o
import io.github.sansolo2000.genesisgym.Apoyo.s
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Multiusuario, Parte 1 (DISENO-MULTIUSUARIO v3, aprobado por Héctor el 16-oct): el perfil único del celular.
 * Lógica pura, sin Android, para probarla con JUnit. Formatos en `claude/CONTEXTO-MULTIUSUARIO-APP.md`.
 *
 * Documento `config/perfil` (entra al respaldo, como antes):
 *   { nombre, creado,                                       ← lo de siempre (0.10)
 *     id, modulos: [..], ocultos: [..], senales_alarma: {..}, plan: {..} }   ← solo si se importó un plan con la sección "perfil"
 * Un perfil sin "id" es el de antes: el id sale del nombre ("Héctor" → "hector") y están todos los módulos.
 *
 * Señales de alarma (común a todos los módulos): la respuesta del día va en `config/senales_<AAAA-MM-DD>`
 * (en "config" para no sumar una colección nueva al respaldo).
 */
object Perfil {
    const val RUTA = "config/perfil"

    const val ENTRENAMIENTO = "entrenamiento"; const val COMIDAS = "comidas"; const val MEDICAMENTOS = "medicamentos"
    const val APOYO = "apoyo"; const val SALUD = "salud"
    val MODULOS = listOf(ENTRENAMIENTO, COMIDAS, MEDICAMENTOS, APOYO, SALUD)
    val NOMBRE = mapOf(ENTRENAMIENTO to "Entrenamiento", COMIDAS to "Comidas", MEDICAMENTOS to "Medicamentos", APOYO to "Apoyo (reporte diario)",
        SALUD to "Salud (Health Connect y cintura)")
    /** La parte del plan que necesita cada módulo (los demás no traen parte). */
    val PARTE = mapOf(ENTRENAMIENTO to "rutina", COMIDAS to "alimentacion", APOYO to "apoyo")
    val ESTADOS = listOf("borrador", "entregado")

    private val ID = Regex("^[a-z0-9][a-z0-9-]{0,39}$")
    fun idValido(s: String?): Boolean = s != null && ID.matches(s)

    data class Senales(val pregunta: String, val senales: List<String>, val accion: String)
    data class Meta(val generadoPor: String?, val para: String?, val estado: String?, val generadoEn: String?)

    /** Lo que dice `config/perfil`. [modulos] null = perfil de antes (todos los módulos). */
    data class Datos(val id: String, val nombre: String?, val modulos: List<String>?, val ocultos: List<String>, val senales: Senales?, val plan: Meta?) {
        val existe: Boolean get() = nombre != null || modulos != null
        val conPlan: Boolean get() = modulos != null
        /** El módulo se muestra: lo trae el perfil (o es un perfil de antes) y la persona no lo ocultó. */
        fun visible(m: String): Boolean = (modulos == null || m in modulos) && m !in ocultos
        val visibles: List<String> get() = MODULOS.filter { visible(it) }
        /** Los módulos que la persona puede ocultar o volver a mostrar: los del plan, o todos en un perfil de antes. */
        val propios: List<String> get() = modulos ?: MODULOS
    }

    private fun lista(e: JsonElement?): List<String> = (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()

    fun senalesDe(o: JsonObject?): Senales? {
        val p = o.s("pregunta")?.trim(); val acc = o.s("accion")?.trim(); val l = lista(o?.get("senales")).map { it.trim() }.filter { it.isNotEmpty() }
        return if (p.isNullOrEmpty() || acc.isNullOrEmpty() || l.isEmpty()) null else Senales(p, l, acc)
    }

    fun leer(doc: JsonObject?): Datos {
        val nombre = doc.s("nombre")?.takeIf { it.isNotBlank() }
        val id = doc.s("id")?.takeIf { idValido(it) } ?: SaludCsv.slug(nombre)
        val mod = (doc?.get("modulos") as? JsonArray)?.let { lista(it).filter { m -> m in MODULOS } }
        val plan = doc.o("plan")?.let { Meta(it.s("generado_por"), it.s("para"), it.s("estado"), it.s("generado_en")) }
        return Datos(id, nombre, mod, lista(doc?.get("ocultos")).filter { it in MODULOS }, senalesDe(doc.o("senales_alarma")), plan)
    }

    /** El perfil escrito a mano en Hoy (el de siempre); conserva lo que trajo el plan. */
    fun conNombre(doc: JsonObject?, nombre: String, ahoraIso: String): JsonObject = JsonObject(LinkedHashMap(doc ?: JsonObject(emptyMap())).also {
        it["nombre"] = JsonPrimitive(nombre); if (doc?.get("creado") == null) it["creado"] = JsonPrimitive(ahoraIso)
    })

    /** Ocultar o volver a mostrar un módulo. No borra nada: solo cambia la lista "ocultos". */
    fun ocultar(doc: JsonObject?, modulo: String, ocultar: Boolean): JsonObject {
        val actual = lista(doc?.get("ocultos")).filter { it in MODULOS }
        val nueva = if (ocultar) (actual + modulo).distinct() else actual - modulo
        return JsonObject(LinkedHashMap(doc ?: JsonObject(emptyMap())).also { it["ocultos"] = JsonArray(nueva.map { m -> JsonPrimitive(m) }) })
    }

    // ---------- sección "perfil" del plan Génesis ----------
    /** La sección nueva del plan: { id, nombre, modulos, senales_alarma } más los metadatos del sobre (generado_por, para, estado). */
    data class DelPlan(val id: String, val nombre: String, val modulos: List<String>, val senales: Senales, val meta: Meta)

    /**
     * Revisa la sección "perfil" cuando es un objeto (el formato nuevo). Devuelve la sección o los errores.
     * Seguridad (CLAUDE.md §3 y §3 del diseño): sin las señales de alarma y el texto de acción de la persona, el plan no se importa.
     */
    fun revisarSeccion(sobre: JsonObject): Pair<DelPlan?, List<String>> {
        val err = mutableListOf<String>()
        val p = sobre.o("perfil")!!
        val id = p.s("id")
        if (!idValido(id)) err.add("perfil.id: solo minúsculas, números y guiones (hasta 40), por ejemplo \"persona-1\".")
        val nombre = p.s("nombre")?.trim()
        if (nombre.isNullOrEmpty()) err.add("perfil.nombre: falta.")
        val modsJson = p["modulos"] as? JsonArray
        val mods = lista(modsJson)
        if (modsJson == null || mods.isEmpty()) err.add("perfil.modulos: debe ser una lista con al menos un módulo (${MODULOS.joinToString(", ")}).")
        else {
            if (mods.size != modsJson.size) err.add("perfil.modulos: cada módulo es un texto.")
            for (m in mods.distinct()) if (m !in MODULOS) err.add("perfil.modulos: \"$m\" no es un módulo de la app (${MODULOS.joinToString(", ")}).")
            if (mods.distinct().size != mods.size) err.add("perfil.modulos: hay módulos repetidos.")
        }
        val sen = senalesDe(p.o("senales_alarma"))
        if (sen == null) err.add("perfil.senales_alarma: faltan la pregunta, las señales o el texto de acción de esta persona. Por seguridad, un plan sin sus señales de alarma no se importa.")
        val meta = Meta(sobre.s("generado_por"), sobre.s("para"), sobre.s("estado"), sobre.s("generado_en"))
        if (meta.generadoPor.isNullOrBlank()) err.add("generado_por: falta (quién generó el plan).")
        if (meta.para != id) err.add("para: debe ser el id de la persona del plan (\"${id ?: ""}\").")
        if (meta.estado !in ESTADOS) err.add("estado: debe ser \"borrador\" o \"entregado\".")
        if (err.isNotEmpty()) return null to err
        return DelPlan(id!!, nombre!!, mods, sen!!, meta) to emptyList()
    }

    /** Módulos que la persona ocultó y que este plan trae: se pregunta antes de volver a mostrarlos. */
    fun ocultosQueTrae(actual: Datos, p: DelPlan): List<String> = p.modulos.filter { it in actual.ocultos }

    /**
     * El `config/perfil` después de importar el plan. Conserva "creado" y los ocultos que la persona no pidió volver a mostrar
     * ([mostrar]); un módulo que el plan ya no trae deja de estar en "ocultos" (no se muestra igual).
     */
    fun aplicar(doc: JsonObject?, p: DelPlan, mostrar: Set<String>, ahoraIso: String): JsonObject {
        val antes = leer(doc)
        val ocultos = antes.ocultos.filter { it in p.modulos && it !in mostrar }
        return Apoyo.objeto(
            "nombre" to JsonPrimitive(p.nombre), "creado" to (doc?.get("creado") ?: JsonPrimitive(ahoraIso)),
            "id" to JsonPrimitive(p.id),
            "modulos" to JsonArray(p.modulos.map { JsonPrimitive(it) }),
            "ocultos" to JsonArray(ocultos.map { JsonPrimitive(it) }),
            "senales_alarma" to Apoyo.objeto("pregunta" to JsonPrimitive(p.senales.pregunta), "senales" to JsonArray(p.senales.senales.map { JsonPrimitive(it) }),
                "accion" to JsonPrimitive(p.senales.accion)),
            "plan" to Apoyo.objeto("generado_por" to Apoyo.txt(p.meta.generadoPor), "para" to Apoyo.txt(p.meta.para), "estado" to Apoyo.txt(p.meta.estado),
                "generado_en" to Apoyo.txt(p.meta.generadoEn), "importado_en" to JsonPrimitive(ahoraIso)))
    }

    /** ¿Cambia algo en `config/perfil`? (para importar un plan cuyas partes ya estaban activas). */
    fun cambia(doc: JsonObject?, p: DelPlan): Boolean {
        val a = leer(doc)
        return a.id != p.id || a.nombre != p.nombre || a.modulos != p.modulos || a.senales != p.senales || a.plan?.estado != p.meta.estado ||
            a.plan?.generadoEn != p.meta.generadoEn || a.plan?.generadoPor != p.meta.generadoPor
    }

    // ---------- señales de alarma (componente común) ----------
    fun rutaSenal(fecha: String) = "config/senales_$fecha"

    /** La pregunta, las señales y la acción del día. Del perfil (plan nuevo) o, en un perfil de antes, de la pregunta de Apoyo. */
    fun senalesVigentes(d: Datos, apoyo: EstadoApoyo?): Senales? {
        d.senales?.let { return it }
        if (d.conPlan) return null   // un plan nuevo siempre trae las suyas; nunca se usan las de otro
        val ap = apoyo ?: return null
        val ai = ap.programa.o("alarma_inmediata") ?: return null
        val campo = ai.s("campo") ?: return null
        val pregunta = ap.pregunta(campo).s("texto") ?: return null
        val accion = ap.textoAlarma() ?: return null
        return Senales(pregunta, emptyList(), accion)
    }

    fun registroSenal(fecha: String, respuesta: Boolean, perfilId: String, ahoraIso: String): JsonObject = Apoyo.objeto(
        "tipo" to JsonPrimitive("senales-alarma"), "fecha" to JsonPrimitive(fecha), "perfil" to JsonPrimitive(perfilId),
        "respuesta" to JsonPrimitive(respuesta), "respondida_en" to JsonPrimitive(ahoraIso))

    fun respuestaSenal(doc: JsonObject?): Boolean? = doc.b("respuesta")

    /** Nombre para mostrar de un id de persona guardado en un registro (Medicamentos): el del perfil si es el mismo; si no, el id. */
    fun nombreDe(personaId: String, d: Datos): String = if (personaId == d.id) d.nombre ?: personaId else personaId.replaceFirstChar { it.uppercase() }
}
