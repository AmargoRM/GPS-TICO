# GPS TICO — memoria del proyecto

App de GNSS/topografía para Costa Rica (Capacitor 6, Android). Un solo archivo web
`www/index.html` + plugins nativos Java en `android/app/src/main/java/com/garua/gps/`.
CI en GitHub Actions publica `gps-tico.apk` + `version.json` en el Release `apk-release`.

## ⚠️ REGLA CRÍTICA — NUNCA BORRAR LOS DATOS DEL USUARIO

Toda actualización DEBE tomar precauciones para que **no se borren los proyectos,
puntos, tracks, capas ni ajustes** del usuario. Esto ya pasó una vez (builds 92/93)
y fue grave: el usuario perdió todo su trabajo de campo. No puede repetirse.

Qué causó la pérdida y cómo evitarla:

1. **IndexedDB `garua-campo` versión 4 es sagrada.**
   - NUNCA bajar el número de versión ni cambiar el esquema de forma destructiva.
   - `abrirDB()` en `onupgradeneeded` SOLO debe **crear** stores que falten; jamás
     borrar, vaciar ni recrear stores existentes.
   - Stores: `proyectos`, `puntos`, `tracks`, `tiles`, `conf`, `capas`, `zonas`, `bigprops`.

2. **No meter capas/datos pesados en IndexedDB por defecto.**
   - La pérdida real ocurrió al descargar ~15 MB de capas DA a IndexedDB: se pasó la
     cuota de almacenamiento del WebView y Android **desalojó TODO el origen**
     (incluida la store `conf` con la config de Neon). Es decir, un dato pesado
     tumbó los datos livianos del usuario.
   - Cualquier capa/recurso pesado debe estar **apagado por defecto**, ser opcional,
     y preferiblemente guardarse FUERA de IndexedDB (archivo en Filesystem), no dentro.
   - Antes de agregar algo que ocupe espacio, estimar el tamaño y el impacto en la cuota.

3. **Almacenamiento persistente.** Al arrancar se llama `navigator.storage.persist()`.
   Si no se concede, el almacenamiento es desalojable. No asumir que está garantizado.

4. **Respaldo interno automático (ya implementado).** Ver sección siguiente. Cualquier
   cambio en el sistema de datos debe mantener este respaldo funcionando.

### Regla de oro antes de hacer merge/release
- ¿Esta actualización puede, en algún escenario, dejar sin datos al usuario? Si la
  respuesta no es un "no" rotundo, NO se publica.
- Ante la duda, revertir a la última build estable (base de referencia: build 89,
  commit `133c634`) antes que arriesgar los datos.

## Sistema de respaldo integrado (implementado)

Vive en `www/index.html`. Dos capas:

1. **Respaldo interno automático** → `Documentos/GPSTICO/respaldo-auto.json`
   (Capacitor Filesystem, `directory:'DOCUMENTS'`). Está **fuera de IndexedDB**, así
   que sobrevive a que el sistema vacíe la base del navegador, e incluso a desinstalar
   la app (carpeta compartida de Documentos).
   - `construirBackupObj()` arma `{_tipo:'gpstico-backup', _v:1, proyectos, puntos, tracks, capas, conf}`.
   - `programarRespaldoInterno()` lo dispara con antirrebote (6 s tras el último
     cambio, máx. 1 cada 30 s). Se engancha dentro de `dbPut`/`dbDel` para los stores
     de datos del usuario.
   - `guardarRespaldoInterno()` / `leerRespaldoInterno()` escriben/leen el archivo.
   - `revisarRespaldoAlInicio()` corre en el arranque: si la base está vacía pero hay
     respaldo interno con contenido, ofrece restaurarlo.
   - Manifest: `requestLegacyExternalStorage="true"` + `WRITE_EXTERNAL_STORAGE`
     (`maxSdkVersion=29`) para compatibilidad con Android 10 y anteriores. En Android
     11+ la app escribe sus propios archivos sin permiso.

2. **Copia a archivo (transferir / fuera del equipo)** → `exportarBackup()` genera un
   `.json` y abre Compartir (Drive, WhatsApp, PC). `importarBackupArchivo()` /
   `restaurarBackup()` restauran. Restaurar es **aditivo** (no borra lo existente) y
   reinicia la app al terminar. UI en `panelBackup()`.

Al tocar el sistema de datos, mantener ambas capas intactas.

## Registro de uso y fallos (diagnóstico)

Para depurar trabas/cierres sin poder reproducirlos. Sobrevive al cierre por fallo.

- **JS** (`www/index.html`): módulo `Registro` (global `logEvento(tipo,msg,extra)`).
  Captura `window.onerror`, promesas rechazadas y `console.error`. Persiste a
  `Documentos/GPSTICO/registro-uso.log` (Filesystem) y a `localStorage` (rápido,
  sincrónico). **Detección de cierre inesperado**: marcador `gt_sesionAbierta` en
  localStorage; si al arrancar sigue en "1", la sesión anterior no cerró limpio
  (traba/ANR/kill que no lanza excepción) → se anota como `crash`. Breadcrumbs en
  apertura de paneles, carga de capas y tracks.
- **Nativo** (`CrashCatcher.java`, instalado primero en `MainActivity.onCreate`):
  `Thread.setDefaultUncaughtExceptionHandler` escribe el stack trace a
  `getFilesDir()/crash-nativo.log` ANTES de que el sistema mate el proceso. JS lo
  lee con Filesystem `directory:'DATA'`.
- **Exportar**: Configuración → Historial de uso y fallos → "Exportar .txt"
  (`Registro.exportar()`): arma un .txt con info del equipo, almacenamiento,
  conteos, crashes nativos y el historial JS, y abre Compartir.
- Al tocar errores/estabilidad, mantener este registro y agregar `logEvento` en los
  puntos nuevos que puedan fallar.

## Nube (Neon vía Cloudflare Worker)

- Sincronización **solo de subida** (upload-only): `getNube`/`postNube` mandan
  `Authorization: Bearer <API_TOKEN>` al Worker `nube.*.workers.dev`. No hay función
  de descarga/pull desde la nube. Conectar la nube NO restaura datos locales perdidos.
- El campo "TOKEN DE ACCESO" de la app es el `API_TOKEN` del Worker (Cloudflare →
  Workers & Pages → worker → Configuración → Variables y secretos), **no** la cadena
  `postgresql://...` de Neon (esa es la dirección de la base, nunca va en la app).
- Si se expuso la contraseña de Neon, hay que rotarla en la consola de Neon.

## Notas técnicas

- CRTM05 = EPSG:5367 (Transverse Mercator, WGS84, lat0=0, lon0=-84, k0=0.9999,
  fe=500000, fn=0). En el código: `tmFwd`/`tmInv`/`haciaWgs`.
- Tiles son ImageBitmap: hacer `.close()` al desalojar de caché para no agotar la
  memoria nativa del renderer del WebView (OOM → la app se "recarga sola").
- DOCX/ZIP sin librerías: `construirZip(archivos)` + `_crc32` (método store).
- El número de build se estampa en `__BUILD__` desde `github.run_number` en el CI.
