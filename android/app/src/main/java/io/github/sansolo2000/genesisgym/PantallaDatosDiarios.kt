package io.github.sansolo2000.genesisgym

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Datos diarios, entrega 1 (CONTEXTO-DATOS-DIARIOS v3 §11): "Subir ahora" en Salud y aviso "N días por subir" en Hoy.
 * Health Connect se lee con la app abierta, al tocar el botón. Lo ya subido (sha256 por día y tipo) queda en el celular,
 * en preferencias propias: no entra al respaldo.
 */
class Dd(private val c: Context, private val base: BaseLocal, private val hc: Hc) {
    var tick by mutableIntStateOf(0)
    var enviando by mutableStateOf("")
    var mensaje by mutableStateOf<Pair<String, String>?>(null)
    var carpetaNueva by mutableStateOf(false)
    var porSubir by mutableIntStateOf(0)
    var ayerAbierto by mutableStateOf(false)
    var permisosReducidos by mutableStateOf(false)   // §1.5.8

    private val prefs get() = c.getSharedPreferences("datos-diarios", Context.MODE_PRIVATE)
    private fun previo(d: LocalDate) = DatosDiarios.estadoDesdeJson(prefs.getString(d.toString(), null))
    private fun guardar(d: LocalDate, e: DatosDiarios.EstadoDia) { prefs.edit().putString(d.toString(), DatosDiarios.estadoAJson(e)).commit() }

