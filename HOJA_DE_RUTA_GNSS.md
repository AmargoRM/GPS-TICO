# Hoja de ruta: GNSS nativo en GPS TICO

**Versión:** 0.1  
**Inicio:** 2026-07-16  
**Regla de oro:** una fase por sesión. No adelantes sin cerrar criterios de aceptación.

---

## Resumen ejecutivo

Hoy la app lee posición del WebView (`navigator.geolocation`), que fusiona GPS + WiFi + celda. Queremos GNSS puro: solo satélites, con diagnóstico de calidad en tiempo real (satélites, L5, DOP, altura ortométrica, ruido de medición). 

El roadmap tiene 5 fases, cada una agregando una capa de fidelidad sin romper datos anteriores.

---

## Fase 0: INSTRUMENTACIÓN (Diagnóstico en tiempo real)

**Objetivo:** Exponer todo lo que Android permite sobre satélites y exactitud, sin cambiar ninguna coordenada que se guarde.

**Qué se agrega:**
- Plugin Kotlin (`GnssInstrumentationPlugin`) que Lee:
  - `GnssStatus.Callback`: satélites totales, usados en fix, C/N0, elevación, acimut, constelación.
  - Detección de L5 (frecuencia 1176.45 MHz).
  - `addNmeaListener`: parseá GSA (PDOP/HDOP/VDOP) y GGA (geoide).
  - `Location`: provider usado, exactitud vertical/velocidad/rumbo.
  - Marca de tiempo: detecta si es UTC o nanotime del sistema.
  - Mock detection (`isMock()`).
  
- UI en la app:
  - Pantalla de diagnóstico: cielo de satélites (gráfico polar: elevación + acimut).
  - Barras de C/N0 por satélite, código de color por constelación (GPS azul, Galileo naranja, GLONASS rojo, BeiDou verde).
  - Contador de L5 en vivo ("2/8 satélites son L5").
  - PDOP/HDOP/VDOP en tiempo real.
  - Proveedor activo (hoy: fused; en F1: GPS puro).
  - Exactitud vertical (metros, con advertencia si es 0 o >= 100m).

- Exportación:
  - Botón "Exportar diagnóstico": descarga CSV con timestamp, satélites, C/N0, PDOP, HDOP, VDOP, provider, exactitud.
  - Formatear para comparar contra Garmin Montana 680 o similar.

**Cambios a datos guardados:**
- Ninguno. Los proyectos y tracks se guardan igual.
- La pantalla de diagnóstico es read-only.

**Criterio de aceptación:**
- [ ] Puedo pararme en un punto de referencia conocido (ej. terraza de casa) y exportar 5 minutos de datos.
- [ ] El CSV muestra: timestamp, latitud, longitud, altura, exactitud horiz/vert, satélites totales, satélites usados, C/N0 mín/máx, L5 count, HDOP, VDOP, provider.
- [ ] El Garmin Montana y el Motorola muestran satélites similares en el mismo punto.
- [ ] La pantalla de cielo de satélites es legible (no se sobreponen los textos, escala clara).

**Riesgos identificados:**
- NMEA puede no llegar en algunos Android / OEM. Fallback: calcular HDOP desde dispersión de fixes (lo haremos si es necesario).
- La exactitud vertical puede estar siempre en 0. Si pasa, documentarlo y proponer alternativa en F2.
- Timestamp: si el chipset no tiene hora UTC, usaremos nanotime y aclaramos que no es UTC.

**Tiempo estimado:** ~2 semanas (Kotlin + Compose UI + CSV export).

**¿Qué no toca?**
- Coordenadas guardadas: siguen siendo WGS84 elipsoidal.
- Proyecciones (CRTM05): no se usan en F0.
- Tracks: sin cambios.

---

## Fase 1: GPS PURO

**Prerequisito:** F0 completa (criterios de aceptación cerrados).

**Objetivo:** Cambiar de provider fused a GPS_PROVIDER. Exponer todas las exactitudes.

**Qué cambia:**
- `LocationManager.requestLocationUpdates()` con `GPS_PROVIDER` (en vez de `FUSED_PROVIDER`).
- Exponé:
  - `Location.getVerticalAccuracyMeters()` (exactitud vertical).
  - `Location.getSpeedAccuracyMetersPerSecond()`.
  - `Location.getBearingAccuracyDegrees()`.
  - `Location.isMock()`: descarta posiciones falsas si devuelve true.
  
- UI: agregar checkbox "GPS puro" / "Fused". Hoy es fused (default); F1 cierra en GPS puro.

