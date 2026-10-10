package io.github.sansolo2000.genesisgym

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sansolo2000.genesisgym.Gimnasio.arr
import io.github.sansolo2000.genesisgym.Gimnasio.esNulo
import io.github.sansolo2000.genesisgym.Gimnasio.num
import io.github.sansolo2000.genesisgym.Gimnasio.txt
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 0.6.0 — la sesión como en el mockup aprobado por Héctor (10-oct): mapa de la sesión, una hoja por ejercicio
 * (registrar / cómo se hace) y descanso con cuenta regresiva. Tema oscuro solo en la sesión.
 * Los datos son los mismos de antes: el registro de la sesión no cambia de formato.
 */
private val FONDO = Color(0xFF111315)
private val CAJA = Color(0xFF1B1E21)
private val CAJA2 = Color(0xFF24282C)
private val LINEA = Color(0xFF30353A)
private val TEXTO = Color(0xFFF2F1EC)
private val GRIS = Color(0xFFA3A8AD)
private val AMBAR = Color(0xFFF0A43A)
private val VERDE = Color(0xFF6CC3A0)
private val AZUL = Color(0xFF8FB7E8)

private val OSCURO = darkColorScheme(
    primary = AMBAR, onPrimary = Color(0xFF1A1300), secondary = VERDE, onSecondary = Color(0xFF0E1F18),
    background = FONDO, onBackground = TEXTO, surface = CAJA, onSurface = TEXTO, surfaceVariant = CAJA2, onSurfaceVariant = GRIS,
    outline = LINEA, error = Color(0xFFFF8A7A), errorContainer = Color(0xFF4A2420), onErrorContainer = Color(0xFFFFDAD4),
    primaryContainer = Color(0xFF3A2A10), onPrimaryContainer = TEXTO, secondaryContainer = Color(0xFF1E3A2F), onSecondaryContainer = TEXTO,
    tertiaryContainer = CAJA2, onTertiaryContainer = TEXTO
)

private val NOMBRE_BLOQUE = mapOf("cal" to "CALENTAMIENTO", "fuerza" to "FUERZA", "elong" to "ELONGACIÓN")
private val COLOR_BLOQUE = mapOf("cal" to VERDE, "fuerza" to AMBAR, "elong" to AZUL)

private fun mmss(seg: Int): String = "${seg / 60}:${(seg % 60).toString().padStart(2, '0')}"
private fun prescrito(se: JsonObject): String =
    if (se.txt("modo") == "tiempo") (se.num("prescrito_seg")?.toInt() ?: 0).let { if (it >= 60) "${mmss(it)} min" else "$it s" }
    else "${se.txt("prescrito_reps")} reps"

@Composable private fun Eti(t: String, color: Color = GRIS) = Text(t, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
@Composable private fun Chip(t: String) = Text(t, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(CAJA2).padding(horizontal = 10.dp, vertical = 5.dp))

@Composable
fun SesionNueva(g: Gym) {
    @Suppress("UNUSED_VARIABLE") val t = g.tick
    val vista = LocalView.current
    DisposableEffect(Unit) { vista.keepScreenOn = true; onDispose { vista.keepScreenOn = false } }   // pantalla encendida
    MaterialTheme(colorScheme = OSCURO) {
        Surface(Modifier.fillMaxSize(), color = FONDO, contentColor = TEXTO) {
            Box(Modifier.fillMaxSize()) {
                when (g.sesVista) {
                    "hoja" -> Hoja(g)
                    "descanso" -> Descanso(g)
                    "cierre" -> CierreSesion(g)
                    else -> Mapa(g)
                }
                if (g.aviso.isNotEmpty()) {
                    LaunchedEffect(g.aviso) { delay(3500); g.aviso = "" }
                    Text(g.aviso, fontSize = 15.sp, modifier = Modifier.align(Alignment.TopCenter).padding(start = 16.dp, end = 16.dp, top = 60.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E3A2F)).border(1.dp, VERDE, RoundedCornerShape(12.dp)).padding(12.dp))
                }
            }
        }
    }
}

