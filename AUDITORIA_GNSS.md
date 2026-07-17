# Auditoría: Qué expone realmente Android para GNSS nativo

**Fecha:** 2026-07-16  
**Teléfono objetivo:** Motorola Edge 50 Fusion (Snapdragon 7s Gen 2, GPS L1+L5, Android 14)  
**Documentación base:** Android LocationManager, GnssStatus (API 24+), NMEA (API 24+)

---

## 1. Lectura de satélites y señal

### GnssStatus.Callback (API 24+)
✅ **Disponible** en `LocationManager.addGnssStatusListener()`.

**Expone:**
- `getSatelliteCount()` — total de satélites rastreados
- `getUsedInFixCount()` — satélites usados en el fix actual (aproximado, depende del chipset)
- `getCn0DbHz(i)` — relación señal/ruido (C/N0) de cada satélite, en dB-Hz
- `getElevationDegrees(i)` — elevación del satélite
- `getAzimuthDegrees(i)` — acimut
- `getSvid(i)` — identificador del satélite (1–32 = GPS, 65–96 = GLONASS, etc.)
- `getConstellationType(i)` — tipo de constelación (`GnssStatus.CONSTELLATION_GPS`, `GLONASS`, `GALILEO`, `BEIDOU`, `SBAS`, `QZSS`, `IRNSS` desde API 29)
- `hasCarrierFrequencyHz(i)` (API 24+) — indica si el satélite reporta frecuencia
- `getCarrierFrequencyHz(i)` (API 24+) — frecuencia portadora en Hz

**Limitaciones (por diseño de Android):**
- No elige qué satélites usar en el fix (el chipset GPS lo decide).
- No fuerza L5, L1C, o ninguna modulación específica.
- No accede al motor interno de posicionamiento del chip (Qualcomm, Broadcom, etc.).
- `getUsedInFixCount()` es aproximado y depende del OEM. En algunos teléfonos siempre coincide con el total; en otros, es una estimación.

**Detección de L5:**
- GPS L5: 1176.45 MHz
- Galileo E5a: 1176.45 MHz
- BeiDou B2a: 1176.45 MHz
- Frecuencias alternativas (L1C, E5b, etc.): varían

**Estrategia:** Si `hasCarrierFrequencyHz(i)` es true y `getCarrierFrequencyHz(i) == 1176450000`, es L5 (o compatible). Contar cuántos de los satélites usados en el fix son L5.

---

## 2. Ubicación y exactitud

### LocationManager.getLastKnownLocation() y listener
✅ **Disponible** con `GPS_PROVIDER` (única lectura GNSS, sin fusión).

**Expone:**
- `getLatitude()`, `getLongitude()` — WGS84 (elipsoidal)
- `getAltitude()` — altura elipsoidal (metros, depende del datum en el chipset, casi siempre WGS84)
- `getAccuracy()` — estimación 1-sigma de exactitud horizontal (metros)
- `getVerticalAccuracyMeters()` (API 26+) — exactitud vertical (metros)
- `getSpeed()` — velocidad (m/s)
- `getSpeedAccuracyMetersPerSecond()` (API 26+) — exactitud de velocidad
- `getBearing()` — rumbo (0–360°)
- `getBearingAccuracyDegrees()` (API 26+) — exactitud del rumbo
- `isMock()` (API 31+) — detecta si la posición es simulada (depuración)
- `getElapsedRealtimeNanos()` — marca de tiempo del fix (nanotime del sistema, NO UTC)
- `getTime()` — marca UTC en milisegundos (depende del chipset y disponibilidad de tiempo)

**Limitaciones:**
- No hay acceso a:
  - PDOP, HDOP, VDOP (hay que parsearlos de NMEA GGA/GSA).
  - Altura ortométrica (elipsoidal siempre); desde API 34, `getMslAltitudeMeters()` si el chipset la calcula.
  - Desviación estándar de cada coordenada (se calcula internamente, no se expone).
  - Información de multipath, refracción ionosférica, u otro diagnóstico interno.

**Proveedor GPS_PROVIDER:**
- Lectura pura GNSS sin fusión con WiFi/celda.
- Primer fix significativamente más lento (hasta 30–60s sin asistencia previa).
- Requiere permiso `ACCESS_FINE_LOCATION`.

---

## 3. NMEA (Locuciones de GNSS)

### addNmeaListener (API 24+)
✅ **Disponible** en `LocationManager`.

**Qué llega:**
- Sentencias NMEA-0183 estándar: GGA, GSA, GSV, RMC, VTG, GLL, etc.
- Formato: `$--<sentence>*<checksum>`

**Útiles para diagnóstico:**

| Sentencia | Información | Nota |
|-----------|-------------|------|
| **GGA** | Hora UTC, lat/lon, calidad del fix (0–8), satélites usados, HDOP, altura elipsoidal, separación geoide | La "separación geoide" es altura ortométrica - altura elipsoidal |
| **GSA** | Modo (Manual/Automático), fix 2D/3D, SV IDs usados, PDOP/HDOP/VDOP | No siempre disponible en todos los chipsets |
| **GSV** | Satélites a la vista, elevación, acimut, C/N0 | Repetido en múltiples frames si hay >4 satélites |
| **RMC** | Hora UTC, posición, velocidad, rumbo, fecha | Redundante con GGA/GSA en nuestro caso |

**Limitaciones de NMEA:**
- No siempre disponible en todos los OEMs.
- Algunos teléfonos (especialmente Xiaomi, Huawei) lo bloquean o limitan sin causa clara.
- No expone constelación en GGA/GSA (hay que infer de los SV IDs en GSA).
- La "calidad del fix" en GGA (0–8) es muy burda: 0=no fix, 1=GPS, 2=DGPS, 4=RTK… pero depende del chipset.

