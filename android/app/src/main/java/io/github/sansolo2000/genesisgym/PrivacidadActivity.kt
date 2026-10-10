package io.github.sansolo2000.genesisgym

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Política de privacidad que Health Connect abre desde su ventana de permisos (la exige Health Connect).
 * Describe lo que la app hace HOY; se actualiza en cada etapa que cambie el uso de los datos.
 */
class PrivacidadActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Privacidad de Génesis Gym N", style = MaterialTheme.typography.headlineSmall)
                        Text("App personal del Proyecto Génesis, para Héctor y su familia. No es una app pública.")
                        Text("Qué lee de Health Connect: solo con tu permiso, y solo en modo lectura: peso, sueño, sesiones de ejercicio y FC en reposo.")
                        Text("Para qué: en esta etapa de prueba (E2) solo cuenta cuántos registros hay y de qué app vienen. No muestra ni guarda los valores.")
                        Text("Dónde quedan los datos: en este celular. La app no los envía a ningún servidor, no los sube al repositorio y no los comparte con terceros.")
                        Text("Más adelante, y solo si lo apruebas, la app podrá enviar un resumen a tu propio Google Drive para el Evaluador del Proyecto Génesis.")
                        Text("Puedes quitar el permiso cuando quieras desde Health Connect → Permisos de apps → Génesis Gym N.")
                    }
                }
            }
        }
    }
}
