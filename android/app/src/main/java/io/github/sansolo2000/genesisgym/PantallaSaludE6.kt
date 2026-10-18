package io.github.sansolo2000.genesisgym

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import kotlin.reflect.KClass

/**
 * Etapa E6 — pestaña Salud: lee una semana de Health Connect (sábado a viernes) y sube al Drive de Héctor los dos CSV
 * del Evaluador. Nada se guarda en la base de la app ni en el respaldo: se lee, se arma el CSV y se sube.
 * Durante 2 semanas convive con el script de Garmin, para comparar (estado-genesis v35).
 */
class Hc(private val c: Context) {
    var tick by mutableIntStateOf(0)
    var desde by mutableStateOf(SaludCsv.semanaPorDefecto(LocalDate.now(Gimnasio.ZONA)))
    var concedidos by mutableStateOf<Set<String>>(emptySet())
    var leyendo by mutableStateOf(false)
    var enviando by mutableStateOf("")
    var mensaje by mutableStateOf<Pair<String, String>?>(null)
    var diario by mutableStateOf<List<SaludCsv.FilaDia>?>(null)
    var actividades by mutableStateOf<List<SaludCsv.FilaActividad>?>(null)
    var avisos by mutableStateOf<List<String>>(emptyList())

    val tipos: List<Pair<String, KClass<out Record>>> = listOf(
        "FC en reposo" to RestingHeartRateRecord::class, "Sueño" to SleepSessionRecord::class, "Peso" to WeightRecord::class,
        "Ejercicio" to ExerciseSessionRecord::class, "Distancia" to DistanceRecord::class, "Desnivel" to ElevationGainedRecord::class,
        "Frecuencia cardíaca" to HeartRateRecord::class)
    val permisos: Set<String> = tipos.map { HealthPermission.getReadPermission(it.second) }.toSet()
    fun tiene(k: KClass<out Record>) = HealthPermission.getReadPermission(k) in concedidos

    fun disponible(): Boolean = HealthConnectClient.getSdkStatus(c) == HealthConnectClient.SDK_AVAILABLE
    private fun cliente() = HealthConnectClient.getOrCreate(c)

    suspend fun refrescarPermisos() { concedidos = runCatching { cliente().permissionController.getGrantedPermissions() }.getOrDefault(emptySet()); tick++ }

