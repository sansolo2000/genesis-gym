package io.github.sansolo2000.genesisgym

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 0.11.2 — D-03 y D-04 (QA #8 y #9, sesión real del 12-oct): reglas del descanso, separadas de la pantalla
 * para poder probarlas sin Android.
 *
 * El aviso de fin de descanso es una alarma del sistema (Programador.programarDescanso). Antes se cancelaba
 * con el gesto Atrás, con "‹ Mapa", con "Ir ahora" y al salir de la sesión, así que si Héctor salía de la app
 * no sonaba nada. Ahora la alarma solo se cancela:
 *  - con "Terminar descanso",
 *  - al empezar otro descanso (lo reemplaza),
 *  - al abrir otra sesión, o al cerrar o borrar la sesión.
 * "Ir ahora", "‹ Mapa", Atrás y salir de la sesión o de la app dejan correr el temporizador, y el aviso suena en 0.
 *
 * [alarma] recibe (fin en ms, texto) para programar el aviso, o (null, "") para cancelarlo.
 */
class RelojDescanso(private val alarma: (Long?, String) -> Unit) {
    var fin by mutableStateOf<Long?>(null)
        private set
    var total by mutableIntStateOf(0)
        private set
    /** Ejercicio y serie que terminaron cuando partió el descanso. */
    var de by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    /** Sesión a la que pertenece el descanso (fecha_sesionId). */
    var clave: String? = null
        private set

    fun activo(ahora: Long): Boolean = fin?.let { ahora < it } ?: false

    fun restanteSeg(ahora: Long): Int = fin?.let { (((it - ahora) + 999) / 1000).toInt().coerceAtLeast(0) } ?: 0

    fun iniciar(ahora: Long, seg: Int, i: Int, k: Int, claveSesion: String?, texto: String) {
        total = seg; de = i to k; clave = claveSesion
        val f = ahora + seg * 1000L
        fin = f
        alarma(f, texto)
    }

    fun mas30(texto: String) {
        val f = (fin ?: return) + 30_000L
        fin = f; total += 30
        alarma(f, texto)
    }

    /** "Terminar descanso", otra sesión, cerrar o borrar: corta el temporizador y su aviso. */
    fun cancelar() {
        if (fin != null) alarma(null, "")
        fin = null
    }

    /** Llegó a 0: el aviso ya sonó o está por sonar; solo se limpia el temporizador, sin tocar la alarma. */
    fun vencer() { fin = null }

    /** Al abrir una sesión: si el descanso ya venció, se limpia; si es otra sesión, se corta; si es la misma, sigue. */
    fun alAbrirSesion(claveSesion: String?, ahora: Long) {
        if (fin == null) return
        if (!activo(ahora)) vencer() else if (claveSesion != clave) cancelar()
    }
}
