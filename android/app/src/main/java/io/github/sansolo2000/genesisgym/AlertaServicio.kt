package io.github.sansolo2000.genesisgym

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * M-10 — hace sonar una alerta hasta "Detener" o "Posponer", con el tope de 2 minutos (después se calla y la
 * notificación queda). Si llegan varias, se ve una notificación por tipo y suena la de mayor prioridad
 * (Medicamentos > Entrenamiento > Comidas > Tareas > Reporte). D-18 (0.18.0): cada alerta suena sus propios 2 minutos desde
 * que llega. Al detener una se detienen todas las que ya estaban (O-35: no una que llega en ese momento).
 */
class AlertaServicio : Service() {
    companion object {
        private const val SONAR = "sonar"
        /** El servicio en curso (mismo proceso), para que Detener y Posponer lo callen. */
        var actual: AlertaServicio? = null
            private set

        fun sonar(c: Context, datos: Intent, id: Int): Intent = Intent(datos).setClass(c, AlertaServicio::class.java).setAction(SONAR).putExtra(Programador.EXTRA_ID, id)

        private fun canalesQueInsisten() = Alertas.TIPOS.filter { Alertas.insiste(it) }.map { Programador.canal(it) }.toSet()

        /**
         * "Detener" (o tocar la notificación, O-33): calla y quita las alertas, también las que quedaron después del tope.
         * O-35: una que llegó hace menos de [Alertas.GRACIA_MS] sigue sonando, salvo que sea la tocada ([tocada]).
         */
        fun detenerTodas(c: Context, tocada: Int? = null) {
            val ahora = System.currentTimeMillis()
            actual?.detenerTodo(tocada)
            val nm = c.getSystemService(NotificationManager::class.java)
            val canales = canalesQueInsisten()
            val quedan = actual?.ids().orEmpty()
            for (n in nm.activeNotifications) if (n.notification.channelId in canales && n.id !in quedan && (n.id == tocada || Alertas.detenerAlcanza(n.postTime, ahora))) nm.cancel(n.id)
            Programador.detener()
            if (actual?.suenaAlgo() != true) Programador.dejarDeVibrar(c)
        }

        /** O-33: marcar la comida o la tarea, abrir la sesión o guardar el reporte calla la alerta de ese tipo. */
        fun callarTipo(c: Context, tipo: String) {
            actual?.quitarTipo(tipo)
            val nm = c.getSystemService(NotificationManager::class.java)
            for (n in nm.activeNotifications) if (n.notification.channelId == Programador.canal(tipo) && n.id != actual?.idEnPrimerPlano()) nm.cancel(n.id)
        }
    }

    fun idEnPrimerPlano(): Int? = idPrimerPlano
    fun ids(): Set<Int> = activas.keys.toSet()
    fun suenaAlgo(): Boolean = tipoSonando != null

    /** Quita las alertas de un tipo (O-33); las de otros tipos siguen. */
    fun quitarTipo(t: String) = quitar(activas.filterValues { it == t }.keys.toList()) { true }