    private fun sesiones() = base.coleccion("sesiones").map { it.data }
    private fun cinturas() = base.coleccion(Cintura.COLECCION).mapNotNull { Cintura.desdeJson(it.data) }
    /** D-09: cada día con el programa que lo cubre (el activo o uno guardado en programas_alimentacion). */
    private fun comidas(): (LocalDate) -> DatosDiarios.Archivo? {
        fun prog(o: kotlinx.serialization.json.JsonObject?) = o?.get("programa") as? kotlinx.serialization.json.JsonObject
        val activo = prog(base.get("alimentacion/programa_activo"))
        val guardados = base.coleccion("programas_alimentacion").mapNotNull { d ->
            prog(d.data)?.let { it to ((d.data["importado_en"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "") } }
        val inter = base.get("alimentacion/intercambios"); val regs = base.coleccion("comidas")
        return { d -> DatosDiarios.comidasDelDia(d, activo, guardados, inter, regs, previo(d)?.tipos?.containsKey("comidas") == true) }
    }

    /** Cuenta los días por subir sin leer Health Connect (eso se hace al tocar "Subir ahora"). */
    fun revisar() {
        val ahora = ZonedDateTime.now(DatosDiarios.ZONA)
        ayerAbierto = DatosDiarios.ayerAbierto(ahora)
        porSubir = runCatching {
            val ses = sesiones(); val cin = cinturas(); val com = comidas()
            DatosDiarios.porSubir(DatosDiarios.ventana(ahora), { d -> DatosDiarios.locales(ses, cin, com, d) }, { previo(it) })
        }.getOrDefault(0)
        tick++
    }

    suspend fun subir(token: String, perfilNombre: String?) {
        if (enviando.isNotEmpty()) return
        val ahora = ZonedDateTime.now(DatosDiarios.ZONA)
        val dias = DatosDiarios.ventana(ahora)
        val perfil = SaludCsv.slug(perfilNombre)
        mensaje = null; carpetaNueva = false; permisosReducidos = false
        val subidos = mutableListOf<LocalDate>()
        try {
            enviando = "Leyendo Health Connect…"
            val disponible = hc.disponible()
            var crudo: Hc.Crudo? = null; var error: String? = null
            if (disponible && dias.isNotEmpty()) try {
                crudo = hc.leerRango(dias.first().minusDays(1).atStartOfDay(DatosDiarios.ZONA).toInstant(), dias.last().plusDays(1).atStartOfDay(DatosDiarios.ZONA).toInstant())
            } catch (e: Exception) { error = "No se pudo leer Health Connect (${e.javaClass.simpleName})." }
            val concedidos = crudo?.concedidos ?: emptySet()
            val faltantes = DatosDiarios.faltantesHc(disponible, error, concedidos)
            val lectura = DatosDiarios.LecturaHc(if (crudo != null) ZonedDateTime.now(DatosDiarios.ZONA) else null, false, DatosDiarios.PERMISOS.filter { it !in concedidos })
            val ses = sesiones(); val cin = cinturas(); val com = comidas()
            var raiz: String? = null
            for (d in dias) {
                val p = previo(d)
                val archivos = DatosDiarios.locales(ses, cin, com, d).toMutableList()
                var faltantesDia = faltantes
                crudo?.let { k ->
                    val hc = listOfNotNull(
                        if (faltantes.none { it.tipo == "hc-diario" }) DatosDiarios.hcDiario(d, k.fc, k.sueno, k.peso) else null,
                        if (faltantes.none { it.tipo == "hc-actividades" }) DatosDiarios.hcActividades(d, k.actividades) else null)
                    val (ok, reducidos) = DatosDiarios.filtrarPermisos(hc, concedidos, p)
                    archivos.addAll(ok); faltantesDia = faltantes + reducidos
                    if (reducidos.isNotEmpty()) permisosReducidos = true
                }
                if (!DatosDiarios.hayQueSubir(archivos, p)) continue
                enviando = "Subiendo ${Comidas.fechaLarga(d.toString())}…"
                val r = raiz ?: DriveApi.carpeta(token, DatosDiarios.CARPETA).let { (id, nueva) -> if (nueva) carpetaNueva = true; id }
                raiz = r
                val sub = DriveApi.carpeta(token, d.toString(), r).first
                val enDrive = DatosDiarios.versionesEnDrive(DriveApi.nombres(token, sub), perfil, d)   // §1.5.6: no repetir nombres
                var est = p ?: DatosDiarios.EstadoDia(0, emptyMap(), false)
                for ((a, v0) in DatosDiarios.cambios(archivos, p)) {
                    val v = maxOf(v0, (enDrive[a.tipo] ?: 0) + 1)
                    val n = DatosDiarios.nombre(a.tipo, perfil, d, v)
                    DriveApi.subirArchivo(token, sub, n, "text/csv", a.texto.toByteArray(Charsets.UTF_8))
                    val permisos = if (a.tipo in DatosDiarios.PERMISOS_DE) DatosDiarios.permisosDe(a.tipo, concedidos) else null
                    est = est.copy(tipos = est.tipos + (a.tipo to DatosDiarios.Subido(v, n, a.sha256, a.filas, permisos)), manifestPendiente = true)
                    guardar(d, est)
                }
                // El manifest va al final, después de los CSV (§1.4).
                val mv = maxOf(est.version, enDrive["manifest"] ?: 0) + 1
                val m = DatosDiarios.manifest(perfil, d, mv, ZonedDateTime.now(DatosDiarios.ZONA), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
                    DatosDiarios.motivo(p), est.tipos, faltantesDia, lectura, base.get("reportes_apoyo/$d"),
                    Gimnasio.noDisponiblesDelDia(ses, d.toString()), Perfil.respuestaSenal(base.get(Perfil.rutaSenal(d.toString(), perfil))))
                DriveApi.subirArchivo(token, sub, DatosDiarios.nombreManifest(perfil, d, mv), "application/json", m.toByteArray(Charsets.UTF_8))
                guardar(d, est.copy(version = mv, manifestPendiente = false))
                subidos.add(d)
            }
            val partes = mutableListOf(
                if (subidos.isEmpty()) "Nada nuevo: los días cerrados ya estaban subidos."
                else "Subido: ${subidos.size} ${if (subidos.size == 1) "día" else "días"} (${subidos.joinToString(", ") { Comidas.fechaLarga(it.toString()) }}), en la carpeta \"${DatosDiarios.CARPETA}\" de tu Drive.")
            if (DatosDiarios.ayerAbierto(ahora)) partes.add("Ayer cierra a las 12:00.")
            if (subidos.isNotEmpty() && faltantes.isNotEmpty()) partes.add("Faltó: " + faltantes.joinToString("; ") { "${it.tipo} (${it.detalle})" })
            mensaje = "ok" to partes.joinToString(" ")
        } catch (e: Exception) {
            val m = if (e is DriveApi.ErrorDrive) "Drive respondió ${e.codigo}" else (e.message ?: e.javaClass.simpleName)
            mensaje = "err" to "No se pudo terminar la subida: $m. Lo que alcanzó a subir queda guardado; vuelve a tocar \"Subir ahora\" con internet."
        } finally { enviando = ""; revisar() }
    }
}

interface AccionesDd { fun subir() }

@Composable
fun SeccionDatosDiarios(dd: Dd, a: AccionesDd) {
    @Suppress("UNUSED_VARIABLE") val t = dd.tick
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CAJA).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Datos diarios", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Text("Sube a Drive (carpeta \"${DatosDiarios.CARPETA}\") los datos de cada día cerrado: fuerza, Health Connect, cintura y comidas. Un día cierra a las 12:00 del día siguiente. Revisa los últimos 7 días; si nada cambió, no sube nada.",
            color = GRIS, fontSize = 13.sp)
        Text(when (dd.porSubir) { 0 -> "Sin días por subir."; 1 -> "1 día por subir."; else -> "${dd.porSubir} días por subir." } +
            (if (dd.ayerAbierto) " Ayer cierra a las 12:00." else ""), color = if (dd.porSubir > 0) AMBAR else GRIS, fontSize = 14.sp)
        Button(onClick = { a.subir() }, enabled = dd.enviando.isEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = VERDE, contentColor = Color(0xFF0E1F18))) {
            Text(if (dd.enviando.isNotEmpty()) dd.enviando else "Subir ahora", fontWeight = FontWeight.Bold)
        }
        dd.mensaje?.let { (tipo, m) -> Text(m, color = if (tipo == "err") Color(0xFFE57373) else VERDE, fontSize = 14.sp) }
        if (dd.permisosReducidos) Text(DatosDiarios.AVISO_PERMISOS, color = AMBAR, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        if (dd.carpetaNueva) Text("Se creó una carpeta datos-diarios nueva; muévela a Genesis Gym.", color = AMBAR, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}