**Cambios a datos guardados:**
- Los puntos nuevos tendrán exactitud vertical. Los viejos (fused) no.
- Esquema JSON de punto: agregar campo opcional `verticalAccuracy` (número, metros; null si no disponible).
- IndexedDB: migración automática, sin perder datos.

**Criterio de aceptación:**
- [ ] Exactitud vertical reportada en pantalla (ej. "±2.5 m" o "no disponible").
- [ ] Primer fix GPS puro tarda X segundos (medir y documentar).
- [ ] Comparar drift de un punto fijo: fused vs. GPS puro en 5 minutos. GPS puro más ruidoso pero más "honesto".
- [ ] Puntos guardados distinguen entre fused y GPS puro en el CSV export.

**Riesgos identificados:**
- Primer fix muy lento (30–60s sin AGPS). Esto es esperado, pero puede ser frustrante.
- GPS puro es más ruidoso que fused. Los tracks tendrán más "zig-zag". Es correcto pero visualmente peor.

**Tiempo estimado:** ~1 semana (cambio de provider + migración IndexedDB + export).

**¿Qué no toca?**
- Altura: sigue siendo elipsoidal.
- Proyecciones CRTM05: no usadas aún.

---

## Fase 2: ALTURA ORTOMÉTRICA

**Prerequisito:** F1 completa.

**Objetivo:** Reportar altura sobre el geoide (lo que importa para dictamenes), no solo sobre el elipsoide.

**Qué cambia:**
- Detectar `Location.hasMslAltitudeMeters()` (API 34+). Si true, usar `getMslAltitudeMeters()`.
- Fallback para API < 34: parseá NMEA GGA, campo 9 (separación geoide).
- Esquema JSON de punto: agregar campos:
  - `altitudeEllipsoidal` (hoy guardado como `altitude`).
  - `altitudeOrtometric` (nuevo).
  - `geoidSeparation` (fuente: MSL o NMEA GGA).
  - `altitudeSource` (string: "MSL_API" o "NMEA_GGA" o "unknown").

- UI: 
  - Mostrar AMBAS alturas, etiquetadas sin ambigüedad: "Elipsoidal: 1234.5 m" / "Ortométrica (sobre geoide): 1232.2 m".
  - Advertencia: "La altura ortométrica es la correcta para dictamenes legales".

**Cambios a datos guardados:**
- IndexedDB: migración. Puntos viejos (sin altura ortométrica): calcular ortométrica = elipsoidal + geoidSeparation(Costa Rica, ≈+1.5m).
- CSV export: incluir ambas alturas, fuente.

**Criterio de aceptación:**
- [ ] Pantalla muestra 2 alturas distintas (no confundibles).
- [ ] Costa Rica: altura ortométrica ≈ altura elipsoidal + 0.5 a 2 metros.
- [ ] Comparar contra mapa topográfico (Google Maps, IGN de CR): altitud reportada plausible.
- [ ] Puntos viejos no se pierden; se rellenan con altura ortométrica calculada.

**Riesgos identificados:**
- NMEA GGA puede tener geoide en 0 o valor incorrecto. Documentar.
- Diferentes chips pueden reportar geoides distintos. Testear en Motorola.
- Puntos anteriores a F2 necesitan migración. Si falla, los datos quedan corruptos.

**Tiempo estimado:** ~1 semana (parser NMEA GGA + migración + validación).

---

## Fase 3: PROMEDIADO ESTÁTICO

**Prerequisito:** F2 completa.

**Objetivo:** Ocupación de puntos. Pausas en un lugar para reducir ruido, con transparencia sobre la dispersión.

**Qué se agrega:**
- Modo de ocupación: entrada de usuario, duración configurable (30, 60, 120, 300 segundos).
- Mientras promedía:
  - Recibe fixes en tiempo real.
  - Descarta atípicos (ej. desviación > 3σ de la media).
  - Promedia ponderado por exactitud: punto = Σ(p_i × w_i) / Σ(w_i), donde w_i = 1 / accuracy_i^2.
  - Muestra en vivo: media actual, desviación estándar, número de muestras aceptadas.
  - Barra de progreso de tiempo.

- Punto guardado incluye:
  - Coordenadas (media).
  - `stdDevLat`, `stdDevLon` (desviación estándar, metros).
  - `sampleCount` (número de fixes promediados).
  - `cN0Mean`, `hdopMean` (C/N0 y HDOP promedios durante ocupación).
  - `duration` (segundos).

- UI: advertencia mientras promedía: "⚠️ El promediado reduce ruido aleatorio, NO sesgo por multipath. Una baja desviación estándar no garantiza exactitud absoluta."