    private val activas = LinkedHashMap<Int, String>()   // id de la notificación → tipo
    private val llegada = HashMap<Int, Long>()   // D-18 y O-35: cuándo llegó cada una (elapsedRealtime)
    private val notifs = HashMap<Int, android.app.Notification>()   // O-38: para pasar el primer plano a otra si se quita una
    private var idPrimerPlano: Int? = null
    private var notifPrimerPlano: android.app.Notification? = null
    private var tipoSonando: String? = null
    private var mp: MediaPlayer? = null
    private val h = Handler(Looper.getMainLooper())
    private val revisar = Runnable { actualizar() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); actual = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != SONAR) { if (activas.isEmpty()) stopSelf(); return START_NOT_STICKY }
        val t = intent.getStringExtra(Programador.EXTRA_TIPO) ?: Recordatorios.COMIDA
        val id = intent.getIntExtra(Programador.EXTRA_ID, 300)
        val n = Programador.construir(this, intent, id)
        // Cada startForegroundService pide su startForeground: se repite con la notificación que ya estaba en primer plano.
        val fg = idPrimerPlano; val fgN = notifPrimerPlano
        if (fg == null || fgN == null) { startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK); idPrimerPlano = id; notifPrimerPlano = n }
        else { startForeground(fg, fgN, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK); getSystemService(NotificationManager::class.java).notify(id, n) }
        activas[id] = t; notifs[id] = n; llegada[id] = SystemClock.elapsedRealtime()
        actualizar()
        return START_NOT_STICKY
    }

    /**
     * D-18: suena la de mayor prioridad entre las que no llegaron a su tope de 2 minutos (cada una cuenta desde que llegó).
     * Cuando todas llegaron, se calla y se cierra el servicio; las notificaciones quedan.
     */
    private fun actualizar() {
        val ahora = SystemClock.elapsedRealtime()
        val lista = activas.map { (id, t) -> Alertas.Activa(t, llegada[id] ?: ahora) }
        val t = Alertas.queSuena(lista, ahora)
        if (t == null) { callar(); detenerServicio(quitarNotificacion = false); return }
        if (t != tipoSonando) sonarTipo(t)
        h.removeCallbacks(revisar)
        Alertas.proximoTope(lista, ahora)?.let { h.postDelayed(revisar, it - ahora) }
    }

    private fun sonarTipo(t: String) {
        callar()
        val v = Programador.tipo(this, t)
        val e = Programador.estado(this)
        if (Alertas.suena(v, e.timbre, e.filtro, e.alarmasPermitidas)) mp = Programador.preparar(this, v, true)?.also { it.start() }
        if (Alertas.vibra(v, e.timbre)) Programador.vibrar(this, true)
        tipoSonando = t
    }

    /** Calla el sonido y la vibración; las notificaciones quedan. */
    fun callar() {
        mp?.let { runCatching { it.stop() }; it.release() }; mp = null
        Programador.dejarDeVibrar(this)
        tipoSonando = null
        h.removeCallbacks(revisar)
    }

    /**
     * "Detener": calla y quita las alertas que ya estaban, salvo las notificaciones de Medicamentos (esperan Confirmar,
     * Posponer u Omitir; §9.6). O-35: una que llegó hace menos de [Alertas.GRACIA_MS] sigue con sus 2 minutos.
     */
    fun detenerTodo(tocada: Int? = null) {
        val ahora = SystemClock.elapsedRealtime()
        quitar(activas.keys.filter { it == tocada || Alertas.detenerAlcanza(llegada[it] ?: 0L, ahora) }) { !Alertas.esperaRespuesta(it) }
    }

    /**
     * O-38 (Héctor, 16-oct): responder un medicamento quita solo su notificación. Si había otras alertas en el servicio,
     * siguen; si sonaba la respondida, pasa a sonar la de mayor prioridad que quede.
     */
    fun quitarUna(id: Int) {
        if (id !in activas) { getSystemService(NotificationManager::class.java).cancel(id); return }
        quitar(listOf(id)) { true }
    }

    /** D-19: calla solo esta alerta y deja su notificación (las demás siguen sonando). */
    fun callarUna(id: Int) = quitar(listOf(id)) { false }

    /**
     * Saca alertas del servicio; [cancelar] dice, según el tipo, si también se quita su notificación. Las que quedan siguen
     * sonando con sus propios 2 minutos (D-18); si no queda ninguna, se calla y se cierra el servicio.
     */
    private fun quitar(ids: Collection<Int>, cancelar: (String) -> Boolean) {
        val fuera = ids.mapNotNull { id -> activas.remove(id)?.let { t -> llegada.remove(id); notifs.remove(id); id to t } }
        if (fuera.isEmpty()) return
        val nm = getSystemService(NotificationManager::class.java)
        val fg = idPrimerPlano; val fgN = notifPrimerPlano
        val tipoFg = fuera.firstOrNull { it.first == fg }?.second
        if (activas.isEmpty()) {
            callar()
            for ((id, t) in fuera) if (id != fg && cancelar(t)) nm.cancel(id)
            detenerServicio(quitarNotificacion = tipoFg != null && cancelar(tipoFg))
            return
        }
        if (fg != null && tipoFg != null) {
            val otro = activas.keys.first(); val n = notifs[otro]
            if (n != null) {
                startForeground(otro, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK); idPrimerPlano = otro; notifPrimerPlano = n
                // Al cambiar el primer plano, Android quita la notificación anterior: si debe quedar (Medicamentos), se vuelve a mostrar.
                if (!cancelar(tipoFg) && fgN != null) nm.notify(fg, fgN)
            }
        }
        for ((id, t) in fuera) if (cancelar(t)) nm.cancel(id)
        actualizar()
    }

    /** "Posponer" de una notificación: calla todo y quita solo esa notificación. */
    fun posponer(id: Int) {
        callar()
        getSystemService(NotificationManager::class.java).cancel(id)
        activas.remove(id)
        detenerServicio(quitarNotificacion = id == idPrimerPlano)
    }

    private fun detenerServicio(quitarNotificacion: Boolean) {
        stopForeground(if (quitarNotificacion) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        idPrimerPlano = null; notifPrimerPlano = null
        activas.clear(); llegada.clear(); notifs.clear()
        stopSelf()
    }

    override fun onDestroy() { callar(); if (actual === this) actual = null; super.onDestroy() }
}

/** Botones "Detener" y "Posponer" de una alerta, y la notificación descartada (cuenta como Detener). */
class AccionAlerta : BroadcastReceiver() {
    companion object { const val DETENER = "detener"; const val POSPONER = "posponer"; const val BORRADA = "borrada" }

    override fun onReceive(c: Context, intent: Intent) {
        val id = intent.getIntExtra(Programador.EXTRA_ID, -1)
        val nm = c.getSystemService(NotificationManager::class.java)
        when (intent.action) {
            POSPONER -> {
                Programador.posponer(c, intent)
                AlertaServicio.actual?.posponer(id) ?: nm.cancel(id)
                Programador.detener(); Programador.dejarDeVibrar(c)
            }
            else -> AlertaServicio.detenerTodas(c, id.takeIf { it >= 0 })   // al detener una se detienen las que ya estaban (O-35)
        }
    }
}
