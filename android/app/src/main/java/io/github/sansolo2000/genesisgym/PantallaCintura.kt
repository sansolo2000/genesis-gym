package io.github.sansolo2000.genesisgym

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** M-07 — estado de la sección Cintura (en la pestaña Salud). Lee la base cada vez: después de restaurar ya ve lo nuevo. */
class Cin(private val c: Context, private val base: BaseLocal) {
    var tick by mutableIntStateOf(0)
    var fecha by mutableStateOf(LocalDate.now(Gimnasio.ZONA).toString())
    var hora by mutableStateOf(LocalTime.now(Gimnasio.ZONA).format(DateTimeFormatter.ofPattern("HH:mm")))
    var m1 by mutableStateOf(""); var m2 by mutableStateOf(""); var m3 by mutableStateOf("")
    var soloPromedio by mutableStateOf(false)
    var nTexto by mutableStateOf("2")
    var ayunas by mutableStateOf(true)
    var nota by mutableStateOf("")
    var errores by mutableStateOf<List<String>>(emptyList())
    var mensaje by mutableStateOf<Pair<String, String>?>(null)
    var desde by mutableStateOf(SaludCsv.semanaPorDefecto(LocalDate.now(Gimnasio.ZONA)))
    var enviando by mutableStateOf("")
    var aBorrar by mutableStateOf<String?>(null)

    fun registros(): List<Pair<String, Cintura.Registro>> =
        base.coleccion(Cintura.COLECCION).mapNotNull { d -> Cintura.desdeJson(d.data)?.let { d.ruta to it } }

    /** Con 2 mediciones válidas que difieren en más de 1,0 cm, la pantalla pide la 3.ª. */
    fun pideTercera(): Boolean {
        val a = Cintura.leerCm(m1); val b = Cintura.leerCm(m2)
        return !soloPromedio && a != null && b != null && Cintura.pideTercera(a, b)
    }

    fun guardar() {
        mensaje = null
        val (r, err) = Cintura.armar(fecha.trim(), hora.trim(), listOf(m1, m2, if (pideTercera()) m3 else ""), soloPromedio, nTexto, ayunas, nota,
            LocalDate.now(Gimnasio.ZONA))
        errores = err
        if (r == null) { tick++; return }
        val ruta = Cintura.ruta(r)
        val reemplaza = base.get(ruta) != null
        base.put(ruta, Cintura.aJson(r, Gimnasio.ahoraIso()))
        mensaje = "ok" to "Guardado: ${Cintura.cmTexto(r.cm)} cm el ${Comidas.fechaLarga(r.fecha)}" + (if (r.hora.isEmpty()) "" else " a las ${r.hora}") + (if (reemplaza) " (reemplazó el registro de esa fecha y hora)." else ".")
        m1 = ""; m2 = ""; m3 = ""; nota = ""; soloPromedio = false; nTexto = "2"
        val f = LocalDate.parse(r.fecha); desde = SaludCsv.sabadoDe(f)
        tick++
    }

    fun borrar(ruta: String) { base.borrar(ruta); aBorrar = null; mensaje = "ok" to "Registro borrado."; tick++ }

    fun mover(n: Long) {
        val nuevo = desde.plusDays(7 * n)
        if (nuevo.isAfter(SaludCsv.sabadoDe(LocalDate.now(Gimnasio.ZONA)))) return
        desde = nuevo; mensaje = null; tick++
    }

    private val prefs get() = c.getSharedPreferences("e6", Context.MODE_PRIVATE)
    fun enviados(): Int = prefs.getInt("cintura-$desde", 0)

    suspend fun subir(token: String, perfil: String?) {
        if (enviando.isNotEmpty()) return
        val filas = Cintura.deSemana(registros().map { it.second }, desde)
        val v = enviados() + 1
        try {
            enviando = "Subiendo…"
            val carpeta = DriveApi.carpetaPropia(token, SaludCsv.CARPETA)
            val n = Cintura.nombre(SaludCsv.slug(perfil), desde, v)
            DriveApi.subirArchivo(token, carpeta, n, "text/csv", Cintura.csv(filas).toByteArray(Charsets.UTF_8))
            prefs.edit().putInt("cintura-$desde", v).apply()
            mensaje = "ok" to "Enviado: $n (${filas.size} ${if (filas.size == 1) "registro" else "registros"}), en la carpeta \"${SaludCsv.CARPETA}\" de tu Drive."
        } catch (e: Exception) {
            val m = if (e is DriveApi.ErrorDrive) "Drive respondió ${e.codigo}" else (e.message ?: e.javaClass.simpleName)
            mensaje = "err" to "No se pudo enviar a Drive: $m. Vuelve a intentarlo con internet."
        } finally { enviando = ""; tick++ }
    }
}

interface AccionesCintura { fun enviar() }