    /** Entrega 2 (CONTEXTO-DATOS-DIARIOS §11.1.2, aprobado por Héctor, R2): lectura de Health Connect en segundo plano. */
    companion object { const val PERMISO_SEGUNDO_PLANO = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" }
    fun segundoPlano(): Boolean = PERMISO_SEGUNDO_PLANO in concedidos

    fun mover(n: Long) {
        val nuevo = desde.plusDays(7 * n)
        if (nuevo.isAfter(SaludCsv.sabadoDe(LocalDate.now(Gimnasio.ZONA)))) return
        desde = nuevo; diario = null; actividades = null; mensaje = null; avisos = emptyList()
    }

    private suspend inline fun <reified T : Record> leerTodo(filtro: TimeRangeFilter): List<T> {
        val l = mutableListOf<T>(); var pagina: String? = null
        do { val r = cliente().readRecords(ReadRecordsRequest(T::class, filtro, pageToken = pagina)); l.addAll(r.records); pagina = r.pageToken } while (pagina != null)
        return l
    }

    suspend fun leer() {
        leyendo = true; mensaje = null; avisos = emptyList()
        val zona = Gimnasio.ZONA
        val ini = desde.minusDays(1).atStartOfDay(zona).toInstant()          // el sueño de la noche del viernes previo termina el sábado
        val fin = desde.plusDays(7).atStartOfDay(zona).toInstant()
        val filtro = TimeRangeFilter.between(ini, fin)
        val av = mutableListOf<String>()
        try {
            val fc = if (tiene(RestingHeartRateRecord::class)) leerTodo<RestingHeartRateRecord>(filtro).map { SaludCsv.Fc(it.time, it.beatsPerMinute, it.metadata.dataOrigin.packageName) } else { av.add("Sin permiso de FC en reposo: esa columna va vacía."); emptyList() }
            val su = if (tiene(SleepSessionRecord::class)) leerTodo<SleepSessionRecord>(filtro).map { s ->
                SaludCsv.Sueno(s.startTime, s.endTime, s.stages.map { SaludCsv.Etapa(it.startTime, it.endTime, it.stage) }, s.metadata.dataOrigin.packageName) } else { av.add("Sin permiso de sueño: esas columnas van vacías."); emptyList() }
            val pe = if (tiene(WeightRecord::class)) leerTodo<WeightRecord>(filtro).map { SaludCsv.Peso(it.time, it.weight.inKilograms, it.metadata.dataOrigin.packageName) } else { av.add("Sin permiso de peso: esa columna va vacía."); emptyList() }
            val ej = if (tiene(ExerciseSessionRecord::class)) leerTodo<ExerciseSessionRecord>(filtro) else { av.add("Sin permiso de ejercicio: el CSV de actividades va sin filas."); emptyList() }
            val acts = ej.map { e ->
                val f = TimeRangeFilter.between(e.startTime, e.endTime)
                suspend fun <T : Any> uno(m: androidx.health.connect.client.aggregate.AggregateMetric<T>, k: KClass<out Record>): T? =
                    if (!tiene(k)) null else runCatching { cliente().aggregate(AggregateRequest(setOf(m), f))[m] }.getOrNull()
                SaludCsv.Actividad(e.startTime, e.endTime, e.exerciseType, e.title,
                    uno(DistanceRecord.DISTANCE_TOTAL, DistanceRecord::class)?.inMeters,
                    uno(ElevationGainedRecord.ELEVATION_GAINED_TOTAL, ElevationGainedRecord::class)?.inMeters,
                    uno(HeartRateRecord.BPM_AVG, HeartRateRecord::class)?.toDouble(), e.metadata.dataOrigin.packageName)
            }
            if (ej.isNotEmpty()) for ((n, k) in listOf("distancia" to DistanceRecord::class, "desnivel" to ElevationGainedRecord::class, "FC media" to HeartRateRecord::class))
                if (!tiene(k)) av.add("Sin permiso de $n: esa columna de actividades va vacía.")
            diario = SaludCsv.diario(desde, fc, su, pe, zona)
            actividades = SaludCsv.actividades(desde, acts, zona)
            if (pe.isNotEmpty() && pe.size < 2) av.add("Health Connect trae solo ${pe.size} peso en la semana: revisa que movinglife escriba en Health Connect (pendiente de E6).")
            avisos = av
        } catch (e: Exception) {
            mensaje = "err" to "No se pudo leer Health Connect (${e.javaClass.simpleName})."
        } finally { leyendo = false; tick++ }
    }

    /** Datos diarios: lo leído de Health Connect en un rango, sin tocar la semana que muestra la pantalla. */
    class Crudo(val fc: List<SaludCsv.Fc>, val sueno: List<SaludCsv.Sueno>, val peso: List<SaludCsv.Peso>, val actividades: List<SaludCsv.Actividad>,
                val concedidos: Set<String>)

    /** Permisos concedidos, con los nombres del manifest (DatosDiarios.PERMISOS va en el mismo orden que [tipos]). */
    fun concedidosDd(): Set<String> = tipos.zip(DatosDiarios.PERMISOS).filter { tiene(it.first.second) }.map { it.second }.toSet()

    suspend fun leerRango(ini: Instant, fin: Instant): Crudo {
        refrescarPermisos()
        val filtro = TimeRangeFilter.between(ini, fin)
        val fc = if (tiene(RestingHeartRateRecord::class)) leerTodo<RestingHeartRateRecord>(filtro).map { SaludCsv.Fc(it.time, it.beatsPerMinute, it.metadata.dataOrigin.packageName) } else emptyList()
        val su = if (tiene(SleepSessionRecord::class)) leerTodo<SleepSessionRecord>(filtro).map { s ->
            SaludCsv.Sueno(s.startTime, s.endTime, s.stages.map { SaludCsv.Etapa(it.startTime, it.endTime, it.stage) }, s.metadata.dataOrigin.packageName) } else emptyList()
        val pe = if (tiene(WeightRecord::class)) leerTodo<WeightRecord>(filtro).map { SaludCsv.Peso(it.time, it.weight.inKilograms, it.metadata.dataOrigin.packageName) } else emptyList()
        val ej = if (tiene(ExerciseSessionRecord::class)) leerTodo<ExerciseSessionRecord>(filtro) else emptyList()
        val acts = ej.map { e ->
            val f = TimeRangeFilter.between(e.startTime, e.endTime)
            suspend fun <T : Any> uno(m: androidx.health.connect.client.aggregate.AggregateMetric<T>, k: KClass<out Record>): T? =
                if (!tiene(k)) null else runCatching { cliente().aggregate(AggregateRequest(setOf(m), f))[m] }.getOrNull()
            SaludCsv.Actividad(e.startTime, e.endTime, e.exerciseType, e.title,
                uno(DistanceRecord.DISTANCE_TOTAL, DistanceRecord::class)?.inMeters,
                uno(ElevationGainedRecord.ELEVATION_GAINED_TOTAL, ElevationGainedRecord::class)?.inMeters,
                uno(HeartRateRecord.BPM_AVG, HeartRateRecord::class)?.toDouble(), e.metadata.dataOrigin.packageName)
        }
        return Crudo(fc, su, pe, acts, concedidosDd())
    }

    private val prefs get() = c.getSharedPreferences("e6", Context.MODE_PRIVATE)
    fun enviados(): Int = prefs.getInt(desde.toString(), 0)

    suspend fun subir(token: String, perfil: String?) {
        val d = diario ?: return; val a = actividades ?: return
        if (enviando.isNotEmpty()) return
        val v = enviados() + 1
        val p = SaludCsv.slug(perfil)
        try {
            enviando = "Preparando carpeta…"
            val carpeta = DriveApi.carpetaPropia(token, SaludCsv.CARPETA)
            enviando = "Subiendo 1 de 2…"
            val n1 = SaludCsv.nombre(p, "diario", desde, v)
            DriveApi.subirArchivo(token, carpeta, n1, "text/csv", SaludCsv.csvDiario(d, Gimnasio.ZONA).toByteArray(Charsets.UTF_8))
            enviando = "Subiendo 2 de 2…"
            val n2 = SaludCsv.nombre(p, "actividades", desde, v)
            DriveApi.subirArchivo(token, carpeta, n2, "text/csv", SaludCsv.csvActividades(a).toByteArray(Charsets.UTF_8))
            prefs.edit().putInt(desde.toString(), v).apply()
            mensaje = "ok" to "Enviado: $n1 y $n2, en la carpeta \"${SaludCsv.CARPETA}\" de tu Drive."
        } catch (e: Exception) {
            val m = if (e is DriveApi.ErrorDrive) "Drive respondió ${e.codigo}" else (e.message ?: e.javaClass.simpleName)
            mensaje = "err" to "No se pudo enviar a Drive: $m. Vuelve a intentarlo con internet."
        } finally { enviando = ""; tick++ }
    }
}

interface AccionesHc {
    fun pedirPermisos()
    fun leer()
    fun enviar()
}

@Composable
fun PantallaSaludE6(h: Hc, a: AccionesHc, ci: Cin, ac: AccionesCintura, dd: Dd, ad: AccionesDd, conSalud: Boolean = true) {
    @Suppress("UNUSED_VARIABLE") val t = h.tick
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Multiusuario: sin el módulo Salud queda solo la subida de datos diarios (la vuelta de datos al Evaluador).
        if (!conSalud) {
            Text("Datos", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text("Sube a tu Drive lo que registraste en la app, para el Evaluador.", color = GRIS, fontSize = 14.sp)
            SeccionDatosDiarios(dd, ad)
            Text(Saludo.version(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.COMMIT), color = GRIS, fontSize = 12.sp)
            return@Column
        }
        Text("Salud", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Lee una semana de Health Connect y sube al Drive los 2 CSV del Evaluador: diario (FC en reposo, sueño y peso) y actividades. Si un dato no está, la celda va vacía. Las 2 primeras semanas se compara con el script de Garmin.",
            color = GRIS, fontSize = 14.sp)
        SeccionDatosDiarios(dd, ad)   // datos diarios, entrega 1
        SeccionCintura(ci, ac)   // M-07
        Text("Health Connect", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        if (!h.disponible()) { Text("Health Connect no está disponible en este celular.", color = AMBAR); return@Column }
        val faltan = h.tipos.filter { !h.tiene(it.second) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CAJA).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Permisos de lectura", fontWeight = FontWeight.SemiBold)
            for ((n, k) in h.tipos) Row { Text(n, Modifier.weight(1f)); Text(if (h.tiene(k)) "permitido" else "sin permiso", color = if (h.tiene(k)) VERDE else GRIS) }
            if (faltan.isNotEmpty()) OutlinedButton(onClick = { a.pedirPermisos() }) { Text("Pedir permisos") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { h.mover(-1) }) { Text("‹", fontSize = 24.sp) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Semana ${Comidas.fechaLarga(h.desde.toString())} a ${Comidas.fechaLarga(h.desde.plusDays(6).toString())}", fontWeight = FontWeight.SemiBold)
                if (h.enviados() > 0) Text("Ya enviada ${h.enviados()} ${if (h.enviados() == 1) "vez" else "veces"}: la próxima va como -v${h.enviados() + 1}", color = GRIS, fontSize = 12.sp)
            }
            TextButton(onClick = { h.mover(1) }) { Text("›", fontSize = 24.sp) }
        }
        Button(onClick = { a.leer() }, enabled = !h.leyendo, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (h.leyendo) "Leyendo…" else "Leer la semana") }
        h.mensaje?.let { (tipo, m) -> Text(m, color = if (tipo == "err") Color(0xFFE57373) else VERDE) }
        for (x in h.avisos) Text("• $x", color = AMBAR, fontSize = 13.sp)
        h.diario?.let { d ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CAJA).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Diario", fontWeight = FontWeight.SemiBold)
                Row { Text("Día", Modifier.weight(1.4f), color = GRIS, fontSize = 12.sp); Text("FC", Modifier.weight(0.6f), color = GRIS, fontSize = 12.sp); Text("Sueño", Modifier.weight(0.9f), color = GRIS, fontSize = 12.sp); Text("Peso", Modifier.weight(0.8f), color = GRIS, fontSize = 12.sp) }
                for (f in d) Row {
                    Text(Comidas.fechaLarga(f.fecha.toString()), Modifier.weight(1.4f), fontSize = 13.sp)
                    Text(f.fc?.toString() ?: "—", Modifier.weight(0.6f), fontSize = 13.sp)
                    Text(f.noche?.total?.let { "${it / 60} h ${it % 60}" } ?: "—", Modifier.weight(0.9f), fontSize = 13.sp)
                    Text(f.peso?.let { SaludCsv.numero(it.kg, 2) } ?: "—", Modifier.weight(0.8f), fontSize = 13.sp)
                }
            }
        }
        h.actividades?.let { l ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CAJA).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Actividades (${l.size})", fontWeight = FontWeight.SemiBold)
                if (l.isEmpty()) Text("Ninguna en Health Connect esta semana.", color = GRIS, fontSize = 13.sp)
                for (x in l) Text("${Comidas.fechaLarga(x.fecha.toString())} ${x.hora} · ${x.tipo} · ${x.duracion} min" +
                    (x.km?.let { " · ${SaludCsv.numero(it, 1)} km" } ?: "") + (x.desnivel?.let { " · ${SaludCsv.numero(it, 0)} m" } ?: ""), fontSize = 13.sp)
            }
            Button(onClick = { a.enviar() }, enabled = h.enviando.isEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VERDE, contentColor = Color(0xFF0E1F18))) {
                Text(if (h.enviando.isNotEmpty()) h.enviando else "Enviar los 2 CSV al Drive", fontWeight = FontWeight.Bold)
            }
        }
        Text(Saludo.version(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.COMMIT), color = GRIS, fontSize = 12.sp)
    }
}
