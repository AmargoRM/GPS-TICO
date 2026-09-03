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

    // Autenticación: acepta Authorization: Bearer <token> O ?t=<token> en la URL
    // (para links directos desde QGIS/navegador que no envían headers).
    const auth = request.headers.get('Authorization') || '';
    const tokenHeader = auth.replace(/^Bearer\s+/i, '').trim();
    const tokenQuery = url.searchParams.get('t') || '';
    const token = tokenHeader || tokenQuery;
    if (!env.API_TOKEN || token !== env.API_TOKEN) {
      return json({ ok: false, error: 'Acceso denegado: token inválido o no enviado.' }, 401);
    }

    // GET /foto/:id  — devuelve el JPEG binario para abrir en QGIS/navegador.
    // Usa ?t=TOKEN para autenticar sin headers.
    const mFoto = url.pathname.match(/^\/foto\/(.+)$/);
    if (request.method === 'GET' && mFoto) {
      const connStr2 = (env.nube || '').trim().replace(/^["']|["']$/g, '');
      const host2 = connStr2.split('@')[1]?.split('/')[0];
      const rr = await fetch(`https://${host2}/sql`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Neon-Connection-String': connStr2 },
        body: JSON.stringify({
          query: `SELECT data_base64, nombre_punto, tomada_en FROM fotos WHERE id = $1`,
          params: [decodeURIComponent(mFoto[1])]
        })
      });
      const dd = await rr.json().catch(() => ({}));
      const rows = dd.rows || dd || [];
      if (!rows.length || !rows[0].data_base64) {
        return new Response('Foto no encontrada', { status: 404, headers: cors });
      }
      const b64 = rows[0].data_base64;
      // Decodificar base64 a bytes (Uint8Array).
      const bin = Uint8Array.from(atob(b64), c => c.charCodeAt(0));
      const nombreArch = 'foto_' + (rows[0].nombre_punto || decodeURIComponent(mFoto[1])).replace(/[^\w\-]/g,'_') + '.jpg';
      return new Response(bin, {
        headers: {
          ...cors,
          'Content-Type': 'image/jpeg',
          'Content-Disposition': 'inline; filename="' + nombreArch + '"',
          'Cache-Control': 'public, max-age=3600',
        },
      });
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
        lat double precision,
        lon double precision,
        nombre_punto text,
        tomada_en timestamptz,
        geom geometry(Point, 4326),
        subido_en timestamptz DEFAULT now()
      )`);
      // Migración: agregar columnas a la tabla si ya existía sin ellas.
      await sql(`ALTER TABLE fotos ADD COLUMN IF NOT EXISTS lat double precision`);
      await sql(`ALTER TABLE fotos ADD COLUMN IF NOT EXISTS lon double precision`);
      await sql(`ALTER TABLE fotos ADD COLUMN IF NOT EXISTS nombre_punto text`);
      await sql(`ALTER TABLE fotos ADD COLUMN IF NOT EXISTS tomada_en timestamptz`);
      await sql(`ALTER TABLE fotos ADD COLUMN IF NOT EXISTS geom geometry(Point, 4326)`);
      // Rellenar geom desde lat/lon si es NULL.
      await sql(`UPDATE fotos SET geom = ST_SetSRID(ST_MakePoint(lon, lat), 4326)
                 WHERE geom IS NULL AND lat IS NOT NULL AND lon IS NOT NULL`);
      // Rellenar lat/lon/nombre_punto desde el punto padre (para fotos viejas).
      await sql(`UPDATE fotos f SET lat = p.lat, lon = p.lon, nombre_punto = p.nombre, tomada_en = p.fecha,
                                     geom = ST_SetSRID(ST_MakePoint(p.lon, p.lat), 4326)
                 FROM puntos p
                 WHERE f.punto_id = p.id AND f.lat IS NULL AND p.lat IS NOT NULL`);
      await sql(`CREATE INDEX IF NOT EXISTS fotos_punto_id_idx ON fotos (punto_id)`);
      await sql(`CREATE INDEX IF NOT EXISTS fotos_geom_idx ON fotos USING GIST (geom)`);
      // fotos_geo se elimina: la información de fotos ahora va JOIN'd
      // directamente en las vistas de puntos (n_fotos + foto_url).
      await sql(`DROP VIEW IF EXISTS fotos_geo`);

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

      // Limpiar versiones anteriores de la función (firmas viejas quedaban).
      await sql(`DROP FUNCTION IF EXISTS crear_vistas_por_dia()`);
      await sql(`DROP FUNCTION IF EXISTS crear_vistas_por_dia(text)`);

      // Funciones INSTEAD OF DELETE para las vistas: cuando borrás features
      // desde QGIS en una vista (por día o puntos_geo), se propaga a la tabla
      // base y ahí se dispara el trigger de la papelera. Sin esto, borrar en
      // una vista con JOIN no elimina nada y los datos "reaparecen".
      await sql(`CREATE OR REPLACE FUNCTION _v_del_punto() RETURNS trigger AS $$
        BEGIN DELETE FROM puntos WHERE id = OLD.id; RETURN OLD; END; $$ LANGUAGE plpgsql`);
      await sql(`CREATE OR REPLACE FUNCTION _v_del_track() RETURNS trigger AS $$
        BEGIN DELETE FROM tracks WHERE id = OLD.id; RETURN OLD; END; $$ LANGUAGE plpgsql`);

      // Función helper para borrar un día entero desde el SQL Editor de QGIS/Neon.
      // Uso: SELECT borrar_dia('2026-07-24');
      // Borra puntos y tracks cuya fecha (hora CR) sea ese día. Los triggers
      // se encargan de registrar los IDs en la papelera.
      await sql(`CREATE OR REPLACE FUNCTION borrar_dia(dia date) RETURNS text AS $$
        DECLARE nP integer; nT integer;
        BEGIN
          DELETE FROM puntos WHERE DATE(fecha AT TIME ZONE 'America/Costa_Rica') = dia;
          GET DIAGNOSTICS nP = ROW_COUNT;
          DELETE FROM tracks WHERE DATE(fecha AT TIME ZONE 'America/Costa_Rica') = dia;
          GET DIAGNOSTICS nT = ROW_COUNT;
          RETURN 'Borrados ' || nP || ' punto(s) y ' || nT || ' track(s) del ' || dia;
        END; $$ LANGUAGE plpgsql`);

      // EVENT TRIGGER (Postgres): captura DROP VIEW puntos_YYYY_MM_DD /
      // tracks_YYYY_MM_DD. Cuando el user borra la vista desde QGIS con
      // "Borrar capa", también se borran los datos de ese día. Se ignora
      // silenciosamente si el rol no tiene permisos para crear event triggers.
      try {
        await sql(`CREATE OR REPLACE FUNCTION _et_al_borrar_vista() RETURNS event_trigger AS $$
          DECLARE r record; fecha_str text; dia date;
          BEGIN
            -- Si el DROP VIEW proviene del refresco interno de vistas del
            -- Worker (no de un "Borrar capa" del usuario en QGIS), NO borrar
            -- datos: crear_vistas_por_dia() marca esta bandera local a la
            -- transacción antes de soltar sus propias vistas para recrearlas.
            -- Sin esto, cada subida de un punto borraba todos los puntos del
            -- día al recrearse la vista de ese día.
            IF current_setting('app.refreshing_views', true) = 'on' THEN
              RETURN;
            END IF;
            FOR r IN SELECT object_name FROM pg_event_trigger_dropped_objects() WHERE object_type = 'view' LOOP
              IF r.object_name ~ '^puntos_[0-9]{4}_[0-9]{2}_[0-9]{2}$' THEN
                fecha_str := replace(substring(r.object_name from 8), '_', '-');
                BEGIN
                  dia := fecha_str::date;
                  DELETE FROM puntos WHERE DATE(fecha AT TIME ZONE 'America/Costa_Rica') = dia;
                EXCEPTION WHEN OTHERS THEN NULL; END;
              ELSIF r.object_name ~ '^tracks_[0-9]{4}_[0-9]{2}_[0-9]{2}$' THEN
                fecha_str := replace(substring(r.object_name from 8), '_', '-');
                BEGIN
                  dia := fecha_str::date;
                  DELETE FROM tracks WHERE DATE(fecha AT TIME ZONE 'America/Costa_Rica') = dia;
                EXCEPTION WHEN OTHERS THEN NULL; END;
              END IF;
            END LOOP;
          END;
          $$ LANGUAGE plpgsql`);
        await sql(`DROP EVENT TRIGGER IF EXISTS _et_borrar_vista_dia`);
        await sql(`CREATE EVENT TRIGGER _et_borrar_vista_dia ON sql_drop
                   WHEN TAG IN ('DROP VIEW')
                   EXECUTE FUNCTION _et_al_borrar_vista()`);
      } catch (e) { /* Neon puede no permitir EVENT TRIGGER al owner; usar borrar_dia() */ }
      // Función que crea/actualiza:
      //   - VIEW puntos_geo (todos los puntos + n_fotos + foto_url + fotos_urls)
      //   - Una VIEW por día para puntos y otra para tracks
      // Las URLs se construyen como: prefix || id || suffix   → así se puede
      // embeber ?t=TOKEN en el suffix sin exponer el token en el schema aparte.
      // Usa DROP+CREATE (no CREATE OR REPLACE) porque las columnas cambian.
      await sql(`CREATE OR REPLACE FUNCTION crear_vistas_por_dia(prefix text, suffix text) RETURNS void AS $fn$
        DECLARE d date; vname text;
        BEGIN
          -- Marca (local a esta transacción) para avisar al EVENT TRIGGER
          -- _et_al_borrar_vista que los DROP VIEW que siguen son un refresco
          -- interno y NO debe borrar los datos del día. El "Borrar capa" real
          -- desde QGIS corre en otra transacción, sin esta bandera, y sí borra.
          PERFORM set_config('app.refreshing_views', 'on', true);
          EXECUTE format('DROP VIEW IF EXISTS puntos_geo CASCADE');
          EXECUTE format(
            'CREATE VIEW puntos_geo AS
             SELECT p.*, COALESCE(fc.n_fotos, 0) AS n_fotos,
                    fc.foto_url, fc.fotos_urls
             FROM puntos p
             LEFT JOIN (
               SELECT punto_id,
                      count(*) AS n_fotos,
                      MIN(%L || id || %L) AS foto_url,
                      string_agg(%L || id || %L, '' | '' ORDER BY ord) AS fotos_urls
               FROM fotos WHERE punto_id IS NOT NULL
               GROUP BY punto_id
             ) fc ON fc.punto_id = p.id',
            prefix, suffix, prefix, suffix);
          -- INSTEAD OF DELETE en la vista global de puntos.
          EXECUTE 'CREATE TRIGGER _trg_v_del_puntos_geo INSTEAD OF DELETE ON puntos_geo
                   FOR EACH ROW EXECUTE FUNCTION _v_del_punto()';

          FOR d IN SELECT DISTINCT DATE(fecha AT TIME ZONE 'America/Costa_Rica')
                   FROM puntos WHERE fecha IS NOT NULL LOOP
            vname := 'puntos_' || to_char(d, 'YYYY_MM_DD');
            EXECUTE format('DROP VIEW IF EXISTS %I CASCADE', vname);
            EXECUTE format(
              'CREATE VIEW %I AS
               SELECT p.*, COALESCE(fc.n_fotos, 0) AS n_fotos,
                      fc.foto_url, fc.fotos_urls
               FROM puntos p
               LEFT JOIN (
                 SELECT punto_id,
                        count(*) AS n_fotos,
                        MIN(%L || id || %L) AS foto_url,
                        string_agg(%L || id || %L, '' | '' ORDER BY ord) AS fotos_urls
                 FROM fotos WHERE punto_id IS NOT NULL
                 GROUP BY punto_id
               ) fc ON fc.punto_id = p.id
               WHERE DATE(p.fecha AT TIME ZONE ''America/Costa_Rica'') = %L',
              vname, prefix, suffix, prefix, suffix, d);
            -- Trigger: borrar desde la vista propaga a la tabla puntos.
            EXECUTE format(
              'CREATE TRIGGER _trg_v_del INSTEAD OF DELETE ON %I
               FOR EACH ROW EXECUTE FUNCTION _v_del_punto()', vname);
          END LOOP;
          FOR d IN SELECT DISTINCT DATE(fecha AT TIME ZONE 'America/Costa_Rica')
                   FROM tracks WHERE fecha IS NOT NULL LOOP
            vname := 'tracks_' || to_char(d, 'YYYY_MM_DD');
            EXECUTE format('DROP VIEW IF EXISTS %I CASCADE', vname);
            EXECUTE format(
              'CREATE VIEW %I AS SELECT * FROM tracks
               WHERE DATE(fecha AT TIME ZONE ''America/Costa_Rica'') = %L',
              vname, d);
            EXECUTE format(
              'CREATE TRIGGER _trg_v_del INSTEAD OF DELETE ON %I
               FOR EACH ROW EXECUTE FUNCTION _v_del_track()', vname);
          END LOOP;
        END;
        $fn$ LANGUAGE plpgsql`);
    }

    // prefijo y sufijo para armar URLs de foto: prefix + id + suffix
    // → https://nube.xxx.workers.dev/foto/  +  ID  +  ?t=TOKEN
    const URL_FOTO_PREFIX = url.origin + '/foto/';
    const URL_FOTO_SUFFIX = '?t=' + encodeURIComponent(env.API_TOKEN || '');
    async function refrescarVistasPorDia() {
      try { await sql(`SELECT crear_vistas_por_dia($1::text, $2::text)`,
                      [URL_FOTO_PREFIX, URL_FOTO_SUFFIX]); }
      catch (e) { /* no bloquea */ }
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
        // Inserta un track. Si `conGeom` es false, guarda geom NULL (para tracks
        // cuya geometría no es una LineString válida — 0 o 1 vértice).
        async function insertarTrack(t, gj, conGeom) {
          const geomExpr = conGeom
            ? `CASE WHEN $8::text IS NULL THEN NULL
                    ELSE ST_SetSRID(ST_GeomFromGeoJSON(($8::jsonb)->>'geometry'), 4326) END`
            : `NULL`;
          await sql(
            `INSERT INTO tracks (id, proy, nombre, detalle, fecha, distancia_m, n_puntos, geojson, geom)
             VALUES ($1::text, $2::text, $3::text, $4::text, $5::timestamptz,
                     $6::double precision, $7::integer, $8::jsonb, ${geomExpr})
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
        }
        let n = 0, ignorados = 0, fallidos = 0; const errores = [];
        for (const t of tracks) {
          if (borrados.has(t.id)) { ignorados++; continue; }
          const gj = t.geojson ? JSON.stringify(t.geojson) : null;
          try {
            await insertarTrack(t, gj, true);
            n++;
          } catch (e) {
            // Una geometría inválida (LineString con <2 vértices, etc.) NO debe
            // tumbar todo el lote ni bloquear a los demás tracks del día:
            // reintentamos guardando el track SIN geometría (geom NULL).
            try {
              await insertarTrack(t, gj, false);
              n++;
            } catch (e2) {
              fallidos++;
              if (errores.length < 5) errores.push(String(t.id) + ': ' + (e2.message || e2));
            }
          }
        }
        await refrescarVistasPorDia();
        return json({ ok: true, recibidos: n, fallidos, errores, ignorados_borrados: ignorados });
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
            `INSERT INTO fotos (id, punto_id, ord, data_base64, lat, lon, nombre_punto, tomada_en, geom)
             VALUES ($1::text, $2::text, $3::integer, $4::text,
                     $5::double precision, $6::double precision, $7::text, $8::timestamptz,
                     CASE WHEN $5::double precision IS NULL OR $6::double precision IS NULL THEN NULL
                          ELSE ST_SetSRID(ST_MakePoint($6::double precision, $5::double precision), 4326) END)
             ON CONFLICT (id) DO UPDATE SET
               data_base64 = EXCLUDED.data_base64,
               ord = EXCLUDED.ord,
               lat = EXCLUDED.lat,
               lon = EXCLUDED.lon,
               nombre_punto = EXCLUDED.nombre_punto,
               tomada_en = EXCLUDED.tomada_en,
               geom = EXCLUDED.geom`,
            [f.id, f.punto_id || null, f.ord ?? 0, f.data_base64 || '',
             f.lat ?? null, f.lon ?? null, f.nombre_punto || null, f.tomada_en || null]
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
