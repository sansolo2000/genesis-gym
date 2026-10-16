package io.github.sansolo2000.genesisgym

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * Pestaña Medicamentos: tratamientos de Héctor y de Majo, tomas de hoy, respuestas e historial.
 * La app registra lo que indica la receta: no recomienda dosis ni cambios de medicación (CLAUDE.md §3).
 */
class Medi(private val c: Context, private val base: BaseLocal, private val alCambiar: () -> Unit) {
    var tick by mutableIntStateOf(0)
    var vista by mutableStateOf("lista")              // lista | form | historial
    var filtro by mutableStateOf<String?>(null)       // persona, o null = las dos
    var mensaje by mutableStateOf<Pair<String, String>?>(null)
    var cfg: Medicamentos.Config? = null; private set
    var catalogo by mutableStateOf<Medicamentos.Catalogo?>(null); private set
    var tratamientos by mutableStateOf<List<Medicamentos.Tratamiento>>(emptyList()); private set
    var registros by mutableStateOf<List<Medicamentos.Registro>>(emptyList()); private set
    /** Toma abierta desde la notificación (Posponer… o tocarla): (tratamiento, hora local, evento). */
    var porResponder by mutableStateOf<Triple<String, LocalDateTime, String?>?>(null)
    var historialDe by mutableStateOf<String?>(null)

    // formulario
    var anterior: Medicamentos.Tratamiento? = null; private set
    var fPersona by mutableStateOf<String?>(null)
    var fMed by mutableStateOf<Medicamentos.Med?>(null)
    var fBusca by mutableStateOf("")
    var fCantidad by mutableStateOf("")
    var fUnidad by mutableStateOf<String?>(null)
    var fModalidad by mutableStateOf<String?>(null)
    var fVeces by mutableStateOf("")
    var fIntervaloH by mutableStateOf("")
    var fIntervaloD by mutableStateOf("")
    val fDias = mutableStateListOf<Int>()
    var fToma by mutableStateOf("")
    var fDescanso by mutableStateOf("")
    var fCiclos by mutableStateOf("")
    val fHorarios = mutableStateListOf<String>()
    val fEventos = mutableStateListOf<Medicamentos.Toma>()
    var fInicio by mutableStateOf("")
    var fDur by mutableStateOf(Medicamentos.DUR_INDEFINIDA)
    var fDurDias by mutableStateOf("")
    var fDurFin by mutableStateOf("")
    var fAlertas by mutableStateOf(true)
    var errores by mutableStateOf<List<String>>(emptyList())

    fun hayCatalogo(): Boolean = AvisosMedicamentos.hayCatalogo(c)
    fun hoy(): LocalDate = LocalDate.now(AvisosMedicamentos.zona())
    fun ahora(): ZonedDateTime = ZonedDateTime.now(AvisosMedicamentos.zona())

    fun cargar() {
        mensaje = try {
            cfg = AvisosMedicamentos.config(c, base)
            tratamientos = AvisosMedicamentos.tratamientos(base).sortedWith(compareBy({ it.persona }, { Medicamentos.normalizar(it.med.nombre) }))
            registros = AvisosMedicamentos.registros(base)
            null
        } catch (e: Exception) { "err" to "No se pudo leer la base de este celular: ${e.javaClass.simpleName}" }
        tick++
    }

    /** Después de importar un plan con catálogo nuevo. */
    fun olvidarCatalogo() { catalogo = null }

