// GPS TICO — Proxy a Neon (Cloudflare Worker)
// Sin dependencias externas: pegá esto en el editor del dashboard y Deploy.
//
// Secretos que espera (Settings → Variables and secrets → Add):
//   nube       → connection string de Neon (postgresql://usuario:pass@host/db?...)
//   API_TOKEN  → token largo (32+ chars) que también configurás en la app
//
// Endpoints (requieren Authorization: Bearer <API_TOKEN>, excepto /ping):
//   GET  /ping     → salud (público)
//   POST /puntos   → { puntos: [...] }  upsert en tabla `puntos` con columna geom
//   POST /tracks   → { tracks: [...] }  upsert en tabla `tracks` con columna geom
//   POST /fotos    → { fotos:  [...] }  upsert en tabla `fotos`
//   GET  /puntos   → últimos 500 (para verificar)
//   GET  /tracks   → últimos 200

export default {
  async fetch(request, env) {
    const cors = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
      'Access-Control-Allow-Headers': 'authorization,content-type',
    };
    const json = (obj, status = 200) => new Response(JSON.stringify(obj), {
      status,
      headers: { 'content-type': 'application/json', ...cors },
    });

    if (request.method === 'OPTIONS') return new Response(null, { headers: cors });

    const url = new URL(request.url);

    if (request.method === 'GET' && url.pathname === '/ping') {
      return json({ ok: true, ts: Date.now() });
    }

    const auth = request.headers.get('Authorization') || '';
    const token = auth.replace(/^Bearer\s+/i, '').trim();
    if (!env.API_TOKEN || token !== env.API_TOKEN) {
      return json({ ok: false, error: 'Acceso denegado: token inválido o no enviado.' }, 401);
    }

    const connStr = (env.nube || '').trim().replace(/^["']|["']$/g, '');
    const host = connStr.split('@')[1]?.split('/')[0];
    if (!connStr || !host) {
      return json({ ok: false, error: 'Falta el secreto "nube" con la connection string.' }, 500);
    }

    async function sql(query, params = []) {
      const r = await fetch(`https://${host}/sql`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Neon-Connection-String': connStr,
        },
        body: JSON.stringify({ query, params }),
      });
      const data = await r.json().catch(() => ({}));
      if (!r.ok || data.message) {
        throw new Error(data.message || `Neon HTTP ${r.status}`);
      }
      return data;
    }

    // Idempotente. Corre en cada request (barato). Migra tablas existentes
    // agregando la columna geom si les falta, y rellenándola desde lat/lon o
    // desde el GeoJSON — así se comportan igual que Control_DA_LERM en QGIS.
    async function asegurarTablas() {
      await sql(`CREATE EXTENSION IF NOT EXISTS postgis`);

      await sql(`CREATE TABLE IF NOT EXISTS puntos (
        id text PRIMARY KEY,
        proy text,
        nombre text,
        detalle text,
        fecha timestamptz,
        lat double precision,
        lon double precision,
        alt_orto double precision,
        exac double precision,
        geom geometry(Point, 4326),
        subido_en timestamptz DEFAULT now()
      )`);
      await sql(`ALTER TABLE puntos ADD COLUMN IF NOT EXISTS geom geometry(Point, 4326)`);
      await sql(`UPDATE puntos SET geom = ST_SetSRID(ST_MakePoint(lon, lat), 4326)
                 WHERE geom IS NULL AND lat IS NOT NULL AND lon IS NOT NULL`);
      await sql(`CREATE INDEX IF NOT EXISTS puntos_geom_idx ON puntos USING GIST (geom)`);

      await sql(`CREATE TABLE IF NOT EXISTS tracks (
        id text PRIMARY KEY,
        proy text,
        nombre text,
        detalle text,
        fecha timestamptz,
        distancia_m double precision,
        n_puntos integer,
        geojson jsonb,
        geom geometry(LineString, 4326),
        subido_en timestamptz DEFAULT now()
      )`);
      await sql(`ALTER TABLE tracks ADD COLUMN IF NOT EXISTS geom geometry(LineString, 4326)`);
      await sql(`UPDATE tracks SET geom = ST_SetSRID(ST_GeomFromGeoJSON(geojson->>'geometry'), 4326)
                 WHERE geom IS NULL AND geojson IS NOT NULL`);
      await sql(`CREATE INDEX IF NOT EXISTS tracks_geom_idx ON tracks USING GIST (geom)`);

      await sql(`CREATE TABLE IF NOT EXISTS fotos (
        id text PRIMARY KEY,
        punto_id text,
        ord integer,
        data_base64 text,
        subido_en timestamptz DEFAULT now()
      )`);
      await sql(`CREATE INDEX IF NOT EXISTS fotos_punto_id_idx ON fotos (punto_id)`);
    }

    try {
      if (request.method === 'POST' && url.pathname === '/puntos') {
        const body = await request.json();
        const puntos = Array.isArray(body.puntos) ? body.puntos : [];
        await asegurarTablas();
        let n = 0;
        for (const p of puntos) {
          await sql(
            `INSERT INTO puntos (id, proy, nombre, detalle, fecha, lat, lon, alt_orto, exac, geom)
             VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9,
                     CASE WHEN $6 IS NULL OR $7 IS NULL THEN NULL
                          ELSE ST_SetSRID(ST_MakePoint($7, $6), 4326) END)
             ON CONFLICT (id) DO UPDATE SET
               nombre = EXCLUDED.nombre,
               detalle = EXCLUDED.detalle,
               fecha = EXCLUDED.fecha,
               lat = EXCLUDED.lat,
               lon = EXCLUDED.lon,
               alt_orto = EXCLUDED.alt_orto,
               exac = EXCLUDED.exac,
               geom = EXCLUDED.geom`,
            [p.id, p.proy || null, p.nombre || '', p.detalle || '', p.fecha || new Date().toISOString(),
             p.lat, p.lon, p.alt_orto ?? null, p.exac ?? null]
          );
          n++;
        }
        return json({ ok: true, recibidos: n });
      }

      if (request.method === 'POST' && url.pathname === '/tracks') {
        const body = await request.json();
        const tracks = Array.isArray(body.tracks) ? body.tracks : [];
        await asegurarTablas();
        let n = 0;
        for (const t of tracks) {
          const gj = t.geojson ? JSON.stringify(t.geojson) : null;
          await sql(
            `INSERT INTO tracks (id, proy, nombre, detalle, fecha, distancia_m, n_puntos, geojson, geom)
             VALUES ($1, $2, $3, $4, $5, $6, $7, $8::jsonb,
                     CASE WHEN $8 IS NULL THEN NULL
                          ELSE ST_SetSRID(ST_GeomFromGeoJSON(($8::jsonb)->>'geometry'), 4326) END)
             ON CONFLICT (id) DO UPDATE SET
               nombre = EXCLUDED.nombre,
               detalle = EXCLUDED.detalle,
               fecha = EXCLUDED.fecha,
               distancia_m = EXCLUDED.distancia_m,
               n_puntos = EXCLUDED.n_puntos,
               geojson = EXCLUDED.geojson,
               geom = EXCLUDED.geom`,
            [t.id, t.proy || null, t.nombre || '', t.detalle || '', t.fecha || new Date().toISOString(),
             t.distancia_m ?? null, t.n_puntos ?? null, gj]
          );
          n++;
        }
        return json({ ok: true, recibidos: n });
      }

      if (request.method === 'POST' && url.pathname === '/fotos') {
        const body = await request.json();
        const fotos = Array.isArray(body.fotos) ? body.fotos : [];
        await asegurarTablas();
        let n = 0;
        for (const f of fotos) {
          await sql(
            `INSERT INTO fotos (id, punto_id, ord, data_base64)
             VALUES ($1, $2, $3, $4)
             ON CONFLICT (id) DO UPDATE SET
               data_base64 = EXCLUDED.data_base64,
               ord = EXCLUDED.ord`,
            [f.id, f.punto_id || null, f.ord ?? 0, f.data_base64 || '']
          );
          n++;
        }
        return json({ ok: true, recibidas: n });
      }

      if (request.method === 'GET' && url.pathname === '/puntos') {
        await asegurarTablas();
        const data = await sql(`SELECT id, proy, nombre, detalle, fecha, lat, lon, alt_orto, exac, subido_en
                                FROM puntos ORDER BY fecha DESC NULLS LAST LIMIT 500`);
        return json({ ok: true, filas: data.rows || data });
      }

      if (request.method === 'GET' && url.pathname === '/tracks') {
        await asegurarTablas();
        const data = await sql(`SELECT id, proy, nombre, detalle, fecha, distancia_m, n_puntos, subido_en
                                FROM tracks ORDER BY fecha DESC NULLS LAST LIMIT 200`);
        return json({ ok: true, filas: data.rows || data });
      }

      return json({ ok: false, error: 'ruta no encontrada: ' + url.pathname }, 404);
    } catch (e) {
      return json({ ok: false, error: e.message || String(e) }, 500);
    }
  },
};
