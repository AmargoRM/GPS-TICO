# Proxy GPS TICO → Neon (Cloudflare Worker)

```
App (APK) ──HTTPS + token──► Cloudflare Worker ──connection string──► Neon (Postgres)
```

El Worker guarda el secreto de Neon; la app **solo** conoce la URL del Worker y
un token. Este Worker **no tiene dependencias** — se pega y despliega directo
en el editor del dashboard de Cloudflare.

## Montaje (una sola vez, ~10 min)

### 1) Neon
1. Cuenta en https://neon.tech (plan gratis alcanza).
2. Copiá la connection string de tu base (empieza con `postgresql://`).
3. Las tablas `puntos` y `tracks` **se crean solas** la primera vez que la
   app manda datos (el Worker corre `CREATE TABLE IF NOT EXISTS`).

### 2) Cloudflare Worker (desde el navegador, sin herramientas)
1. Entrá a **Workers & Pages** en https://dash.cloudflare.com.
2. Crear un Worker nuevo (nombre a gusto, ej. `nube`). Anotá la URL que te da,
   estilo `https://nube.TUCUENTA.workers.dev`.
3. **Edit code** → borrás todo lo que hay y pegás el contenido de `worker.js`
   (este mismo directorio) → **Deploy**.
4. **Settings → Variables and secrets → Add**:
   - `nube` (tipo Secret) → pegás la connection string de Neon.
   - `API_TOKEN` (tipo Secret) → inventás un token largo (32+ caracteres).

### 3) En la app
Menú → **Sincronizar a la nube** →
- **Dirección del proxy**: la URL `https://nube.TUCUENTA.workers.dev`
- **Token de acceso**: el mismo `API_TOKEN` de arriba
- **Guardar ajustes** → **Probar conexión** (debe decir "OK ✓").

Listo. Con "Subir automáticamente" activo, cada vez que haya internet se suben
los puntos y tracks pendientes. También podés forzar con los botones.

## Estructura en Neon

**Tabla `puntos`**: `id, proy, nombre, detalle, fecha, lat, lon, alt_orto, exac, subido_en`

**Tabla `tracks`**: `id, proy, nombre, detalle, fecha, distancia_m, n_puntos, geojson, subido_en`
(la geometría `LineString` va en `geojson` como jsonb — QGIS puede leerlo con
la expresión `ST_GeomFromGeoJSON(geojson::text)`).

## Endpoints

| Método | Ruta      | Autenticación | Uso |
|--------|-----------|---------------|-----|
| GET    | `/ping`   | ninguna       | prueba de conexión |
| POST   | `/puntos` | Bearer token  | inserta/upsert de `{ puntos: [...] }` |
| POST   | `/tracks` | Bearer token  | inserta/upsert de `{ tracks: [...] }` |
| GET    | `/puntos` | Bearer token  | últimos 500 puntos (para QGIS) |
| GET    | `/tracks` | Bearer token  | últimos 200 tracks |

## Seguridad

- El `API_TOKEN` puede extraerse del APK. Es de bajo privilegio (solo insertar
  en `puntos` y `tracks`). Si se filtra, cambiá el secret en Cloudflare y
  actualizá la app.
- La connection string de Neon (`nube`) NUNCA sale del Worker.