    /** El catálogo pesa varios MB: se lee fuera del hilo de la pantalla. */
    suspend fun cargarCatalogo() {
        if (catalogo != null) return
        catalogo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { AvisosMedicamentos.catalogo(c) }.getOrNull() }
    }

    fun abrirRespuesta(tid: String?, local: String?, evento: String?) {
        val l = runCatching { LocalDateTime.parse(local) }.getOrNull()
        if (tid != null && l != null) { porResponder = Triple(tid, l, evento); vista = "lista" }
        cargar()
    }

    fun responder(tid: String, local: LocalDateTime, evento: String?, estado: String, minutos: Int?) {
        val ok = runCatching { AvisosMedicamentos.responder(c, tid, local, evento, estado, minutos, "app") }.getOrDefault(false)
        mensaje = if (ok) "ok" to (Medicamentos.ESTADOS[estado] + (minutos?.let { " $it min" } ?: "") + ". Quedó en el historial.")
        else "err" to "No se pudo registrar: el tratamiento ya no existe."
        if (porResponder?.first == tid && porResponder?.second == local) porResponder = null
        cargar(); alCambiar()
    }

    /** Toma fuera de horario (por ejemplo "según necesidad"): se registra confirmada a la hora actual. */
    fun registrarAhora(t: Medicamentos.Tratamiento) = responder(t.id, ahora().toLocalDateTime().withSecond(0).withNano(0), null, Medicamentos.CONFIRMADA, null)

    // ---------- formulario ----------
    fun nuevo() {
        anterior = null; errores = emptyList(); mensaje = null
        fPersona = filtro; fMed = null; fBusca = ""; fCantidad = ""; fUnidad = null; fModalidad = null
        fVeces = ""; fIntervaloH = ""; fIntervaloD = ""; fDias.clear(); fToma = ""; fDescanso = ""; fCiclos = ""
        fHorarios.clear(); fEventos.clear(); fInicio = hoy().toString(); fDur = Medicamentos.DUR_INDEFINIDA; fDurDias = ""; fDurFin = ""; fAlertas = true
        vista = "form"
    }

    fun editar(t: Medicamentos.Tratamiento) {
        nuevo(); anterior = t
        fPersona = t.persona; fMed = t.med; fCantidad = Medicamentos.numeroTexto(t.cantidad); fUnidad = t.unidad
        val f = t.frecuencia
        fModalidad = f.modalidad; fVeces = f.veces?.toString() ?: ""; fIntervaloH = f.intervaloHoras?.toString() ?: ""; fIntervaloD = f.intervaloDias?.toString() ?: ""
        fDias.addAll(f.dias); fToma = f.diasToma?.toString() ?: ""; fDescanso = f.diasDescanso?.toString() ?: ""; fCiclos = f.ciclosMax?.toString() ?: ""
        fHorarios.addAll(f.horarios); fEventos.addAll(f.eventos)
        fInicio = t.inicio.toString(); fDur = t.duracion.tipo; fDurDias = t.duracion.dias?.toString() ?: ""; fDurFin = t.duracion.fin?.toString() ?: ""
        fAlertas = t.alertas
    }

    fun elegirMed(m: Medicamentos.Med) {
        fMed = m; fBusca = ""
        cfg?.let { k -> fUnidad = Medicamentos.unidadPorDefecto(k, m) ?: fUnidad }   // predeterminada del catálogo, editable
    }

    /** Al cambiar la modalidad, las veces o el intervalo: horarios sugeridos (editables). */
    fun sugerir() {
        val k = cfg ?: return; val m = fModalidad ?: return
        fHorarios.clear()
        if (m == Medicamentos.EVENTOS) return
        val veces = fVeces.toIntOrNull(); val h = fIntervaloH.toIntOrNull()
        if (m == Medicamentos.VECES_DIA && (veces == null || veces !in 1..12)) return
        val s = Medicamentos.sugerir(k, m, veces, h)
        fHorarios.addAll(if (m == Medicamentos.INTERVALO_HORAS || m == Medicamentos.INTERVALO_DIAS) s.take(1) else s)
    }

    fun elegirModalidad(m: String) {
        fModalidad = m
        // Excluyentes: al cambiar de modalidad se borran los datos de la otra.
        if (m != Medicamentos.VECES_DIA) fVeces = ""; if (m != Medicamentos.INTERVALO_HORAS) fIntervaloH = ""; if (m != Medicamentos.INTERVALO_DIAS) fIntervaloD = ""
        if (m != Medicamentos.DIAS_SEMANA) fDias.clear()
        if (m != Medicamentos.CICLOS) { fToma = ""; fDescanso = ""; fCiclos = "" }
        if (m != Medicamentos.EVENTOS) fEventos.clear()
        sugerir()
    }

    fun alternarEvento(e: Medicamentos.EventoCfg) {
        val i = fEventos.indexOfFirst { it.evento == e.id }
        if (i >= 0) { fEventos.removeAt(i); return }
        if (e.id == Medicamentos.SEGUN_NECESIDAD) fEventos.clear() else fEventos.removeAll { it.evento == Medicamentos.SEGUN_NECESIDAD }
        fEventos.add(Medicamentos.Toma(e.id, e.hora))
    }

    private fun armar(k: Medicamentos.Config): Pair<Medicamentos.Tratamiento?, List<String>> {
        val e = mutableListOf<String>()
        val m = fMed
        if (fPersona == null) e.add("Elige la persona: Héctor o Majo.")
        if (m == null) e.add("Elige el medicamento en el buscador.")
        val inicio = runCatching { LocalDate.parse(fInicio.trim()) }.getOrNull()
        if (inicio == null) e.add("Escribe la fecha de inicio como AAAA-MM-DD.")
        if (fModalidad == null) e.add("Elige una modalidad de frecuencia.")
        val mod = fModalidad
        val f = Medicamentos.Frecuencia(mod ?: "",
            veces = fVeces.toIntOrNull().takeIf { mod == Medicamentos.VECES_DIA },
            intervaloHoras = fIntervaloH.toIntOrNull().takeIf { mod == Medicamentos.INTERVALO_HORAS },
            intervaloDias = fIntervaloD.toIntOrNull().takeIf { mod == Medicamentos.INTERVALO_DIAS },
            dias = if (mod == Medicamentos.DIAS_SEMANA) fDias.sorted() else emptyList(),
            diasToma = fToma.toIntOrNull().takeIf { mod == Medicamentos.CICLOS }, diasDescanso = fDescanso.toIntOrNull().takeIf { mod == Medicamentos.CICLOS },
            ciclosMax = fCiclos.toIntOrNull().takeIf { mod == Medicamentos.CICLOS },
            horarios = if (mod == Medicamentos.EVENTOS) emptyList() else fHorarios.map { it.trim() }.let { l -> if (mod == Medicamentos.VECES_DIA) l.sorted() else l },
            eventos = if (mod == Medicamentos.EVENTOS) fEventos.toList() else emptyList())
        val dur = Medicamentos.Duracion(fDur, fDurDias.toIntOrNull().takeIf { fDur == Medicamentos.DUR_DIAS },
            runCatching { LocalDate.parse(fDurFin.trim()) }.getOrNull().takeIf { fDur == Medicamentos.DUR_FECHA })
        if (e.isNotEmpty() || m == null || inicio == null) return null to e
        val cant = Medicamentos.leerCantidad(fCantidad)
        val t = Medicamentos.Tratamiento(anterior?.id ?: Medicamentos.nuevoId(Instant.now()), fPersona!!, m, cant ?: 0.0, fUnidad ?: "", f, inicio, dur, fAlertas)
        val v = Medicamentos.validar(t, k).toMutableList()
        if (cant == null) { v.removeAll { it.startsWith("La cantidad") }; v.add(0, Medicamentos.validarDosis(k, null, fUnidad, m.fraccionable)!!) }
        return (if (v.isEmpty()) t else null) to v
    }

    fun guardar() {
        val k = cfg ?: return
        val (t, e) = armar(k)
        errores = e
        if (t == null) return
        val g = Medicamentos.guardar(t, anterior, Instant.now())
        try { base.put(Medicamentos.rutaTratamiento(g.id), Medicamentos.tratamientoAJson(g)) }
        catch (x: Exception) { errores = listOf("No se pudo guardar: ${x.javaClass.simpleName}"); return }
        runCatching { AvisosMedicamentos.reprogramar(c) }
        val p = Medicamentos.proxima(g, ahora())
        mensaje = "ok" to ((if (anterior == null) "Tratamiento guardado." else "Cambios guardados (versión ${g.version}); rigen desde ahora y el historial no cambia.") +
            (p?.let { " Primera administración: ${it.local.toLocalDate()} a las ${it.local.toLocalTime()}." } ?: if (g.frecuencia.segunNecesidad) " Según necesidad: sin alertas." else " No quedan administraciones en el período.") +
            (if (!g.alertas) " Alertas apagadas." else ""))
        vista = "lista"; cargar(); alCambiar()
    }

    fun terminarHoy(t: Medicamentos.Tratamiento) {
        val fin = maxOf(hoy(), t.inicio)
        val g = Medicamentos.guardar(t.copy(duracion = Medicamentos.Duracion(Medicamentos.DUR_FECHA, fin = fin)), t, Instant.now())
        base.put(Medicamentos.rutaTratamiento(g.id), Medicamentos.tratamientoAJson(g))
        runCatching { AvisosMedicamentos.reprogramar(c) }
        mensaje = "ok" to "El tratamiento termina el $fin. No se generan más alertas después de esa fecha."
        cargar(); alCambiar()
    }

    // ---------- O-40 (Héctor, 16-oct): archivar y borrar ----------
    var confirmar by mutableStateOf<Pair<String, String>?>(null)   // (tratamiento, "archivar" | "borrar")
    var verArchivados by mutableStateOf(false)

    /** Lo oculta y deja de avisar; el historial se conserva. */
    fun archivar(t: Medicamentos.Tratamiento) {
        confirmar = null
        base.put(Medicamentos.rutaTratamiento(t.id), Medicamentos.tratamientoAJson(t.copy(archivado = true)))
        AvisosMedicamentos.quitarNotificaciones(c, t.id)
        runCatching { AvisosMedicamentos.reprogramar(c) }
        mensaje = "ok" to "Tratamiento archivado: ya no avisa y su historial se conserva."
        cargar(); alCambiar()
    }

    /** Solo sin historial (si tiene, se archiva). */
    fun borrar(t: Medicamentos.Tratamiento) {
        confirmar = null
        if (!Medicamentos.puedeBorrar(t, AvisosMedicamentos.registros(base))) { mensaje = "err" to "Tiene historial: no se puede borrar. Puedes archivarlo."; return }
        base.borrar(Medicamentos.rutaTratamiento(t.id))
        AvisosMedicamentos.quitarNotificaciones(c, t.id)
        runCatching { AvisosMedicamentos.reprogramar(c) }
        mensaje = "ok" to "Tratamiento borrado."
        cargar(); alCambiar()
    }
}