// ---------- Mapa ----------
@Composable
private fun Mapa(g: Gym) {
    val b = g.borrador ?: return
    val ejs = b.arr("ejercicios")
    val listos = ejs.count { Gimnasio.ejercicioListo(it) }
    val pendiente = Gimnasio.primerPendiente(b)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { g.ir("hoy") }, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("‹ Hoy", fontWeight = FontWeight.SemiBold) }
            Text("${Gimnasio.fechaLarga(b.txt("fecha")!!)} · rutina v${b["rutina_version"]} · ~${b["duracion_estimada_min"]} min", color = GRIS, fontSize = 13.sp)
            Text(b.txt("sesion_nombre") ?: "", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(LINEA)) {
                    Box(Modifier.fillMaxWidth(if (ejs.isEmpty()) 0f else listos.toFloat() / ejs.size).height(8.dp).background(VERDE))
                }
                Text("$listos de ${ejs.size} listos", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            if (b.txt("estado") == "cerrada") Text("Sesión cerrada · puedes corregir valores y se guardan solos.", color = VERDE, fontSize = 13.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (bloque in listOf("cal", "fuerza", "elong")) {
                val idx = ejs.indices.filter { Gimnasio.bloqueDe(ejs[it]) == bloque }
                if (idx.isEmpty()) continue
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Eti(NOMBRE_BLOQUE[bloque]!!, COLOR_BLOQUE[bloque]!!); Eti("${idx.count { Gimnasio.ejercicioListo(ejs[it]) }}/${idx.size}", COLOR_BLOQUE[bloque]!!)
                    }
                    for (i in idx) FilaMapa(g, i, ejs[i])
                }
            }
            Text(ALARMA, color = GRIS, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (pendiente < 0) g.sesVista = "cierre" else g.abrirEjercicio(pendiente) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (pendiente < 0) "Todo anotado: ir al cierre" else "Continuar: ${ejs[pendiente].txt("nombre")}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
            }
            OutlinedButton(onClick = { g.sesVista = "cierre" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cierre: RPE, duración y nota", color = TEXTO) }
        }
    }
}

@Composable
private fun FilaMapa(g: Gym, i: Int, ej: JsonObject) {
    val series = ej.arr("series")
    val hechas = series.count { Gimnasio.registrada(it) }
    val listo = hechas == series.size && series.isNotEmpty()
    val curso = !listo && hechas > 0
    val cal = series.count { it.txt("tipo") == "calentamiento" }
    val ef = series.size - cal
    val detalle = (if (cal > 0 && ef > 0) "$cal calent. + " else "") + "${if (ef > 0) ef else cal} × ${series.lastOrNull()?.let { prescrito(it) } ?: ""}"
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp))
            .background(if (curso) Color(0xFF22262A) else if (listo) Color(0xFF15181A) else CAJA)
            .border(1.dp, if (curso) AMBAR else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable { g.abrirEjercicio(i) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (listo) VERDE else Color.Transparent).border(2.dp, if (listo) VERDE else if (curso) AMBAR else Color(0xFF4A5057), CircleShape),
            contentAlignment = Alignment.Center) { if (listo) Text("✓", color = FONDO, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        Column(Modifier.weight(1f)) {
            Text(ej.txt("nombre") ?: "", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detalle, color = GRIS, fontSize = 12.sp)
        }
        if (listo) Text("listo", color = VERDE, fontSize = 12.sp) else if (curso) Text("$hechas de ${series.size}", color = AMBAR, fontSize = 12.sp)
    }
}