**Cambios a datos guardados:**
- Esquema de punto: agregar campos de estadística (stdDev, sampleCount, etc.).
- IndexedDB: punto antiguo vs. punto promediado: distinguir en UI.

**Criterio de aceptación:**
- [ ] Promediado de 60s en un punto fijo: desviación estándar < 0.5 m (típico, depende de GPS).
- [ ] Barra de progreso es clara.
- [ ] Advertencia sobre multipath se ve.
- [ ] CSV export incluye desviación estándar y muestra de cuántos fixes se hizo el promedio.
- [ ] Puntos promediados vs. instantáneos se distinguen visualmente en el mapa.

**Riesgos identificados:**
- Usuario puede terminar ocupación prematuramente (tocando botón). Manejo de ese caso.
- Atípicos: cómo definir "atípico". Usaremos 3σ; documentar si falla (ej. multipath crónico).
- Ponderación por exactitud: si todos los fixes reportan exactitud = 0, el promediado falla. Fallback: media simple.

**Tiempo estimado:** ~2 semanas (UI, estadística, migración IndexedDB).

---

## Fase 4: SERVICIO EN PRIMER PLANO

**Prerequisito:** F3 completa.

**Objetivo:** Grabar tracks con pantalla apagada, sin que Android mate la app.

**Qué se agrega:**
- `ForegroundService` en Kotlin:
  - Permiso `FOREGROUND_SERVICE_LOCATION` en manifest.
  - Notificación persistente: "GPS TICO grabando track".
  - Continúa leyendo GPS aunque pantalla esté apagada.
  
- Wakelock (si fuera necesario): probablemente no, porque `ForegroundService` con GPS mantiene el dispositivo despierto.

**Cambios a datos guardados:**
- Ninguno. Tracks se guardan igual.

**Criterio de aceptación:**
- [ ] Grabar track, apagar pantalla: track sigue grabando (ver en notificación "3 minutos grabados").
- [ ] Duración de batería: medir consumo con pantalla apagada (vs. encendida).
- [ ] Notificación es clara y permite cancelar o pausar.

**Riesgos identificados:**
- Android puede restringir servicios en primer plano si el dispositivo está bajo presión de memoria.
- Algunos OEMs (Huawei, Xiaomi) agresivos en killing de servicios. Testear en Motorola.

**Tiempo estimado:** ~1 semana (Kotlin ForegroundService + notificación + test de batería).

---

## Fase 5: FILTROS DE CALIDAD

**Prerequisito:** F4 completa.

**Objetivo:** Descarte de puntos ruidosos o de baja calidad según criterios configurables.

**Qué se agrega:**
- Umbrales (configurables por usuario, con defaults sensatos):
  - HDOP máximo (ej. 5.0).
  - Satélites mínimos usados en fix (ej. 8).
  - Exactitud máxima (ej. 10 m).
  - C/N0 mínimo (ej. 20 dB-Hz).
  
- Lógica: antes de grabar punto en track:
  - Si HDOP > umbral: descarta.
  - Si satélites usados < umbral: descarta.
  - Si exactitud horizontal > umbral: descarta.
  - Si C/N0 medio < umbral: descarta.
  - Si hay descarte: muestra alerta visual / notificación sonora (configurable).

- UI: pantalla de configuración de filtros, con presets:
  - "Lenient" (máximas tolerancias, captura más).
  - "Strict" (mínimas tolerancias, captura menos pero más preciso).
  - "Custom" (entrada manual).

- Estadísticas: contador de puntos aceptados vs. rechazados durante grabación.

**Cambios a datos guardados:**
- Punto: agregar campo `filter_applied` (booleano), para saber si fue aceptado por filtros o es un rechazo grabado.
- Track: meta `filter_config` (qué filtros se usaron).

**Criterio de aceptación:**
- [ ] Grabar track con filtro HDOP < 3: track resulta más corto (algunos puntos rechazados).
- [ ] Estadísticas finales: "105 puntos capturados, 23 rechazados por filtro".
- [ ] Exportar track: CSV muestra cuál fue rechazado (columna `accepted: true/false`).
- [ ] Sin filtro y con filtro estricto: comparar tracks (stricter tiene menos zig-zag).

**Riesgos identificados:**
- Usuario asustado de que "la app rechaza datos". UI educativa muy importante.
- Umbrales pueden ser demasiado restrictivos en zonas urbanas (HDOP alto). Presets por zona (opcional, F5+).