@Composable private fun Titulo(t: String) = Text(t, style = MaterialTheme.typography.titleMedium)
@Composable private fun Gris(t: String) = Text(t, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
@Composable private fun Nota(t: String, error: Boolean = false) = Card(Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)) { Text(t, Modifier.padding(12.dp)) }

@Composable
private fun <T> Opciones(valores: List<T>, elegido: (T) -> Boolean, texto: (T) -> String, elegir: (T) -> Unit) =
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (v in valores) {
            if (elegido(v)) Button(onClick = { elegir(v) }) { Text(texto(v), style = MaterialTheme.typography.labelMedium) }
            else OutlinedButton(onClick = { elegir(v) }) { Text(texto(v), style = MaterialTheme.typography.labelMedium) }
        }
    }

@Composable
private fun Numero(valor: String, etiqueta: String, cambiar: (String) -> Unit) = OutlinedTextField(value = valor, onValueChange = { cambiar(it.take(6)) }, singleLine = true,
    label = { Text(etiqueta) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())

@Composable
fun PantallaMedicamentos(m: Medi) {
    androidx.compose.runtime.LaunchedEffect(Unit) { m.cargarCatalogo() }
    @Suppress("UNUSED_VARIABLE") val t = m.tick
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (m.vista) {
            "form" -> Formulario(m)
            "historial" -> Historial(m)
            else -> Lista(m)
        }
        Text(Saludo.version(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.COMMIT), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Lista(m: Medi) {
    val cfg = m.cfg
    Text("Medicamentos", style = MaterialTheme.typography.headlineSmall)
    Gris("Registra lo que indica la receta o tu médico. La app no recomienda dosis, horarios ni cambios de medicación.")
    m.mensaje?.let { (tipo, texto) -> Nota(texto, tipo == "err") }
    if (m.catalogo == null && !m.hayCatalogo()) Nota("El catálogo de medicamentos llega con el plan Génesis. Impórtalo en Importar → Importar plan completo.", true)

    m.porResponder?.let { (tid, local, evento) ->
        val tr = m.tratamientos.firstOrNull { it.id == tid }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Titulo("Responder la toma de las ${local.toLocalTime()}")
                if (tr != null) Text("${Medicamentos.PERSONAS[tr.persona]} · ${tr.med.nombre} · ${tr.dosisTexto}" + (evento?.let { e -> " · " + (cfg?.evento(e)?.nombre ?: e) } ?: ""))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { m.responder(tid, local, evento, Medicamentos.CONFIRMADA, null) }) { Text("Confirmar") }
                    OutlinedButton(onClick = { m.responder(tid, local, evento, Medicamentos.OMITIDA, null) }) { Text("Omitir") }
                }
                Text("Posponer:")
                Opciones(Medicamentos.POSTERGAR, { false }, { "$it min" }) { min -> m.responder(tid, local, evento, Medicamentos.POSTERGADA, min) }
                TextButton(onClick = { m.porResponder = null }) { Text("Responder después") }
            }
        }
    }

    Opciones(listOf<String?>(null) + Medicamentos.PERSONAS.keys, { it == m.filtro }, { it?.let { p -> Medicamentos.PERSONAS[p] } ?: "Las dos" }) { m.filtro = it }
    val todos = m.tratamientos.filter { m.filtro == null || it.persona == m.filtro }
    val lista = todos.filter { !it.archivado }
    val archivados = todos.filter { it.archivado }
    val hoy = m.hoy(); val ahora = m.ahora()

    HorizontalDivider(); Titulo("Hoy")
    val porClave = AvisosMedicamentos.porClave(m.registros)
    val tomas = lista.flatMap { tr -> Medicamentos.ocurrencias(tr, hoy.atStartOfDay(ahora.zone).minusNanos(1), hoy.plusDays(1).atStartOfDay(ahora.zone).minusNanos(1), ahora.zone).map { tr to it } }
        .sortedBy { it.second.cuando.toInstant() }
    if (tomas.isEmpty()) Gris("No hay tomas programadas para hoy.")
    for ((tr, oc) in tomas) {
        val u = Medicamentos.ultima(porClave[Medicamentos.clave(tr.id, oc.local)] ?: emptyList())
        val estado = u?.let { Medicamentos.ESTADOS[it.estado] } ?: if (oc.cuando.isAfter(ahora)) "Pendiente" else "Sin responder"
        Column(Modifier.fillMaxWidth()) {
            Text("${oc.local.toLocalTime()} · ${Medicamentos.PERSONAS[tr.persona]} · ${tr.med.nombre} · ${tr.dosisTexto}", fontWeight = FontWeight.SemiBold)
            Gris(estado + (oc.evento?.let { e -> " · " + (cfg?.evento(e)?.nombre ?: e) } ?: ""))
            if (u == null || u.estado == Medicamentos.POSTERGADA) TextButton(onClick = { m.porResponder = Triple(tr.id, oc.local, oc.evento) }) { Text("Responder") }
        }
    }

    HorizontalDivider()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { m.nuevo() }) { Text("Nuevo tratamiento") }
        OutlinedButton(onClick = { m.historialDe = null; m.vista = "historial" }) { Text("Historial") }
    }
    if (lista.isEmpty()) Gris("No hay tratamientos registrados.")
    for (tr in lista) {
        val vencido = tr.vencido(hoy)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr.med.nombre, fontWeight = FontWeight.SemiBold)
                Gris("${Medicamentos.PERSONAS[tr.persona]} · ids ${tr.med.ids.joinToString(", ")}")
                Text("${tr.dosisTexto} · ${Medicamentos.resumen(tr.frecuencia, cfg)}")
                Gris("Desde ${tr.inicio} · " + (tr.fin?.let { "hasta $it" } ?: "indefinido") + (if (vencido) " · TERMINADO" else "") +
                    " · alertas " + (if (tr.alertas) "activas" else "apagadas") + " · versión ${tr.version}")
                if (!vencido) Medicamentos.proxima(tr, ahora)?.let { Gris("Próxima: ${it.local.toLocalDate()} ${it.local.toLocalTime()}") }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { m.editar(tr) }) { Text("Editar") }
                    OutlinedButton(onClick = { m.historialDe = tr.id; m.vista = "historial" }) { Text("Historial") }
                    if (!vencido) OutlinedButton(onClick = { m.registrarAhora(tr) }) { Text("Registrar toma ahora") }
                    if (!vencido) OutlinedButton(onClick = { m.terminarHoy(tr) }) { Text("Terminar hoy") }
                    OutlinedButton(onClick = { m.confirmar = tr.id to "archivar" }) { Text("Archivar") }
                    if (Medicamentos.puedeBorrar(tr, m.registros)) OutlinedButton(onClick = { m.confirmar = tr.id to "borrar" }) { Text("Borrar") }
                }
                if (m.confirmar?.first == tr.id) {
                    val borrar = m.confirmar?.second == "borrar"
                    Nota(if (borrar) "¿Borrar este tratamiento? No tiene historial y no se puede deshacer."
                        else "¿Archivar este tratamiento? Deja de avisar y se oculta de la lista; su historial se conserva.", borrar)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (borrar) m.borrar(tr) else m.archivar(tr) }) { Text(if (borrar) "Sí, borrar" else "Sí, archivar") }
                        OutlinedButton(onClick = { m.confirmar = null }) { Text("Cancelar") }
                    }
                }
            }
        }
    }
    // O-40: los archivados se ven aparte, con su historial.
    if (archivados.isNotEmpty()) {
        HorizontalDivider()
        TextButton(onClick = { m.verArchivados = !m.verArchivados }) { Text((if (m.verArchivados) "Ocultar" else "Ver") + " archivados (${archivados.size})") }
        if (m.verArchivados) for (tr in archivados) Column(Modifier.fillMaxWidth()) {
            Text(tr.med.nombre, fontWeight = FontWeight.SemiBold)
            Gris("${Medicamentos.PERSONAS[tr.persona]} · ${tr.dosisTexto} · ${Medicamentos.resumen(tr.frecuencia, cfg)} · archivado")
            TextButton(onClick = { m.historialDe = tr.id; m.vista = "historial" }) { Text("Historial") }
        }
    }
}