// ---------- Hoja del ejercicio ----------
@Composable
private fun Hoja(g: Gym) {
    val b = g.borrador ?: return
    val ejs = b.arr("ejercicios")
    if (ejs.isEmpty()) { g.sesVista = "mapa"; return }
    val i = g.idxEj.coerceIn(0, ejs.size - 1)
    val ej = ejs[i]
    val bloque = Gimnasio.bloqueDe(ej)
    val enBloque = ejs.indices.filter { Gimnasio.bloqueDe(ejs[it]) == bloque }
    val series = ej.arr("series")
    val actual = series.indexOfFirst { !Gimnasio.registrada(it) }
    val descanso = ej.num("descanso_seg")?.toInt() ?: 0
    val sesiones = g.sesiones()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { g.sesVista = "mapa" }) { Text("‹ Mapa", fontWeight = FontWeight.SemiBold) }
            Text(b.txt("sesion_nombre") ?: "", color = GRIS, fontSize = 14.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${i + 1}", fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(" / ${ejs.size}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GRIS)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (j in ejs.indices) Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp))
                .background(if (j == i) AMBAR else if (Gimnasio.ejercicioListo(ejs[j])) VERDE else LINEA))
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Eti("${NOMBRE_BLOQUE[bloque]} · ${enBloque.indexOf(i) + 1} DE ${enBloque.size}", COLOR_BLOQUE[bloque]!!)
            Text(ej.txt("nombre") ?: "", fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CAJA).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((ficha, txt) in listOf(false to "Registrar", true to "Cómo se hace")) {
                val sel = g.verFicha == ficha
                Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(9.dp)).background(if (sel) Color(0xFF2C3135) else Color.Transparent)
                    .clickable { g.verFicha = ficha }, contentAlignment = Alignment.Center) {
                    Text(txt, color = if (sel) TEXTO else GRIS, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (g.verFicha) FichaHoja(g, ej)
            else {
                val ef = series.filter { it.txt("tipo") == "efectiva" }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (ef.isNotEmpty()) { Chip("${ef.size} ${if (ef.size == 1) "efectiva" else "efectivas"}"); Chip(prescrito(ef[0])) }
                    ef.firstOrNull()?.get("rpe_objetivo")?.takeIf { it !is JsonNull }?.let { Chip("RPE ${Gimnasio.kg(it)}") }
                    if (descanso > 0) Chip("Descanso ${mmss(descanso)}")
                }
                ej.txt("notas")?.takeIf { it.isNotEmpty() }?.let { Text(it, color = GRIS, fontSize = 14.sp) }
                series.forEachIndexed { k, se -> FilaSerie(g, b, i, k, ej.txt("ejercicio_id")!!, se, sesiones, k == actual) }
            }
        }
        if (descanso > 0 && !g.verFicha) OutlinedButton(onClick = {
            val k = if (actual < 0) series.size - 1 else (actual - 1).coerceAtLeast(0)
            g.iniciarDescanso(i, k)
        }, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 48.dp)) { Text("Iniciar descanso ${mmss(descanso)}", color = AMBAR) }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { g.abrirEjercicio(i - 1) }, enabled = i > 0, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Text("‹ Anterior", fontSize = 11.sp, color = GRIS)
                    Text(if (i > 0) ejs[i - 1].txt("nombre") ?: "" else "—", fontSize = 14.sp, color = TEXTO, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Button(onClick = { if (i + 1 < ejs.size) g.abrirEjercicio(i + 1) else g.sesVista = "cierre" }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    Text("Siguiente ›", fontSize = 11.sp)
                    Text(if (i + 1 < ejs.size) ejs[i + 1].txt("nombre") ?: "" else "Cierre", fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun FilaSerie(g: Gym, b: JsonObject, i: Int, k: Int, ejId: String, se: JsonObject, sesiones: List<JsonObject>, actual: Boolean) {
    val modo = se.txt("modo")
    val hecha = Gimnasio.registrada(se)
    val repKey = if (modo == "tiempo") "seg_reales" else "reps_reales"
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (actual) Color(0xFF22262A) else CAJA)
        .border(1.dp, if (actual) AMBAR else CAJA2, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.width(52.dp)) {
                Text("${k + 1}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(if (se.txt("tipo") == "calentamiento") "calent." else "efectiva", color = GRIS, fontSize = 11.sp)
            }
            if (modo == "tiempo") {
                Text(if (hecha) "${se.num("seg_reales")?.toInt() ?: 0} s ✓" else prescrito(se), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            } else key(b.txt("fecha"), b.txt("sesion_id"), i, k, se["carga_real_kg"].toString(), se[repKey].toString()) {
                var carga by remember { mutableStateOf(Gimnasio.kg(se["carga_real_kg"])) }
                var reps by remember { mutableStateOf(se[repKey]?.takeIf { it !is JsonNull }?.toString() ?: "") }
                OutlinedTextField(value = carga, onValueChange = { v -> carga = v; anotar(g, i, k, "carga_real_kg", v, false) },
                    label = { Text("kg") }, placeholder = { Text(if (se.esNulo("carga_prescrita_kg")) "—" else Gimnasio.kg(se["carga_prescrita_kg"])) },
                    singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(value = reps, onValueChange = { v -> reps = v; anotar(g, i, k, repKey, v, true) },
                    label = { Text("reps") }, placeholder = { Text(se.txt("prescrito_reps") ?: "") },
                    singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Box(Modifier.size(48.dp).clip(CircleShape).background(if (hecha) VERDE else Color.Transparent)
                .border(2.dp, if (hecha) VERDE else Color(0xFF4A5057), CircleShape)
                .semantics { contentDescription = if (hecha) "Serie ${k + 1} anotada: iniciar descanso" else "Marcar serie ${k + 1} como hecha" }
                .clickable { g.marcarSerie(i, k) }, contentAlignment = Alignment.Center) {
                Text(if (hecha) "✓" else "", color = FONDO, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        val indic = listOfNotNull(se.txt("carga_indicacion")?.takeIf { it.isNotEmpty() && se.esNulo("carga_prescrita_kg") },
            se["rpe_objetivo"]?.takeIf { it !is JsonNull }?.let { "RPE obj. ${Gimnasio.kg(it)}" })
        if (indic.isNotEmpty()) Text(indic.joinToString(" · "), color = GRIS, fontSize = 12.sp)
        val prev = Gimnasio.anterior(sesiones, ejId, se.num("n") ?: 0.0, b.txt("fecha")!!)
        Text(prev?.let { (s, p) ->
            val c = if (p.esNulo("carga_real_kg")) "—" else Gimnasio.kg(p["carga_real_kg"]) + " kg"
            val r = if (modo == "tiempo") (if (p.esNulo("seg_reales")) "—" else "${p["seg_reales"]} s") else (if (p.esNulo("reps_reales")) "—" else "${p["reps_reales"]}")
            "Anterior (${Gimnasio.fechaCorta(s.txt("fecha")!!)}): $c × $r"
        } ?: "Sin registro anterior", color = GRIS, fontSize = 12.sp)
    }
}

@Composable
private fun FichaHoja(g: Gym, ej: JsonObject) {
    val id = ej.txt("ejercicio_id") ?: return
    val e = g.rutina()?.arr("ejercicios")?.firstOrNull { it.txt("id") == id }
    fun lista(k: String): List<String> = (e?.get(k) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFF4F3EF)).padding(8.dp)) {
        MaterialTheme(colorScheme = androidx.compose.material3.lightColorScheme()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { FichaImagenes(g, id, ej.txt("nombre") ?: id) }
        }
    }
    e?.txt("equipo")?.let { Text("Equipo: $it", fontSize = 14.sp) }
    val m = lista("musculos_principales"); val s = lista("musculos_secundarios")
    if (m.isNotEmpty()) Text("Músculos: " + m.joinToString(", ") + if (s.isNotEmpty()) " (también: ${s.joinToString(", ")})" else "", fontSize = 14.sp)
    e?.txt("descripcion")?.let { Text(it, fontSize = 14.sp, color = GRIS) }
    Eti("PASOS")
    lista("pasos").forEachIndexed { n, p ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text("${n + 1}", color = AMBAR, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.width(18.dp)); Text(p, fontSize = 14.sp) }
    }
    lista("errores_comunes").takeIf { it.isNotEmpty() }?.let { Eti("ERRORES COMUNES"); it.forEach { x -> Text("• $x", fontSize = 14.sp) } }
    lista("precauciones").takeIf { it.isNotEmpty() }?.let {
        Text("Precaución: " + it.joinToString(" "), fontSize = 14.sp, modifier = Modifier.fillMaxWidth().border(1.dp, AMBAR, RoundedCornerShape(10.dp)).padding(10.dp))
    }
    if (e == null) Text("La rutina activa no trae la ficha de este ejercicio.", color = GRIS)
}

// ---------- Descanso ----------
@Composable
private fun Descanso(g: Gym) {
    val fin = g.descansoFin
    var ahora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(fin) {
        while (fin != null) {
            ahora = System.currentTimeMillis()
            if (ahora >= fin) { g.terminarDescanso(true); break }
            delay(250)
        }
    }
    if (fin == null) { LaunchedEffect(Unit) { g.sesVista = "hoja" }; return }
    val restante = (((fin - ahora) + 999) / 1000).toInt().coerceAtLeast(0)
    val total = g.descansoTotal.coerceAtLeast(1)
    val ej = g.descansoDe?.let { g.borrador?.arr("ejercicios")?.getOrNull(it.first) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { g.cancelarDescanso(); g.sesVista = "mapa" }) { Text("‹ Mapa", fontWeight = FontWeight.SemiBold) }
            Text("${ej?.txt("nombre") ?: ""} · serie ${(g.descansoDe?.second ?: 0) + 1} ✓", color = GRIS, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically)) {
            Eti("DESCANSO")
            Box(Modifier.size(260.dp).semantics { contentDescription = "Quedan ${mmss(restante)} de ${mmss(total)}" }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val grosor = 16.dp.toPx()
                    val tam = Size(size.width - grosor, size.height - grosor)
                    val o = Offset(grosor / 2, grosor / 2)
                    drawArc(CAJA2, 0f, 360f, false, o, tam, style = Stroke(grosor))
                    drawArc(AMBAR, -90f, 360f * restante / total, false, o, tam, style = Stroke(grosor, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(mmss(restante), fontSize = 76.sp, fontWeight = FontWeight.Bold)
                    Text("de ${mmss(total)}", color = GRIS, fontSize = 14.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { g.masTreinta() }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("+30 s", color = TEXTO) }
                OutlinedButton(onClick = { g.terminarDescanso(false) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Terminar descanso", color = TEXTO) }
            }
            Text("Al llegar a 0 suena el aviso (sonido y volumen de la pestaña Avisos), aunque la pantalla esté apagada.", color = GRIS, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CAJA).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Eti("LO QUE SIGUE", AMBAR)
            Text(g.textoSiguiente(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Button(onClick = { g.terminarDescanso(false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 6.dp)) { Text("Ir ahora") }
        }
    }
}
