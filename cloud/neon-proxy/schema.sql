-- GPS TICO — esquema en Neon (Postgres). Corré esto una vez en el SQL Editor de Neon.

create table if not exists puntos (
  id          text primary key,
  proy        text,
  nom         text,
  descripcion text,
  lat         double precision,
  lon         double precision,
  alt_orto    double precision,
  alt_elip    double precision,
  exac        double precision,
  fuente      text,
  tipo        text,
  datos       jsonb,
  promediado  boolean,
  muestras    integer,
  desv_std    double precision,
  n_fotos     integer default 0,
  t           timestamptz,
  subido_en   timestamptz default now()
);

create table if not exists capas (
  id        text primary key,
  nombre    text,
  geojson   jsonb,
  t         timestamptz,
  subido_en timestamptz default now()
);

-- Índices útiles para consultar por proyecto y fecha.
create index if not exists idx_puntos_proy on puntos (proy);
create index if not exists idx_puntos_t    on puntos (t);
