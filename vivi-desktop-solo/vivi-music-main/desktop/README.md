# VIVI Music para escritorio (Windows · Linux · macOS)

App nativa de escritorio de VIVI Music hecha con **Compose Multiplatform (JVM)**. Reutiliza el
mismo código de la app Android para buscar y reproducir música, y se **sincroniza con el móvil** sin
servidores.

## Qué se reutiliza de Android (sin copiar código)

| Módulo Android | En escritorio |
|---|---|
| `innertube/` (API de YouTube Music: home, búsqueda, álbumes, artistas, playlists, radio, letras) | Se compila **tal cual** (`sourceSets` apunta a `../innertube`) |
| `lyricsProvider/…/lrclib` (letras sincronizadas) | Se compila tal cual |
| Base de datos Room `song.db` (v34) | Mismo esquema exacto en SQLite (`RoomSchema.kt` generado desde `app/schemas/…/34.json`) |
| Estrategia de clientes de `YTPlayerUtils` (ANDROID_VR, IOS, TVHTML5… + descifrado NewPipe) | `StreamResolver.kt` |
| ExoPlayer / Media3 | **LibVLC (vlcj)** + proxy local con descarga por rangos (`StreamProxy.kt`) |
| Material You (color de la carátula) | Tema dinámico con color dominante de la carátula |

## Funciones

- Inicio con las secciones de YouTube Music (chips de ánimo, continuación infinita) y "Escuchado recientemente".
- Búsqueda con sugerencias en vivo e historial; filtros: Todo, Canciones, Videos, Álbumes, Artistas, Listas.
- Páginas de álbum, artista (seguir), playlists de YouTube (importar a tus listas) y "Explorar".
- Radio automática infinita al reproducir una canción, cola editable, aleatorio, repetir, normalización de volumen.
- Letras sincronizadas (LrcLib → YouTube Music) con clic en una línea para saltar.
- Biblioteca: tus listas (crear, renombrar, reordenar, eliminar), canciones, álbumes guardados, artistas seguidos, "Me gusta", historial.
- Interfaz de escritorio: barra lateral, reproductor inferior, panel derecho de cola/letras, diseño adaptable
  (se compacta en ventanas pequeñas), menú de clic derecho, icono en la bandeja del sistema,
  teclas multimedia y atajos (Ctrl+Espacio, Ctrl+←/→).
- Cuenta de YouTube Music opcional (cookie) para recomendaciones y "Me gusta" en la nube.

## Sincronización con Android (carpeta compartida, sin servidor)

1. **Android:** VIVI → Ajustes → Copia de seguridad → activa la copia automática. Los respaldos se guardan en
   `Descargas/vivimusic`.
2. Sincroniza esa carpeta con la nube (Autosync for Google Drive, FolderSync, Syncthing, OneDrive…).
3. **PC:** Ajustes → *Sincronización con Android* → elige la misma carpeta (p. ej. `G:\Mi unidad\vivimusic`).
4. El PC **fusiona** cada respaldo nuevo del móvil: favoritos, biblioteca, listas, historial, contadores,
   álbumes, artistas, búsquedas y letras. Nada local se pierde; lo que borras en el PC no "resucita".
5. El PC deja `auto_backup_desktop_<fecha>.backup` en la carpeta. Como empieza por `auto_backup_`, Android
   normalmente lo muestra en su lista de copias automáticas; si no aparece, usa *Restaurar* y elige el archivo.

> Nota: la restauración en Android reemplaza su base de datos por la fusionada (que ya incluye todo lo del móvil).
> Mantén la app Android actualizada (esquema v34 o superior).

## Compilar

Requisitos: JDK 17+ para ejecutar Gradle (el JDK 21 se descarga solo) y conexión a internet.

```bash
cd desktop
./gradlew run                 # ejecutar en desarrollo
./gradlew packageMsi          # instalador Windows (.msi)  → build/compose/binaries/main/msi/
./gradlew packageExe          # instalador Windows (.exe)
./gradlew packageDeb          # Linux
./gradlew packageDmg          # macOS
```

**Audio (VLC):** en Windows ejecuta antes `scripts/fetch-vlc.ps1` para que el instalador incluya VLC portable
(así el usuario final no instala nada). Si no, la app usa el VLC instalado en el sistema (64 bits).

**Sin instalar nada:** sube el repositorio a GitHub; el workflow `.github/workflows/desktop-windows.yml`
compila y publica el `.msi` y el `.exe` como artefactos (pestaña *Actions* → *Run workflow*).

## Estructura

```
desktop/src/main/kotlin/com/music/vivi/desktop/
├── Main.kt                 ventana, barra lateral, búsqueda, bandeja, atajos
├── AppState.kt             servicios (DB, reproductor, sync) y navegación
├── data/                   MusicDatabase (song.db compatible), RoomSchema, ajustes
├── sync/SyncManager.kt     importar/fusionar/exportar respaldos de Android
├── playback/               StreamResolver, StreamProxy, AudioEngine (VLC), PlayerController
├── lyrics/                 letras LRC
└── ui/                     tema, componentes, pantallas, reproductor
```

Datos locales: `%APPDATA%\ViviMusic` (Windows), `~/Library/Application Support/ViviMusic` (macOS),
`~/.local/share/vivimusic` (Linux).