@Composable
fun SeccionCintura(ci: Cin, a: AccionesCintura) {
    @Suppress("UNUSED_VARIABLE") val t = ci.tick
    val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CAJA).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Cintura", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Text(Cintura.PROTOCOLO, color = AMBAR, fontSize = 14.sp)
        Text("La medición oficial es la del viernes; las de otros días son de referencia.", color = GRIS, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(ci.fecha, { ci.fecha = it.take(10) }, Modifier.weight(1.3f), label = { Text("Fecha") }, singleLine = true)
            OutlinedTextField(ci.hora, { ci.hora = it.take(5) }, Modifier.weight(1f), label = { Text(if (ci.soloPromedio) "Hora (opcional)" else "Hora") }, singleLine = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(ci.soloPromedio, { ci.soloPromedio = it; ci.hora = if (it) "" else LocalTime.now(Gimnasio.ZONA).format(DateTimeFormatter.ofPattern("HH:mm")) })
            Text("Dato anterior: solo tengo el promedio", fontSize = 14.sp)
        }
        if (ci.soloPromedio) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(ci.m1, { ci.m1 = it.take(6) }, Modifier.weight(1.3f), label = { Text("Promedio (cm)") }, singleLine = true, keyboardOptions = num)
            OutlinedTextField(ci.nTexto, { ci.nTexto = it.take(1) }, Modifier.weight(1f), label = { Text("N.º mediciones") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(ci.m1, { ci.m1 = it.take(6) }, Modifier.weight(1f), label = { Text("Medición 1 (cm)") }, singleLine = true, keyboardOptions = num)
                OutlinedTextField(ci.m2, { ci.m2 = it.take(6) }, Modifier.weight(1f), label = { Text("Medición 2 (cm)") }, singleLine = true, keyboardOptions = num)
            }
            if (ci.pideTercera()) {
                Text("Difieren en más de 1,0 cm: mide una 3.ª vez.", color = AMBAR, fontSize = 13.sp)
                OutlinedTextField(ci.m3, { ci.m3 = it.take(6) }, Modifier.fillMaxWidth(), label = { Text("Medición 3 (cm)") }, singleLine = true, keyboardOptions = num)
            }
            val ms = listOfNotNull(Cintura.leerCm(ci.m1), Cintura.leerCm(ci.m2), if (ci.pideTercera()) Cintura.leerCm(ci.m3) else null)
            if (ms.size >= 2 && !(ci.pideTercera() && ms.size < 3)) Text((if (ms.size == 3) "Mediana: " else "Promedio: ") + "${Cintura.cmTexto(Cintura.valor(ms))} cm", color = VERDE)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("En ayunas", Modifier.weight(1f)); Switch(ci.ayunas, { ci.ayunas = it })
        }
        OutlinedTextField(ci.nota, { ci.nota = it.take(Cintura.NOTA_MAX) }, Modifier.fillMaxWidth(), label = { Text("Nota (opcional)") })
        for (e in ci.errores) Text("• $e", color = Color(0xFFE57373), fontSize = 13.sp)
        Button(onClick = { ci.guardar() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Guardar medición") }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { ci.mover(-1) }) { Text("‹", fontSize = 24.sp) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Semana ${Comidas.fechaLarga(ci.desde.toString())} a ${Comidas.fechaLarga(ci.desde.plusDays(6).toString())}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                if (ci.enviados() > 0) Text("Ya enviada ${ci.enviados()} ${if (ci.enviados() == 1) "vez" else "veces"}: la próxima va como -v${ci.enviados() + 1}", color = GRIS, fontSize = 12.sp)
            }
            TextButton(onClick = { ci.mover(1) }) { Text("›", fontSize = 24.sp) }
        }
        val de = ci.desde.toString(); val hasta = ci.desde.plusDays(6).toString()
        val semana = ci.registros().filter { it.second.fecha in de..hasta }.sortedWith(compareBy({ it.second.fecha }, { it.second.hora }))
        if (semana.isEmpty()) Text("Sin mediciones esta semana.", color = GRIS, fontSize = 13.sp)
        for ((ruta, r) in semana) Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${Comidas.fechaLarga(r.fecha)} ${r.hora.ifEmpty { "sin hora" }} · ${Cintura.cmTexto(r.cm)} cm", fontSize = 14.sp)
                Text("n = ${r.n}" + (if (r.mediciones.isNotEmpty()) " (" + r.mediciones.joinToString(" · ") { Cintura.cmTexto(it) } + ")" else " · solo promedio") +
                    (if (r.ayunas) " · en ayunas" else "") + (r.nota?.let { " · $it" } ?: ""), color = GRIS, fontSize = 12.sp)
            }
            if (ci.aBorrar == ruta) {
                TextButton(onClick = { ci.borrar(ruta) }) { Text("Borrar", color = Color(0xFFE57373)) }
                TextButton(onClick = { ci.aBorrar = null; ci.tick++ }) { Text("No") }
            } else TextButton(onClick = { ci.aBorrar = ruta; ci.tick++ }) { Text("✕", color = GRIS) }
        }
        ci.mensaje?.let { (tipo, m) -> Text(m, color = if (tipo == "err") Color(0xFFE57373) else VERDE, fontSize = 13.sp) }
        OutlinedButton(onClick = { a.enviar() }, enabled = ci.enviando.isEmpty() && semana.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = VERDE)) {
            Text(if (ci.enviando.isNotEmpty()) ci.enviando else "Enviar el CSV de cintura de la semana al Drive")
        }
    }
}
