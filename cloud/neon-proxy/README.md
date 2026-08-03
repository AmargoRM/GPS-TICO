# Proxy GPS TICO → Neon (opción B)

La app manda tus puntos y capas a **tu** base Neon, pero sin conocer la clave secreta:
solo habla con este proxy (un Cloudflare Worker gratis). El Worker guarda la
connection string de Neon y valida un token.

```
App (APK)  ──HTTPS + token──►  Cloudflare Worker  ──connection string──►  Neon (Postgres)
```

## Pasos (una sola vez)

### 1. Base Neon
1. Creá un proyecto en https://neon.tech (plan gratis alcanza).
2. Copiá la **connection string** (`postgresql://...`).
3. En el **SQL Editor** de Neon, pegá y ejecutá el contenido de `schema.sql`.

### 2. Cuenta Cloudflare + Worker
1. Creá cuenta en https://dash.cloudflare.com (gratis).
2. Instalá las herramientas:
   ```bash
   cd cloud/neon-proxy
   npm install
   npx wrangler login
   ```
3. Cargá los dos secretos:
   ```bash
   npx wrangler secret put DATABASE_URL   # pegás la connection string de Neon
   npx wrangler secret put API_TOKEN      # inventás un token largo (ej. 32+ caracteres)
   ```
4. Publicá:
   ```bash
   npx wrangler deploy
   ```
   Te devuelve una URL como `https://gps-tico-proxy.TUCUENTA.workers.dev`.

### 3. En la app
Menú → **Sincronizar a la nube** →
- **Dirección del proxy**: la URL `...workers.dev`
- **Token de acceso**: el mismo `API_TOKEN` de arriba
- **Guardar ajustes** → **Probar conexión** (debe decir "OK").

Listo. Con "Subir automáticamente" activo, cada vez que haya internet se suben los
puntos pendientes. También podés forzarlo con **Subir puntos ahora** / **Subir capas**.

## Notas
- **Token**: cualquiera con el APK puede extraerlo, así que es un token de bajo
  privilegio (solo insertar). No es la clave de Neon. Si se filtra, cambiá el
  `API_TOKEN` con `wrangler secret put` y actualizá la app.
- **Fotos**: por ahora no se suben (pesan). Se sube el conteo `n_fotos`. Para
  guardarlas en la nube conviene Cloudflare R2 (paso aparte).
- **Endpoints**: `GET /ping`, `POST /puntos`, `POST /capas` (con `Authorization: Bearer <token>`).
