package io.github.sansolo2000.genesisgym

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Datos diarios: la subida a Drive, compartida por "Subir ahora" (entrega 1) y la tarea automática (entrega 2,
 * CONTEXTO-DATOS-DIARIOS §11.1). Lo ya subido (sha256 y -vN por día) queda en las preferencias "datos-diarios", no en el respaldo.
 */
object SubidaDiaria {
    private const val PREFS = "datos-diarios"
    private const val PREFS_AUTO = "datos-diarios-auto"
    const val TAREA = "datos-diarios-automatica"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun auto(c: Context) = c.getSharedPreferences(PREFS_AUTO, Context.MODE_PRIVATE)
    fun previo(c: Context, d: LocalDate) = DatosDiarios.estadoDesdeJson(prefs(c).getString(d.toString(), null))
    private fun guardar(c: Context, d: LocalDate, e: DatosDiarios.EstadoDia) { prefs(c).edit().putString(d.toString(), DatosDiarios.estadoAJson(e)).commit() }

    private fun sesiones(base: BaseLocal) = base.coleccion("sesiones").map { it.data }
    private fun cinturas(base: BaseLocal) = base.coleccion(Cintura.COLECCION).mapNotNull { Cintura.desdeJson(it.data) }
    /** D-09: cada día con el programa que lo cubre (el activo o uno guardado en programas_alimentacion). */
    private fun comidas(c: Context, base: BaseLocal): (LocalDate) -> DatosDiarios.Archivo? {
        fun prog(o: kotlinx.serialization.json.JsonObject?) = o?.get("programa") as? kotlinx.serialization.json.JsonObject
        val activo = prog(base.get("alimentacion/programa_activo"))
        val guardados = base.coleccion("programas_alimentacion").mapNotNull { d ->
            prog(d.data)?.let { it to ((d.data["importado_en"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "") } }
        val inter = base.get("alimentacion/intercambios"); val regs = base.coleccion("comidas")
        return { d -> DatosDiarios.comidasDelDia(d, activo, guardados, inter, regs, previo(c, d)?.tipos?.containsKey("comidas") == true) }
    }

    /** §11.1.3: el primer día con datos en la app, para buscar los días que nunca se subieron. */
    private fun inicioDatos(base: BaseLocal): LocalDate? = DatosDiarios.inicioDatos(
        sesiones(base).mapNotNull { (it["fecha"] as? kotlinx.serialization.json.JsonPrimitive)?.content } +
            cinturas(base).map { it.fecha.toString() } +
            base.rutas("comidas").map { it.removePrefix("comidas/") } + base.rutas("reportes_apoyo").map { it.removePrefix("reportes_apoyo/") })

    fun dias(c: Context, base: BaseLocal, ahora: ZonedDateTime, automatico: Boolean): List<LocalDate> =
        DatosDiarios.diasARevisar(ahora, { previo(c, it) }, inicioDatos(base), if (automatico) DatosDiarios.MARGEN_AUTOMATICA_MIN else 0)

    /** Cuenta los días por subir sin leer Health Connect (eso se hace al subir). */
    fun porSubir(c: Context, base: BaseLocal, ahora: ZonedDateTime): Int {
        val ses = sesiones(base); val cin = cinturas(base); val com = comidas(c, base)
        return DatosDiarios.porSubir(dias(c, base, ahora, false), { d -> DatosDiarios.locales(ses, cin, com, d) }, { previo(c, it) })
    }

    data class Resultado(val tipo: String, val texto: String, val subidos: List<LocalDate>, val carpetaNueva: Boolean, val enRaiz: Boolean, val permisosReducidos: Boolean)

    /**
     * Sube los días de [dias] que cambiaron (§1.5). [automatico]: la tarea de la entrega 2 (motivo "automatico"; Health Connect
     * solo con el permiso de segundo plano). Lo que alcanzó a subir queda guardado aunque después falle.
     */
    suspend fun subir(c: Context, base: BaseLocal, hc: Hc, token: String, perfilNombre: String?, automatico: Boolean, progreso: (String) -> Unit = {}): Resultado {
        val ahora = ZonedDateTime.now(DatosDiarios.ZONA)
        val dias = dias(c, base, ahora, automatico)
        val perfil = SaludCsv.slug(perfilNombre)
        var carpetaNueva = false; var permisosReducidos = false
        val subidos = mutableListOf<LocalDate>()
        progreso("Leyendo Health Connect…")
        val disponible = hc.disponible()
        if (disponible) hc.refrescarPermisos()
        val sinSegundoPlano = automatico && disponible && !hc.segundoPlano()
        var crudo: Hc.Crudo? = null; var error: String? = null
        if (disponible && dias.isNotEmpty() && !sinSegundoPlano) try {
            crudo = hc.leerRango(dias.first().minusDays(1).atStartOfDay(DatosDiarios.ZONA).toInstant(), dias.last().plusDays(1).atStartOfDay(DatosDiarios.ZONA).toInstant())
        } catch (e: Exception) { error = "No se pudo leer Health Connect (${e.javaClass.simpleName})." }
        val concedidos = crudo?.concedidos ?: emptySet()
        val faltantes = if (sinSegundoPlano) listOf("hc-diario", "hc-actividades").map { DatosDiarios.Faltante(it, "sin-permiso-health-connect", "Sin permiso de lectura en segundo plano.") }
            else DatosDiarios.faltantesHc(disponible, error, concedidos)
        val lectura = DatosDiarios.LecturaHc(if (crudo != null) ZonedDateTime.now(DatosDiarios.ZONA) else null, automatico && crudo != null, DatosDiarios.PERMISOS.filter { it !in concedidos })
        val ses = sesiones(base); val cin = cinturas(base); val com = comidas(c, base)
        var raiz: String? = null
        for (d in dias) {
            val p = previo(c, d)
            val archivos = DatosDiarios.locales(ses, cin, com, d).toMutableList()
            var faltantesDia = faltantes
            crudo?.let { k ->
                val hcs = listOfNotNull(
                    if (faltantes.none { it.tipo == "hc-diario" }) DatosDiarios.hcDiario(d, k.fc, k.sueno, k.peso) else null,
                    if (faltantes.none { it.tipo == "hc-actividades" }) DatosDiarios.hcActividades(d, k.actividades) else null)
                val (ok, reducidos) = DatosDiarios.filtrarPermisos(hcs, concedidos, p)
                archivos.addAll(ok); faltantesDia = faltantes + reducidos
                if (reducidos.isNotEmpty()) permisosReducidos = true
            }
            if (!DatosDiarios.hayQueSubir(archivos, p)) continue
            progreso("Subiendo ${Comidas.fechaLarga(d.toString())}…")
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
                guardar(c, d, est)
            }
            // El manifest va al final, después de los CSV (§1.4).
            val mv = maxOf(est.version, enDrive["manifest"] ?: 0) + 1
            val m = DatosDiarios.manifest(perfil, d, mv, ZonedDateTime.now(DatosDiarios.ZONA), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
                DatosDiarios.motivo(p, automatico), est.tipos, faltantesDia, lectura, base.get("reportes_apoyo/$d"),
                Gimnasio.noDisponiblesDelDia(ses, d.toString()), Perfil.respuestaSenal(base.get(Perfil.rutaSenal(d.toString(), perfil))))
            DriveApi.subirArchivo(token, sub, DatosDiarios.nombreManifest(perfil, d, mv), "application/json", m.toByteArray(Charsets.UTF_8))
            guardar(c, d, est.copy(version = mv, manifestPendiente = false))
            subidos.add(d)
        }
        // §11.1.4: si la carpeta de la app sigue en la raíz, se avisa para moverla a Genesis Gym (una vez).
        val enRaiz = raiz != null && DriveApi.enRaiz(token, DatosDiarios.CARPETA)
        val partes = mutableListOf(
            if (subidos.isEmpty()) "Nada nuevo: los días cerrados ya estaban subidos."
            else "Subido: ${subidos.size} ${if (subidos.size == 1) "día" else "días"} (${subidos.joinToString(", ") { Comidas.fechaLarga(it.toString()) }}), en la carpeta \"${DatosDiarios.CARPETA}\" de tu Drive.")
        if (DatosDiarios.ayerAbierto(ahora)) partes.add("Ayer cierra a las 12:00.")
        if (subidos.isNotEmpty() && faltantes.isNotEmpty()) partes.add("Faltó: " + faltantes.joinToString("; ") { "${it.tipo} (${it.detalle})" })
        return Resultado("ok", partes.joinToString(" "), subidos, carpetaNueva, enRaiz, permisosReducidos)
    }

    // ---------- entrega 2: estado de la tarea automática (lo muestra la app) ----------
    data class EstadoAuto(val ultima: String?, val texto: String?, val faltaPermisoGoogle: Boolean, val enRaiz: Boolean)
    fun estadoAuto(c: Context) = auto(c).let { EstadoAuto(it.getString("ultima", null), it.getString("texto", null), it.getBoolean("falta_permiso", false), it.getBoolean("en_raiz", false)) }
    fun anotarAuto(c: Context, texto: String, faltaPermiso: Boolean, enRaiz: Boolean? = null) {
        auto(c).edit().putString("ultima", ZonedDateTime.now(DatosDiarios.ZONA).toString()).putString("texto", texto).putBoolean("falta_permiso", faltaPermiso)
            .also { e -> enRaiz?.let { e.putBoolean("en_raiz", it) } }.apply()
    }
    /** Una subida manual que funcionó quita el aviso de permiso de Google. */
    fun trasManual(c: Context, enRaiz: Boolean) { auto(c).edit().putBoolean("falta_permiso", false).putBoolean("en_raiz", enRaiz).apply() }

    /** Programa la tarea cada 6 h, con red y batería no baja (§11.1.1). KEEP: no se reprograma si ya existe. */
    fun programar(c: Context) {
        val req = PeriodicWorkRequestBuilder<TareaSubida>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(TAREA, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

/**
 * Entrega 2: la tarea automática. Funciona sin abrir la app. Si Google pide una pantalla para el permiso de Drive, no falla en
 * silencio: deja el aviso para Hoy. Si falla la red o Drive, WorkManager reintenta.
 */
class TareaSubida(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        val base = BaseLocal(c)
        if (SubidaDiaria.porSubir(c, base, ZonedDateTime.now(DatosDiarios.ZONA)) == 0) return Result.success()
        val token = try {
            val pedido = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveTexto.ALCANCE))).build()
            val r = Tasks.await(Identity.getAuthorizationClient(c).authorize(pedido), 60, TimeUnit.SECONDS)
            if (r.hasResolution()) { SubidaDiaria.anotarAuto(c, "Google pide volver a dar el permiso de Drive.", true); return Result.success() }
            r.accessToken
        } catch (e: Exception) { SubidaDiaria.anotarAuto(c, "No se pudo pedir el permiso de Google (${e.javaClass.simpleName}).", false); return Result.retry() }
        if (token == null) { SubidaDiaria.anotarAuto(c, "Google no entregó un permiso de acceso.", true); return Result.success() }
        return try {
            val perfil = Perfil.leer(base.get(Perfil.RUTA)).id
            val r = SubidaDiaria.subir(c, base, Hc(c), token, perfil, automatico = true)
            SubidaDiaria.anotarAuto(c, r.texto, false, r.enRaiz)
            Result.success()
        } catch (e: Exception) {
            SubidaDiaria.anotarAuto(c, "No se pudo terminar la subida automática (${if (e is DriveApi.ErrorDrive) "Drive respondió ${e.codigo}" else e.javaClass.simpleName}); se reintenta.", false)
            Result.retry()
        }
    }
}
