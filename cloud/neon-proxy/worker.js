// GPS TICO — Proxy a Neon (Cloudflare Worker)
// Versión SIN dependencias externas: se puede pegar y desplegar directo desde
// el editor del dashboard de Cloudflare. Habla con Neon vía su endpoint HTTP.
//
// Secretos que espera (Settings → Variables and secrets → Add):
//   nube       → la connection string de Neon (postgresql://usuario:pass@host/db?...)
//   API_TOKEN  → un token largo que inventás vos y también ponés en la app
//
// Endpoints:
//   GET  /ping     → salud (sin token, para "Probar conexión")
//   POST /puntos   → { puntos: [...] }  inserta en la tabla puntos
//   POST /tracks   → { tracks: [...] }  inserta en la tabla tracks
//   GET  /puntos   → lista los últimos 500 puntos (útil para QGIS)
//   GET  /tracks   → lista los últimos 200 tracks
// Todos los POST y GET (excepto /ping) requieren Authorization: Bearer <API_TOKEN>

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

    // Salud pública, sin token. La app la usa para "Probar conexión".
    if (request.method === 'GET' && url.pathname === '/ping') {
      return json({ ok: true, ts: Date.now() });
    }

    // Autenticación por token compartido.
    const auth = request.headers.get('Authorization') || '';
    const token = auth.replace(/^Bearer\s+/i, '').trim();
    if (!env.API_TOKEN || token !== env.API_TOKEN) {
      return json({ ok: false, error: 'Acceso denegado: token inválido o no enviado.' }, 401);
    }

    // Conexión a Neon (endpoint HTTP directo, sin driver).
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
      // Neon devuelve {message: "..."} cuando hay error SQL, con HTTP 4xx/5xx.
      if (!r.ok || data.message) {
        throw new Error(data.message || `Neon HTTP ${r.status}`);
      }
      return data;
    }

    // Crea las tablas si no existen. Idempotente y barato.
    async function asegurarTablas() {
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
        subido_en timestamptz DEFAULT now()
      )`);
      await sql(`CREATE TABLE IF NOT EXISTS tracks (
        id text PRIMARY KEY,
        proy text,
        nombre text,
        detalle text,
        fecha timestamptz,
        distancia_m double precision,
        n_puntos integer,
        geojson jsonb,
        subido_en timestamptz DEFAULT now()
      )`);
    }

    try {
      if (request.method === 'POST' && url.pathname === '/puntos') {
        const body = await request.json();
        const puntos = Array.isArray(body.puntos) ? body.puntos : [];
        await asegurarTablas();
        let n = 0;
        for (const p of puntos) {
          await sql(
            `INSERT INTO puntos (id, proy, nombre, detalle, fecha, lat, lon, alt_orto, exac)
             VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
             ON CONFLICT (id) DO UPDATE SET
               nombre = EXCLUDED.nombre,
               detalle = EXCLUDED.detalle,
               fecha = EXCLUDED.fecha,
               lat = EXCLUDED.lat,
               lon = EXCLUDED.lon,
               alt_orto = EXCLUDED.alt_orto,
               exac = EXCLUDED.exac`,
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
          await sql(
            `INSERT INTO tracks (id, proy, nombre, detalle, fecha, distancia_m, n_puntos, geojson)
             VALUES ($1, $2, $3, $4, $5, $6, $7, $8::jsonb)
             ON CONFLICT (id) DO UPDATE SET
               nombre = EXCLUDED.nombre,
               detalle = EXCLUDED.detalle,
               fecha = EXCLUDED.fecha,
               distancia_m = EXCLUDED.distancia_m,
               n_puntos = EXCLUDED.n_puntos,
               geojson = EXCLUDED.geojson`,
            [t.id, t.proy || null, t.nombre || '', t.detalle || '', t.fecha || new Date().toISOString(),
             t.distancia_m ?? null, t.n_puntos ?? null,
             t.geojson ? JSON.stringify(t.geojson) : null]
          );
          n++;
        }
        return json({ ok: true, recibidos: n });
      }

      if (request.method === 'GET' && url.pathname === '/puntos') {
        await asegurarTablas();
        const data = await sql(`SELECT * FROM puntos ORDER BY fecha DESC NULLS LAST LIMIT 500`);
        return json({ ok: true, filas: data.rows || data });
      }

      if (request.method === 'GET' && url.pathname === '/tracks') {
        await asegurarTablas();
        const data = await sql(`SELECT id, proy, nombre, detalle, fecha, distancia_m, n_puntos, subido_en FROM tracks ORDER BY fecha DESC NULLS LAST LIMIT 200`);
        return json({ ok: true, filas: data.rows || data });
      }

      return json({ ok: false, error: 'ruta no encontrada: ' + url.pathname }, 404);
    } catch (e) {
      return json({ ok: false, error: e.message || String(e) }, 500);
    }
  },
};
