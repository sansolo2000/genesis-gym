# Génesis Gym N — app nativa de Android

App nativa del Proyecto Génesis (Desarrollador 3), escrita en **Kotlin + Jetpack Compose**.
Plan y decisiones: `claude/PROPUESTA-APP-NATIVA.md` en el proyecto Génesis (aprobada por Héctor el 9-oct-2026).

- **No reemplaza** a la 1.0 ni a la 2.0 hasta que Héctor lo apruebe.
- **Nunca** entran a este repositorio rutinas, programas, registros, respaldos ni datos de salud.
- La llave de firma vive **solo** en los secretos de GitHub (`GG_KEYSTORE_BASE64`, `GG_KEYSTORE_PASSWORD`, `GG_KEY_ALIAS`, `GG_KEY_PASSWORD`).

## Etapa actual: E0
Una pantalla con la versión y un contador de aperturas. Sirve para probar que el APK firmado se instala
en el S25 y que una versión nueva se instala encima sin perder datos.

## Cómo se compila
En la nube, con `.github/workflows/android.yml`, cada vez que cambia esta carpeta. En un PC con Android SDK:
`./gradlew testDebugUnitTest assembleDebug`.

## Estructura (para aprender)
| Archivo | Qué es |
|---|---|
| `settings.gradle.kts` | Dónde se bajan las librerías y qué módulos tiene el proyecto |
| `build.gradle.kts` | Versiones del plugin de Android y de Kotlin |
| `app/build.gradle.kts` | Configuración de la app: identificador, versión mínima de Android, firma, librerías |
| `version.properties` | Versión de la app (se sube antes de cada publicación) |
| `app/src/main/AndroidManifest.xml` | Lo que la app declara a Android: nombre, ícono, pantallas y (más adelante) permisos |
| `app/src/main/java/.../MainActivity.kt` | La pantalla, escrita con Compose |
| `app/src/main/java/.../Saludo.kt` | Lógica pura, sin Android, para poder probarla |
| `app/src/test/...` | Pruebas automáticas (JUnit) que corren en la nube |
