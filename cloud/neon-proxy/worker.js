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

      // Papelera de IDs: si el usuario borra un punto/track desde Neon (o QGIS),
      // el ID queda acá y ya no se acepta nunca más en un POST (silenciosamente
      // ignorado). La app también puede consultar /borrados para eliminar
      // esos datos localmente y no ocupar espacio.
      await sql(`CREATE TABLE IF NOT EXISTS puntos_borrados (
        id text PRIMARY KEY, borrado_en timestamptz DEFAULT now())`);
      await sql(`CREATE TABLE IF NOT EXISTS tracks_borrados (
        id text PRIMARY KEY, borrado_en timestamptz DEFAULT now())`);

      await sql(`CREATE OR REPLACE FUNCTION _reg_punto_borrado() RETURNS trigger AS $tr$
        BEGIN INSERT INTO puntos_borrados (id) VALUES (OLD.id) ON CONFLICT DO NOTHING; RETURN OLD; END; $tr$ LANGUAGE plpgsql`);
      await sql(`CREATE OR REPLACE FUNCTION _reg_track_borrado() RETURNS trigger AS $tr$
        BEGIN INSERT INTO tracks_borrados (id) VALUES (OLD.id) ON CONFLICT DO NOTHING; RETURN OLD; END; $tr$ LANGUAGE plpgsql`);
      // También borra fotos huérfanas cuando se borra un punto.
      await sql(`CREATE OR REPLACE FUNCTION _limpiar_fotos_de_punto() RETURNS trigger AS $tr$
        BEGIN DELETE FROM fotos WHERE punto_id = OLD.id; RETURN OLD; END; $tr$ LANGUAGE plpgsql`);

      await sql(`DROP TRIGGER IF EXISTS trg_punto_borrado ON puntos`);
      await sql(`CREATE TRIGGER trg_punto_borrado AFTER DELETE ON puntos
                 FOR EACH ROW EXECUTE FUNCTION _reg_punto_borrado()`);
      await sql(`DROP TRIGGER IF EXISTS trg_track_borrado ON tracks`);
      await sql(`CREATE TRIGGER trg_track_borrado AFTER DELETE ON tracks
                 FOR EACH ROW EXECUTE FUNCTION _reg_track_borrado()`);
      await sql(`DROP TRIGGER IF EXISTS trg_punto_fotos ON puntos`);
      await sql(`CREATE TRIGGER trg_punto_fotos AFTER DELETE ON puntos
                 FOR EACH ROW EXECUTE FUNCTION _limpiar_fotos_de_punto()`);

      // Función que crea/actualiza una vista por día con los datos: puntos_YYYY_MM_DD
      // y tracks_YYYY_MM_DD (zona Costa Rica). QGIS las lista como capas separadas.
      await sql(`CREATE OR REPLACE FUNCTION crear_vistas_por_dia() RETURNS void AS $fn$
        DECLARE d date; vname text;
        BEGIN
          FOR d IN SELECT DISTINCT DATE(fecha AT TIME ZONE 'America/Costa_Rica')
                   FROM puntos WHERE fecha IS NOT NULL LOOP
            vname := 'puntos_' || to_char(d, 'YYYY_MM_DD');
            EXECUTE format(
              'CREATE OR REPLACE VIEW %I AS SELECT * FROM puntos
               WHERE DATE(fecha AT TIME ZONE ''America/Costa_Rica'') = %L',
              vname, d);
          END LOOP;
          FOR d IN SELECT DISTINCT DATE(fecha AT TIME ZONE 'America/Costa_Rica')
                   FROM tracks WHERE fecha IS NOT NULL LOOP
            vname := 'tracks_' || to_char(d, 'YYYY_MM_DD');
            EXECUTE format(
              'CREATE OR REPLACE VIEW %I AS SELECT * FROM tracks
               WHERE DATE(fecha AT TIME ZONE ''America/Costa_Rica'') = %L',
              vname, d);
          END LOOP;
        END;
        $fn$ LANGUAGE plpgsql`);
    }

    // Refresca las vistas por día. Se llama al final de cada POST exitoso
    // para que la vista del día aparezca sola en QGIS sin intervención.
    async function refrescarVistasPorDia() {
      try { await sql(`SELECT crear_vistas_por_dia()`); } catch (e) { /* no bloquea */ }
    }

    try {
      if (request.method === 'POST' && url.pathname === '/puntos') {
        const body = await request.json();
        const puntos = Array.isArray(body.puntos) ? body.puntos : [];
        await asegurarTablas();
        // Filtrar IDs que ya fueron borrados: NUNCA se vuelven a aceptar.
        const ids = puntos.map(p => p.id).filter(Boolean);
        let borrados = new Set();
        if (ids.length) {
          const r = await sql(
            `SELECT id FROM puntos_borrados WHERE id = ANY($1::text[])`, [ids]);
          for (const row of (r.rows || r)) borrados.add(row.id);
        }
        let n = 0, ignorados = 0;
        for (const p of puntos) {
          if (borrados.has(p.id)) { ignorados++; continue; }
          // Casts explícitos: sin ellos Postgres no infiere el tipo cuando
          // el parámetro es NULL dentro de un CASE (falla con "could not
          // determine data type of parameter $N").
          await sql(
            `INSERT INTO puntos (id, proy, nombre, detalle, fecha, lat, lon, alt_orto, exac, geom)
             VALUES ($1::text, $2::text, $3::text, $4::text, $5::timestamptz,
                     $6::double precision, $7::double precision,
                     $8::double precision, $9::double precision,
                     CASE WHEN $6::double precision IS NULL OR $7::double precision IS NULL THEN NULL
                          ELSE ST_SetSRID(ST_MakePoint($7::double precision, $6::double precision), 4326) END)
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
        await refrescarVistasPorDia();
        return json({ ok: true, recibidos: n, ignorados_borrados: ignorados });
      }

      if (request.method === 'POST' && url.pathname === '/tracks') {
        const body = await request.json();
        const tracks = Array.isArray(body.tracks) ? body.tracks : [];
        await asegurarTablas();
        const ids = tracks.map(t => t.id).filter(Boolean);
        let borrados = new Set();
        if (ids.length) {
          const r = await sql(
            `SELECT id FROM tracks_borrados WHERE id = ANY($1::text[])`, [ids]);
          for (const row of (r.rows || r)) borrados.add(row.id);
        }
        let n = 0, ignorados = 0;
        for (const t of tracks) {
          if (borrados.has(t.id)) { ignorados++; continue; }
          const gj = t.geojson ? JSON.stringify(t.geojson) : null;
          await sql(
            `INSERT INTO tracks (id, proy, nombre, detalle, fecha, distancia_m, n_puntos, geojson, geom)
             VALUES ($1::text, $2::text, $3::text, $4::text, $5::timestamptz,
                     $6::double precision, $7::integer, $8::jsonb,
                     CASE WHEN $8::text IS NULL THEN NULL
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
        await refrescarVistasPorDia();
        return json({ ok: true, recibidos: n, ignorados_borrados: ignorados });
      }

      // Lista de IDs borrados desde la última consulta. La app la usa para
      // eliminar puntos/tracks localmente y liberar espacio.
      if (request.method === 'GET' && url.pathname === '/borrados') {
        await asegurarTablas();
        const p = await sql(`SELECT id FROM puntos_borrados`);
        const t = await sql(`SELECT id FROM tracks_borrados`);
        return json({
          ok: true,
          puntos: (p.rows || p).map(r => r.id),
          tracks: (t.rows || t).map(r => r.id),
        });
      }

      if (request.method === 'POST' && url.pathname === '/fotos') {
        const body = await request.json();
        const fotos = Array.isArray(body.fotos) ? body.fotos : [];
        await asegurarTablas();
        let n = 0;
        for (const f of fotos) {
          await sql(
            `INSERT INTO fotos (id, punto_id, ord, data_base64)
             VALUES ($1::text, $2::text, $3::integer, $4::text)
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
