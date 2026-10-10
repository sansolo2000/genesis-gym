# Pruebas automáticas de Génesis Gym 2.0

Pruebas de punta a punta con Playwright (Chromium) contra una copia de la app servida en `http://localhost:<puerto>/genesis-gym/`.
**No contienen datos personales:** todo lo que usan está en `datos-ejemplo/` y es inventado (`datos-ejemplo/crear.py` lo regenera).

## Requisitos
- Node 18 o superior y `npm i playwright` (o `NODE_PATH` apuntando a una instalación existente). `servidor.js` usa Chromium.
- Para las pruebas que comparan con la 1.0: la carpeta `genesis-app` de la 1.0 (solo lectura; no está en este repositorio).

## Cómo correrlas (desde esta carpeta)
| Prueba | Comando | Qué cubre |
|---|---|---|
| App instalable | `node test-pwa.js ..` | service worker, sin internet, aviso de versión nueva, caché de imágenes |
| Paso 2 | `node test-paso2.js .. <genesis-app 1.0>` | pantallas de la 1.0 sobre IndexedDB |
| Paso 3 | `node test-paso3.js .. <genesis-app 1.0>` | perfil, respaldo y restauración |
| Paso 4 | `node test-paso4.js ..` | migración 1.0 → 2.0 (`herramientas/migrar.js`) con `datos-ejemplo/base-1.0` |
| Selector | `node test-selector.js .. <genesis-app 1.0>` | elegir un archivo en Android (la pantalla se redibuja) |
| Imágenes | `node test-imagenes.js .. [universo-ejercicios.json]` | catálogo, fichas, créditos, sin internet |
| Drive | `node test-drive.js .. <genesis-app 1.0>` | "Buscar en Drive" con Google simulado (`dobles-google.js`) |
| Comidas | `node test-comidas.js ..` | módulo Comidas con `datos-ejemplo/programa-alimentacion-ejemplo.json` |

Cada prueba imprime `PASA`/`FALLA` por línea y termina con código 0 solo si todo pasa.
Las pruebas que usan la 1.0 o el universo de ejercicios toman esos archivos como argumento: nunca se copian aquí.

## Herramientas (`../herramientas/`)
- `construir.py`: arma `index.html` desde `app.src.html` de la 1.0 aplicando cambios verificados (cada texto original debe aparecer una vez).
- `armar_universo.py`: copia sin modificar las imágenes del universo de Entrenamiento y genera `imagenes/catalogo.json`.
- `migrar.js`: convierte la exportación de la base 1.0 en un archivo `genesis-respaldo`. **Su salida tiene datos personales: nunca al repositorio.**
