package io.github.sansolo2000.genesisgym

import io.github.sansolo2000.genesisgym.Gimnasio.obj
import io.github.sansolo2000.genesisgym.Gimnasio.txt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Importador del plan Génesis (estado-genesis v40; diseño aprobado en principio por el Coordinador #76).
 * El Consolidador entrega un sobre con las tres partes copiadas sin modificar:
 *   { tipo: "plan-genesis", version_formato: "1.0", perfil, generado_en, fuentes, rutina, alimentacion, apoyo }
 * Reglas:
 *  - Cada parte pasa por el mismo validador que su importación por separado (los contratos no cambian).
 *  - Todo o nada: si una parte tiene errores, no se importa ninguna.
 *  - La parte idéntica a la activa se omite ("ya está activa").
 *  - Módulo Medicamentos (15-oct): el sobre puede traer además la sección opcional "medicamentos" (catálogo y configuración,
 *    CONTEXTO-MODULO-MEDICAMENTOS §5). Un plan sin ella se revisa igual que antes.
 * Lógica pura, sin Android, para probarla con JUnit.
 */
object Plan {
    const val TIPO = "plan-genesis"
    const val VERSION = "1.0"

    data class Parte(
        val clave: String,          // rutina | alimentacion | apoyo
        val nombre: String,         // texto para la pantalla
        val estado: String,         // nueva | igual | error
        val errores: List<String>,
        val avisos: List<String>,
        val docs: List<Respaldo.Doc>,
        val detalle: String,
        val contenido: JsonObject?
    )

    data class Revision(val errores: List<String>, val avisos: List<String>, val partes: List<Parte>) {
        val ok: Boolean get() = errores.isEmpty() && partes.none { it.estado == "error" }
        val nuevas: List<Parte> get() = partes.filter { it.estado == "nueva" }
    }

    private fun slug(s: String?): String = java.text.Normalizer.normalize(s ?: "", java.text.Normalizer.Form.NFD)
        .replace(Regex("[\\u0300-\\u036f]"), "").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /**
     * [rutinaActiva]: documento config/rutina_activa; [alimentacionActiva] y [apoyoActivo]: el archivo del programa activo
     * (los mismos valores que usan sus importaciones por separado).
     */
    fun revisar(
        texto: String, rutinaActiva: JsonObject?, alimentacionActiva: JsonObject?, apoyoActivo: JsonObject?,
        perfilCelular: String?, ahoraIso: String, huellaMedicamentos: String? = null
    ): Revision {
        if (texto.isBlank()) return Revision(listOf("El archivo llegó vacío al celular."), emptyList(), emptyList())
        val sobre = try { Json.parseToJsonElement(texto.removePrefix("﻿")) as? JsonObject } catch (e: Exception) { null }
            ?: return Revision(listOf("El archivo no es un JSON válido."), emptyList(), emptyList())
        val err = mutableListOf<String>(); val av = mutableListOf<String>()
        if (sobre.txt("tipo") != TIPO) err.add("tipo: debe ser \"$TIPO\". ¿Es un plan del Consolidador? Las partes sueltas se importan en su pestaña.")
        if (sobre.txt("version_formato") != VERSION) err.add("version_formato: debe ser \"$VERSION\".")
        val perfil = sobre.txt("perfil")
        if (perfil.isNullOrBlank()) err.add("perfil: falta.")
        else if (perfilCelular != null && slug(perfil) != slug(perfilCelular))
            av.add("Este plan es para \"$perfil\" y este celular es de \"$perfilCelular\". Revisa que sea el archivo correcto.")
        val faltan = listOf("rutina", "alimentacion", "apoyo").filter { sobre.obj(it) == null }
        if (faltan.isNotEmpty()) err.add("Faltan partes en el sobre: ${faltan.joinToString(", ")}.")
        if (sobre["medicamentos"] != null && sobre.obj("medicamentos") == null) err.add("medicamentos: debe ser un objeto.")
        if (err.isNotEmpty()) return Revision(err, av, emptyList())

        val partes = listOfNotNull(
            parteRutina(sobre.obj("rutina")!!, rutinaActiva, ahoraIso),
            parteAlimentacion(sobre.obj("alimentacion")!!, alimentacionActiva, ahoraIso),
            parteApoyo(sobre.obj("apoyo")!!, apoyoActivo, ahoraIso),
            sobre.obj("medicamentos")?.let { parteMedicamentos(it, huellaMedicamentos, ahoraIso) }
        )
        return Revision(emptyList(), av, partes)
    }

    /** [activa]: huella de la sección importada antes (null si no hay o falta el catálogo en el celular). El catálogo lo guarda la actividad en un archivo. */
    private fun parteMedicamentos(o: JsonObject, activa: String?, ahora: String): Parte {
        val (s, err, av) = Medicamentos.revisarSeccion(o)
        val cat = o.obj("catalogo")
        val det = "catálogo ${cat?.txt("version") ?: "?"} · ${s?.registros ?: (cat?.get("medicamentos") as? kotlinx.serialization.json.JsonArray)?.size ?: 0} medicamentos"
        return when {
            s == null -> Parte("medicamentos", "Medicamentos (catálogo)", "error", err, av, emptyList(), det, o)
            s.huella == activa -> Parte("medicamentos", "Medicamentos (catálogo)", "igual", emptyList(), emptyList(), emptyList(), det, o)
            else -> Parte("medicamentos", "Medicamentos (catálogo)", "nueva", emptyList(), av,
                listOf(Respaldo.Doc("config/medicamentos", Medicamentos.documentoActivo(s, ahora))), det, o)
        }
    }

    private fun parteRutina(o: JsonObject, activa: JsonObject?, ahora: String): Parte {
        val im = Gimnasio.revisarImportacion(JsJson.stringify(o), activa)
        val ru = o.obj("rutina")
        val det = "${ru?.txt("nombre") ?: ru?.txt("id") ?: "rutina"} · versión ${ru?.get("version") ?: "?"}"
        return when {
            im.yaActiva -> Parte("rutina", "Rutina de fuerza", "igual", emptyList(), emptyList(), emptyList(), det, o)
            !im.resultado.ok -> Parte("rutina", "Rutina de fuerza", "error", im.resultado.errores, im.resultado.avisos, emptyList(), det, o)
            else -> Parte("rutina", "Rutina de fuerza", "nueva", emptyList(), im.resultado.avisos, Gimnasio.documentosActivacion(im.rutina!!, ahora), det, im.rutina)
        }
    }

    private fun parteAlimentacion(o: JsonObject, activo: JsonObject?, ahora: String): Parte {
        val (obj, r) = Comidas.revisarTexto(JsJson.stringify(o), activo)
        val p = o.obj("programa")
        val det = "${p?.txt("nombre") ?: p?.txt("id") ?: "programa"} · versión ${p?.get("version") ?: "?"}"
        return when {
            r.yaActivo -> Parte("alimentacion", "Programa de alimentación", "igual", emptyList(), emptyList(), emptyList(), det, o)
            !r.ok || obj == null -> Parte("alimentacion", "Programa de alimentación", "error", r.errores, r.avisos, emptyList(), det, o)
            else -> Parte("alimentacion", "Programa de alimentación", "nueva", emptyList(), r.avisos, EstadoComidas.documentosActivacion(obj, ahora), det, obj)
        }
    }

    private fun parteApoyo(o: JsonObject, activo: JsonObject?, ahora: String): Parte {
        val (obj, r) = Apoyo.revisarTexto(JsJson.stringify(o), activo)
        val p = o.obj("programa")
        val det = "${p?.txt("id") ?: "programa"} · versión ${p?.get("version") ?: "?"}"
        return when {
            r.yaActivo -> Parte("apoyo", "Programa de apoyo", "igual", emptyList(), emptyList(), emptyList(), det, o)
            !r.ok || obj == null -> Parte("apoyo", "Programa de apoyo", "error", r.errores, r.avisos, emptyList(), det, o)
            else -> Parte("apoyo", "Programa de apoyo", "nueva", emptyList(), r.avisos, Apoyo.documentosActivacion(obj, ahora), det, obj)
        }
    }
}
