package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 0.18.0: Entrenamiento validó 17 imágenes de ejercicios para casa (17-oct; decisión del Coordinador, provisoria hasta que
 * Héctor la revise). Solo cambia "validada_entrenamiento" en imagenes/catalogo.json; ninguna imagen cambia.
 */
class ImagenesValidadasTest {
    // Las pruebas corren desde android/app; el catálogo de la 2.0 está en la raíz del repositorio (imagenes/).
    private val archivo = listOf(File("../../imagenes/catalogo.json"), File("imagenes/catalogo.json")).first { it.isFile }
    private val imagenes = Json.parseToJsonElement(archivo.readText()).jsonObject["imagenes"]!!.jsonObject

    private val antes = setOf("balanceo-pierna-frontal", "circulos-tobillo", "crunch-inverso", "curl-biceps-polea", "curl-femoral-maquina",
        "curl-martillo-polea-cuerda", "dead-bug", "elevacion-posterior-tumbado-mancuernas", "eliptica", "estiramiento-cuadriceps-pie-elevado",
        "estiramiento-flexor-cadera-rodillas", "estiramiento-gemelo-pared", "estiramiento-gluteo-figura-4", "estiramiento-isquiotibiales-tumbado-banda",
        "extension-triceps-polea", "extension-triceps-polea-barra-v", "face-pull-polea", "gato-cuadrupedia", "gemelos-en-prensa", "jalon-agarre-neutro",
        "jalon-agarre-supino", "pallof-press", "peso-muerto-rumano-mancuernas", "prensa-piernas", "press-inclinado-maquina", "press-maquina-agarre-neutro",
        "puente-gluteo", "remo-mancuerna-una-mano", "remo-maquina-agarre-neutro", "remo-sentado-polea", "rodillas-al-pecho", "subida-banco-mancuernas",
        "zancada-caminando")
    private val nuevas = setOf("cruce-bandas", "press-banca-mancuernas", "sentadilla-al-banco-mancuernas", "sentadilla-mancuernas", "sentadilla-banda",
        "sentadilla-sumo-mancuerna", "zancada-atras-mancuernas", "gemelos-banda", "gemelos-sentado-una-pierna-mancuerna", "remo-deltoides-posterior-mancuerna",
        "aperturas-posteriores-banda", "curl-martillo-mancuernas", "curl-biceps-mancuernas", "curl-biceps-sentado-mancuernas", "patada-triceps-mancuerna",
        "activacion-abdominal-cuadrupedia", "superman")
    /** Revisadas y no validadas (quedan en false). */
    private val noValidadas = setOf("plancha-lateral", "extension-triceps-toalla", "elevacion-pierna-tumbado-boca-abajo", "elevacion-lateral-mancuernas", "circulos-rodilla")

    private fun validada(id: String) = ((imagenes[id] as JsonObject)["validada_entrenamiento"] as JsonPrimitive).content == "true"

    @Test fun lasDiecisieteSalenValidadasYElRestoNoCambia() {
        assertEquals(17, nuevas.size)
        assertEquals(287, imagenes.size)
        assertEquals(antes + nuevas, imagenes.keys.filter { validada(it) }.toSet())
        for (id in noValidadas) assertEquals(id, false, validada(id))
    }
}