**Tiempo estimado:** ~2 semanas (UI configuración + lógica de descarte + estadísticas + export).

---

## Resumen de cambios a IndexedDB por fase

| Fase | Cambio a esquema | Migración | Reversible |
|------|------------------|-----------|-----------|
| F0 | Ninguno | N/A | N/A |
| F1 | Agregar `verticalAccuracy` (opcional) | Auto: rellenar null en viejos | Sí (solo lectura) |
| F2 | Agregar `altitudeOrtometric`, `altitudeSource` | Auto: calcular ortométrica en viejos | Sí (cálculo reversible) |
| F3 | Agregar `stdDevLat`, `stdDevLon`, `sampleCount`, `cN0Mean`, `hdopMean`, `duration` | Auto: rellenar con null/0 en viejos (marcar como "instantáneo") | Sí (campos opcionales) |
| F4 | Ninguno | N/A | N/A |
| F5 | Agregar `filter_applied` (booleano) | Auto: true en todos (asumen que pasaron filtro) | Sí (booleano) |

**Regla:** Cada migración debe:
1. Detectar versión vieja del esquema.
2. Rellenar campos nuevos con defaults seguros (null, 0, false).
3. Loguear migración (para debug).
4. Nunca borrar datos.

---

## Calendario tentativo

| Fase | Estimado | Start | End |
|------|----------|-------|-----|
| F0 | 2 sem | 2026-07-16 | 2026-07-30 |
| F1 | 1 sem | 2026-07-31 | 2026-08-06 |
| F2 | 1 sem | 2026-08-07 | 2026-08-13 |
| F3 | 2 sem | 2026-08-14 | 2026-08-27 |
| F4 | 1 sem | 2026-08-28 | 2026-09-03 |
| F5 | 2 sem | 2026-09-04 | 2026-09-17 |

**Total:** ~9–10 semanas.

---

## Reglas de oro para todo el roadmap

1. **Una fase por sesión.** No adelantes sin criterios de aceptación cerrados.
2. **Antes de cada cambio:** qué cambia, por qué, qué se puede romper.
3. **Datos no se pierden.** Migración automática si toca IndexedDB. Plan de rollback documentado.
4. **Diagnosticable sin logcat.** Exportar CSV, pantalla de debug, o ambas.
5. **Sé escéptico.** Verificá APIs de Android contra documentación oficial, no memoria.
6. **Testing en dispositivo real.** No podés depurar localmente. Cada cambio requiere APK en Motorola.

---

## Definiciones de términos (para referencia)

| Término | Significado |
|---------|------------|
| **GNSS** | Sistema Global de Navegación por Satélite (GPS, Galileo, GLONASS, BeiDou, etc.) |
| **L5** | Banda de frecuencia de GPS (1176.45 MHz), más robusta que L1 |
| **Exactitud horizontal** | Error esperado (1-sigma) en latitud/longitud (metros) |
| **Exactitud vertical** | Error esperado (1-sigma) en altura (metros) |
| **HDOP** | Dilución horizontal de precisión. Bajo = geom. satélite buena. |
| **VDOP** | Dilución vertical de precisión. Bajo = satélites bien distribuidos verticalmente. |
| **PDOP** | Dilución posicional total (incluye altura). Bajo = bueno. |
| **C/N0** | Relación portadora/ruido en dB-Hz. Alto = señal fuerte. Típico: 20–50 dB-Hz. |
| **Multipath** | Rebote de señal en edificios/suelo. Causa error de metros. No es detectable directamente. |
| **Altura elipsoidal** | Altura sobre el elipsoide WGS84. Lo que devuelve GPS crudo. |
| **Altura ortométrica** | Altura sobre el geoide (nivel medio del mar local). Lo que importa para topografía. |
| **Proveedor fused** | Google Play Services: fusión GPS + WiFi + celda. Rápido pero no "GNSS puro". |
| **GPS_PROVIDER** | LocationManager nativo: solo GNSS. Primer fix lento, pero genuino. |
| **Atípico (outlier)** | Fix cuya posición se desvía demasiado (> 3σ) de la media. Descartable. |
| **Wakelock** | Flag de Android para mantener CPU/pantalla despierta. |
| **ForegroundService** | Servicio que no muere aunque no haya foreground. Requiere notificación. |

---

## Próximos pasos inmediatos

1. Cierra esta hoja de ruta (revisa que entendés todas las fases).
2. Abre sesión de F0.
3. Escribe el plugin Kotlin `GnssInstrumentationPlugin`.
4. Integrá UI en Composable.
5. Testá en Motorola Edge 50 Fusion.