@Composable
private fun Formulario(m: Medi) {
    val cfg = m.cfg ?: return
    Text(if (m.anterior == null) "Nuevo tratamiento" else "Editar tratamiento", style = MaterialTheme.typography.headlineSmall)
    if (m.anterior != null) Gris("Los cambios rigen desde ahora para las próximas tomas. El historial no cambia.")

    Titulo("Persona")
    Opciones(Medicamentos.PERSONAS.keys.toList(), { it == m.fPersona }, { Medicamentos.PERSONAS.getValue(it) }) { m.fPersona = it }

    HorizontalDivider(); Titulo("Medicamento")
    m.fMed?.let { med ->
        Text(med.nombre, fontWeight = FontWeight.SemiBold)
        Gris("ids ${med.ids.joinToString(", ")}" + (med.tag?.let { " · $it" } ?: ""))
    }
    val cat = m.catalogo
    if (cat == null) Gris(if (m.hayCatalogo()) "Cargando el catálogo…" else "Falta el catálogo: impórtalo con el plan Génesis.")
    else {
        OutlinedTextField(value = m.fBusca, onValueChange = { m.fBusca = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(if (m.fMed == null) "Buscar medicamento" else "Cambiar medicamento") })
        if (m.fBusca.isNotBlank()) {
            val (res, total) = androidx.compose.runtime.remember(m.fBusca, cat) { cat.buscar(m.fBusca, 100) }
            Gris(if (total == 0) "Sin resultados." else if (total > res.size) "Mostrando ${res.size} de $total: escribe más letras para acotar." else "$total resultado${if (total == 1) "" else "s"}.")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(res) { med ->
                    Column(Modifier.fillMaxWidth().clickable { m.elegirMed(med) }.padding(vertical = 8.dp)) {
                        Text(med.nombre)
                        Gris("ids ${med.ids.joinToString(", ")}" + (med.tag?.let { " · $it" } ?: ""))
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    HorizontalDivider(); Titulo("Dosis")
    OutlinedTextField(value = m.fCantidad, onValueChange = { m.fCantidad = it.take(8) }, singleLine = true, label = { Text("Cantidad (por ejemplo 1 o 0,5)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = m.fCantidad.isNotBlank() && Medicamentos.leerCantidad(m.fCantidad) == null,
        modifier = Modifier.fillMaxWidth())
    val sugeridas = Medicamentos.unidadesDe(cfg, m.fMed)
    val otras = cfg.unidades.map { it.id }.filter { it !in sugeridas }
    if (sugeridas.isNotEmpty()) { Gris("Del catálogo:"); Opciones(sugeridas, { it == m.fUnidad }, { cfg.unidad(it)?.nombre ?: it }) { m.fUnidad = it } }
    Gris(if (sugeridas.isEmpty()) "Unidad:" else "Otra unidad:")
    Opciones(otras, { it == m.fUnidad }, { cfg.unidad(it)?.nombre ?: it }) { m.fUnidad = it }
    val paso = Medicamentos.paso(cfg, m.fMed?.fraccionable, m.fUnidad)
    Gris("Fraccionable según el catálogo: ${m.fMed?.fraccionable ?: "sin dato"} · se aceptan múltiplos de ${Medicamentos.numeroTexto(paso)}.")

    HorizontalDivider(); Titulo("Frecuencia (elige una)")
    Opciones(Medicamentos.MODALIDADES.keys.toList(), { it == m.fModalidad }, { Medicamentos.MODALIDADES.getValue(it) }) { m.elegirModalidad(it) }
    when (m.fModalidad) {
        Medicamentos.VECES_DIA -> Numero(m.fVeces, "Veces al día") { m.fVeces = it; m.sugerir() }
        Medicamentos.INTERVALO_HORAS -> Numero(m.fIntervaloH, "Cada cuántas horas") { m.fIntervaloH = it; m.sugerir() }
        Medicamentos.INTERVALO_DIAS -> Numero(m.fIntervaloD, "Cada cuántos días") { m.fIntervaloD = it }
        Medicamentos.DIAS_SEMANA -> Opciones((1..7).toList(), { it in m.fDias }, { Medicamentos.DIAS[it - 1].take(3) }) { d -> if (d in m.fDias) m.fDias.remove(d) else m.fDias.add(d) }
        Medicamentos.CICLOS -> {
            Numero(m.fToma, "Días de toma") { m.fToma = it }
            Numero(m.fDescanso, "Días de descanso") { m.fDescanso = it }
            Numero(m.fCiclos, "Cantidad de ciclos (vacío = indefinido)") { m.fCiclos = it }
        }
        Medicamentos.EVENTOS -> {
            Opciones(cfg.eventos, { e -> m.fEventos.any { it.evento == e.id } }, { it.nombre }) { m.alternarEvento(it) }
            m.fEventos.forEachIndexed { i, tm ->
                if (tm.evento != Medicamentos.SEGUN_NECESIDAD) OutlinedTextField(value = tm.hora ?: "", onValueChange = { m.fEventos[i] = tm.copy(hora = it.take(5)) }, singleLine = true,
                    label = { Text("Hora: ${cfg.evento(tm.evento)?.nombre ?: tm.evento} (HH:MM)") }, isError = !Medicamentos.horaValida(tm.hora), modifier = Modifier.fillMaxWidth())
                else Gris("Según necesidad: sin horario ni alertas. Registra cada toma con \"Registrar toma ahora\".")
            }
        }
    }
    if (m.fModalidad != null && m.fModalidad != Medicamentos.EVENTOS) {
        val unaHora = m.fModalidad == Medicamentos.INTERVALO_HORAS || m.fModalidad == Medicamentos.INTERVALO_DIAS
        Text(if (unaHora) "Hora de la toma (la primera, si es cada N horas):" else "Horarios:")
        m.fHorarios.forEachIndexed { i, h ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = h, onValueChange = { m.fHorarios[i] = it.take(5) }, singleLine = true, label = { Text("HH:MM") },
                    isError = !Medicamentos.horaValida(h), modifier = Modifier.weight(1f))
                if (!unaHora && m.fModalidad != Medicamentos.VECES_DIA) TextButton(onClick = { m.fHorarios.removeAt(i) }) { Text("Quitar") }
            }
        }
        if (m.fModalidad == Medicamentos.DIAS_SEMANA || m.fModalidad == Medicamentos.CICLOS || (unaHora && m.fHorarios.isEmpty()))
            OutlinedButton(onClick = { m.fHorarios.add(cfg.horaPorDefecto) }) { Text("Agregar horario") }
        if (m.fModalidad == Medicamentos.INTERVALO_HORAS) m.fIntervaloH.toIntOrNull()?.let { h ->
            m.fHorarios.firstOrNull()?.let { ini -> if (Medicamentos.horaValida(ini)) Gris("En el día: " + Medicamentos.horasDelIntervalo(ini, h).joinToString(", ")) }
        }
        OutlinedButton(onClick = { m.sugerir() }) { Text("Volver a sugerir horarios") }
    }
    if (m.fModalidad != null) Gris(Medicamentos.AVISO_SUGERENCIA)

    HorizontalDivider(); Titulo("Inicio y duración")
    OutlinedTextField(value = m.fInicio, onValueChange = { m.fInicio = it.take(10) }, singleLine = true, label = { Text("Fecha de inicio (AAAA-MM-DD)") }, modifier = Modifier.fillMaxWidth())
    Opciones(listOf(Medicamentos.DUR_DIAS, Medicamentos.DUR_FECHA, Medicamentos.DUR_INDEFINIDA), { it == m.fDur },
        { mapOf(Medicamentos.DUR_DIAS to "Cantidad de días", Medicamentos.DUR_FECHA to "Fecha de término", Medicamentos.DUR_INDEFINIDA to "Indefinida").getValue(it) }) { m.fDur = it }
    when (m.fDur) {
        Medicamentos.DUR_DIAS -> {
            Numero(m.fDurDias, "Cantidad de días") { m.fDurDias = it }
            val ini = runCatching { LocalDate.parse(m.fInicio.trim()) }.getOrNull(); val n = m.fDurDias.toIntOrNull()
            if (ini != null && n != null && n > 0) Gris("Termina el ${Medicamentos.fechaFin(ini, Medicamentos.Duracion(Medicamentos.DUR_DIAS, n))}.")
        }
        Medicamentos.DUR_FECHA -> OutlinedTextField(value = m.fDurFin, onValueChange = { m.fDurFin = it.take(10) }, singleLine = true,
            label = { Text("Fecha de término (AAAA-MM-DD)") }, modifier = Modifier.fillMaxWidth())
    }

    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = m.fAlertas, onCheckedChange = { m.fAlertas = it }); Text("Alertas de este tratamiento")
    }
    Gris("Sin respuesta, la alerta se repite cada ${Medicamentos.REPETIR_MIN} minutos durante ${Medicamentos.VENTANA_MIN / 60} horas. El tono y el volumen se eligen en Avisos → Medicamentos.")
    if (m.errores.isNotEmpty()) Nota(m.errores.joinToString("\n") { "• $it" }, true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { m.guardar() }) { Text("Guardar") }
        OutlinedButton(onClick = { m.vista = "lista"; m.errores = emptyList() }) { Text("Cancelar") }
    }
}

@Composable
private fun Historial(m: Medi) {
    val tr = m.historialDe?.let { id -> m.tratamientos.firstOrNull { it.id == id } }
    Text("Historial", style = MaterialTheme.typography.headlineSmall)
    Gris(tr?.let { "${it.med.nombre} · ${Medicamentos.PERSONAS[it.persona]}" } ?: "Todos los tratamientos" + (m.filtro?.let { " de ${Medicamentos.PERSONAS[it]}" } ?: ""))
    OutlinedButton(onClick = { m.vista = "lista" }) { Text("Volver") }
    val lista = m.registros.filter { r -> (tr == null || r.tratamientoId == tr.id) && (m.filtro == null || tr != null || (r.data["persona"] as? kotlinx.serialization.json.JsonPrimitive)?.content == m.filtro) }
        .sortedWith(compareByDescending<Medicamentos.Registro> { it.programada }.thenByDescending { it.respondidaEn })
    if (lista.isEmpty()) Gris("Todavía no hay registros.")
    val zona = AvisosMedicamentos.zona()
    for (r in lista.take(300)) {
        val snap = r.data["tratamiento"] as? kotlinx.serialization.json.JsonObject
        val med = ((snap?.get("medicamento") as? kotlinx.serialization.json.JsonObject)?.get("nombre") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        val dosis = (snap?.get("dosis") as? kotlinx.serialization.json.JsonObject)?.let { d ->
            ((d["cantidad"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.let { Medicamentos.numeroTexto(it) } ?: "") + " " + ((d["unidad"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "")
        } ?: ""
        val pos = (r.data["postergacion_min"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it != "null" }
        Column(Modifier.fillMaxWidth()) {
            Text("${r.programada.toLocalDate()} ${r.programada.toLocalTime()} · ${Medicamentos.ESTADOS[r.estado] ?: r.estado}" + (pos?.let { " $it min" } ?: ""), fontWeight = FontWeight.SemiBold)
            Gris("$med · $dosis · respondida ${r.respondidaEn.atZone(zona).toLocalDateTime().withSecond(0).withNano(0).toString().replace('T', ' ')}" +
                " · versión ${(snap?.get("version") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "?"}")
        }
    }
}