**Estrategia de fallback:**
- Si NMEA no llega, usá solo GnssStatus (que siempre funciona).
- Para altura ortométrica: probá `Location.getMslAltitudeMeters()` (API 34); si no está, parseá la separación geoide de GGA.
- Para DOP: parseá GSA si llega; si no, estimá HDOP desde la dispersión de los últimos 5 fixes (no es científico pero funciona).

---

## 4. Altura ortométrica (elevada a tierra)

### Elevación elipsoidal vs. ortométrica
- **Elipsoidal** (hoy): altura sobre el elipsoide de referencia (WGS84). Lo que devuelve `Location.getAltitude()`.
- **Ortométrica** (la que importa): altura sobre el geoide (nivel medio del mar local). Difiere de la elipsoidal por 10–100 metros según la región.

**En Android 34+ (nuestro target, Motorola Edge 50 Fusion en Android 14 está a 1-2 versiones de distancia):**
- `Location.hasMslAltitude()` — sí/no
- `Location.getMslAltitudeMeters()` — altura ortométrica

**En Android < 34:**
- Parseá de NMEA GGA, campo 9: "separación del geoide" (altura ortométrica - altura elipsoidal).
- Fórmula: altura ortométrica = altura elipsoidal + separación geoide.
- **Cuidado:** en algunos chipsets, este valor llega con unidad equivocada o está siempre en 0.

**En nuestro caso (Costa Rica, proyecciones CRTM05):**
- La separación geoide en CR varía de +0.5m a +2m (aproximadamente).
- Para dictamenes legales, importa la altura ortométrica.
- Hoy estamos reportando altura elipsoidal sin aclaración: **es un error**.

---

## 5. Proveedores de ubicación

### Fused vs. GPS_PROVIDER
- **`FUSED_PROVIDER`** (Google Play Services): fusión de GPS, WiFi y celda. Rápido, preciso, pero no es "GNSS puro".
- **`GPS_PROVIDER`** (LocationManager nativo): solo GNSS. Primer fix lento, pero genuino.
- **`NETWORK_PROVIDER`** (WiFi + celda): no es GNSS.

**Nuestra meta:** GPS_PROVIDER para garantizar que es GNSS.

---

## 6. Diagnóstico: qué SÍ y qué NO podemos hacer

| Necesidad | ¿Se puede? | Cómo / Limitación |
|-----------|-----------|-------------------|
| Contar satélites | ✅ | `GnssStatus.getSatelliteCount()` |
| Saber cuál es L5 | ✅ | `hasCarrierFrequencyHz()` + `getCarrierFrequencyHz()` == 1176.45 MHz |
| Forzar L5 | ❌ | Chipset decide. No hay API. |
| Cielo de satélites (elevación + acimut) | ✅ | `GnssStatus.getElevationDegrees()` + `getAzimuthDegrees()` |
| C/N0 por satélite | ✅ | `GnssStatus.getCn0DbHz()` |
| HDOP / VDOP | ⚠️ | Parseá NMEA GSA; fallback: estimá desde dispersión de fixes |
| Altura ortométrica (geoide) | ⚠️ | Android 34+: `getMslAltitudeMeters()`; antes: parseá GGA |
| Detectar multipath | ❌ | No expuesto. El C/N0 baja, es pista indirecta. |
| Elegir constelaciones | ❌ | Chipset decide. |
| Acceder al motor de fix interno | ❌ | Caja negra (Qualcomm, Broadcom, etc.). |
| Descartar posición falsa (mock) | ✅ | `Location.isMock()` (API 31+) |
| Velocidad + exactitud | ✅ | `getSpeed()` + `getSpeedAccuracyMetersPerSecond()` |
| Rumbo + exactitud | ✅ | `getBearing()` + `getBearingAccuracyDegrees()` |

---

## 7. Dependencias del chipset / OEM

Motorola Edge 50 Fusion: Snapdragon 7s Gen 2 (Qualcomm).
- ✅ Expone GnssStatus (verificado en Android 14).
- ✅ Soporta NMEA (casi siempre).
- ⚠️ La "exactitud vertical" depende de la alineación interna: puede ser 0 o realista.
- ⚠️ La separación geoide en NMEA GGA no siempre es exacta (pueden dejarla en 0).

**Recomendación:** Testear en el dispositivo real y documentar qué llega y qué no.

---

## 8. Conclusión: lo que el roadmap GNSS puede asumir

### Seguro (API 24, Android 14 confirmado)
1. Lectura de satélites y constelaciones.
2. Detección de L5 vía frecuencia portadora.
3. Cielo de satélites (elevación, acimut).
4. C/N0 por satélite.
5. Posición GNSS pura (GPS_PROVIDER).
6. Exactitud horizontal, vertical, velocidad, rumbo.
7. Mock detection.

### Probable (API 26–34, requiere test en dispositivo)
1. NMEA (GSA para DOP, GGA para geoide).
2. Altura ortométrica vía `getMslAltitudeMeters()` (si disponible).

### Imposible (limitación de Android)
1. Forzar L5.
2. Elegir constelaciones.
3. Acceder al motor de fix.
4. Desambiguar multipath vs. ruido de medición.

---

## 9. Próximos pasos

**F0 (Instrumentación)** puede avanzar sin riesgos. Expondrá todo lo que sí tenemos, con UI honesta sobre lo que no tenemos.
