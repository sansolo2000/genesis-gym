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
    /** Entrega 2: lo último que hizo la tarea automática (y si falta el permiso de Google o la carpeta sigue en la raíz). */
    var auto by mutableStateOf(SubidaDiaria.EstadoAuto(null, null, false, false))

    /** Cuenta los días por subir sin leer Health Connect (eso se hace al subir). */
    fun revisar() {
        val ahora = ZonedDateTime.now(DatosDiarios.ZONA)
        ayerAbierto = DatosDiarios.ayerAbierto(ahora)
        porSubir = runCatching { SubidaDiaria.porSubir(c, base, ahora) }.getOrDefault(0)
        auto = SubidaDiaria.estadoAuto(c)
        tick++
    }

    /** Entrega 2: Health Connect está, pero falta el permiso de lectura en segundo plano (la automática sube sin los hc-). */
    fun faltaSegundoPlano(): Boolean = hc.disponible() && !hc.segundoPlano()

    /** "Subir ahora" (entrega 1, se mantiene como respaldo manual de la automática). La lógica es la de SubidaDiaria. */
    suspend fun subir(token: String, perfilNombre: String?) {
        if (enviando.isNotEmpty()) return
        mensaje = null; carpetaNueva = false; permisosReducidos = false
        try {
            val r = SubidaDiaria.subir(c, base, hc, token, perfilNombre, automatico = false) { enviando = it }
            carpetaNueva = r.carpetaNueva; permisosReducidos = r.permisosReducidos
            SubidaDiaria.trasManual(c, r.enRaiz)
            mensaje = r.tipo to r.texto
        } catch (e: Exception) {
            val m = if (e is DriveApi.ErrorDrive) "Drive respondió ${e.codigo}" else (e.message ?: e.javaClass.simpleName)
            mensaje = "err" to "No se pudo terminar la subida: $m. Lo que alcanzó a subir queda guardado; vuelve a tocar \"Subir ahora\" con internet."
        } finally { enviando = ""; revisar() }
    }

}

interface AccionesDd {
    fun subir()
    /** Entrega 2: abre el ajuste de batería de Android ("Sin restricciones"). */
    fun bateria() {}
    /** Entrega 2: pide a Health Connect el permiso de lectura en segundo plano. */
    fun segundoPlano() {}
}

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
        // Entrega 2 (CONTEXTO-DATOS-DIARIOS §11.1): subida automática.
        Text("Subida automática: con internet, cada día después de las 12:15, aunque no abras la app." +
            (dd.auto.ultima?.let { " Última: ${it.take(16).replace('T', ' ')} · ${dd.auto.texto ?: ""}" } ?: " Todavía no corrió."), color = GRIS, fontSize = 13.sp)
        if (dd.auto.faltaPermisoGoogle) Text("La subida automática necesita que vuelvas a dar permiso de Google: toca \"Subir ahora\" una vez.",
            color = Color(0xFFE57373), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        if (dd.auto.enRaiz) Text("Tu carpeta datos-diarios está en la raíz de tu Drive: muévela a Genesis Gym una vez (si no usas esa carpeta, puede quedar ahí).",
            color = AMBAR, fontSize = 13.sp)
        if (dd.faltaSegundoPlano()) androidx.compose.material3.OutlinedButton(onClick = { a.segundoPlano() }, modifier = Modifier.fillMaxWidth()) {
            Text("Permitir Health Connect en segundo plano")
        }
        androidx.compose.material3.OutlinedButton(onClick = { a.bateria() }, modifier = Modifier.fillMaxWidth()) { Text("Batería de Génesis Gym: \"Sin restricciones\"") }
        Text("Para que suba sola, en el ajuste que se abre elige Génesis Gym N y deja la batería en \"Sin restricciones\".", color = GRIS, fontSize = 12.sp)
    }
}
