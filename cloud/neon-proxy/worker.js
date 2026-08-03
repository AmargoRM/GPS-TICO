// GPS TICO — Proxy a Neon (Cloudflare Worker)
// La app NUNCA conoce la cadena de conexión de Neon: solo esta URL + un token.
// Secretos (se cargan con `wrangler secret put`):
//   DATABASE_URL  → la connection string de Neon (postgresql://...)
//   API_TOKEN     → un token largo que inventás vos y también ponés en la app
import { neon } from '@neondatabase/serverless';

const cors = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
  'Access-Control-Allow-Headers': 'authorization,content-type',
};
function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: { 'Content-Type': 'application/json', ...cors } });
}

export default {
  async fetch(req, env) {
    if (req.method === 'OPTIONS') return new Response(null, { headers: cors });
    const url = new URL(req.url);

    // Salud: sin token, para "Probar conexión" desde la app.
    if (req.method === 'GET' && url.pathname === '/ping') return json({ ok: true, ts: Date.now() });

    // Autenticación por token.
    const token = (req.headers.get('authorization') || '').replace(/^Bearer\s+/i, '');
    if (!env.API_TOKEN || token !== env.API_TOKEN) return json({ error: 'no autorizado' }, 401);

    const sql = neon(env.DATABASE_URL);
    try {
      if (req.method === 'POST' && url.pathname === '/puntos') {
        const { puntos = [] } = await req.json();
        for (const p of puntos) {
          await sql`insert into puntos
            (id, proy, nom, descripcion, lat, lon, alt_orto, alt_elip, exac, fuente, tipo, datos, promediado, muestras, desv_std, n_fotos, t)
            values (${p.id}, ${p.proy}, ${p.nom}, ${p.desc || ''}, ${p.lat}, ${p.lon}, ${p.alt_orto}, ${p.alt_elip},
                    ${p.exac}, ${p.fuente}, ${p.tipo}, ${p.datos ? JSON.stringify(p.datos) : null}::jsonb,
                    ${!!p.promediado}, ${p.muestras}, ${p.desv_std}, ${p.n_fotos || 0}, ${p.t})
            on conflict (id) do update set
              nom = excluded.nom, descripcion = excluded.descripcion, lat = excluded.lat, lon = excluded.lon,
              alt_orto = excluded.alt_orto, alt_elip = excluded.alt_elip, exac = excluded.exac,
              tipo = excluded.tipo, datos = excluded.datos, n_fotos = excluded.n_fotos, t = excluded.t`;
        }
        return json({ ok: true, recibidos: puntos.length });
      }

      if (req.method === 'POST' && url.pathname === '/capas') {
        const { capas = [] } = await req.json();
        for (const c of capas) {
          await sql`insert into capas (id, nombre, geojson, t)
            values (${c.id}, ${c.nombre}, ${JSON.stringify(c.geojson)}::jsonb, ${c.t})
            on conflict (id) do update set nombre = excluded.nombre, geojson = excluded.geojson, t = excluded.t`;
        }
        return json({ ok: true, recibidas: capas.length });
      }

      return json({ error: 'ruta no encontrada' }, 404);
    } catch (e) {
      return json({ error: String((e && e.message) || e) }, 500);
    }
  },
};
