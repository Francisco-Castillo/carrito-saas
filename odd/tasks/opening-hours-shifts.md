# Horarios: varios turnos por día

**Estado:** en curso
**Rama:** `feature/ui-liquid-glass`
**Origen:** el modelo actual guarda **un solo rango por día** (`business_hours` con `UNIQUE (business_id, day_of_week)`), así que "mediodía y noche" es imposible de representar. Un restaurante real abre, cierra y vuelve a abrir el mismo día.

## Por qué no alcanza con dos pares de columnas

`apertura1/cierre1/apertura2/cierre2` es tentador pero: no escala a tres turnos, obliga a repetir la validación por cada par, y deja cuatro columnas nulas que representan "no hay segundo turno" (un estado más que se puede contradecir con los datos).

**Modelo elegido: cada fila de `business_hours` es un turno, ordenado dentro del día.** Un día sin turnos **es** un día cerrado. Así salen del mismo modelo "sólo mediodía", "sólo noche" y tres turnos, y desaparece el campo `closed`, que hoy puede contradecir a los horarios (un día `closed=true` con horas cargadas).

```
business_hours
  business_id  → businesses.id
  day_of_week  1..7 (1 = lunes)
  position     0,1,2…  orden del turno dentro del día
  opens_at     time
  closes_at    time   (menor que opens_at ⇒ pasa la medianoche)
  UNIQUE (business_id, day_of_week, position)
```

## Migración (manual, idempotente)

`ddl-auto: update` **no altera de forma confiable** una tabla existente y no hay Flyway ni Liquibase: el esquema de estas tablas lo posee Hibernate y los cambios delicados se hacen a mano (precedente documentado en `Business.java` para `slug`/`phone`). Por eso el cambio va en un script versionado y **no** en `schema.sql` (que posee sólo las tablas de analítica por JDBC).

`docs/migrations/2026-10-15-business-hours-shifts.sql`:

```sql
BEGIN;
-- Un día cerrado no aporta ningún turno: hoy es una fila sin horarios.
DELETE FROM business_hours WHERE closed IS TRUE OR opens_at IS NULL OR closes_at IS NULL;
-- Un turno por (negocio, día) deja de ser único; pasa a ser el turno 0.
ALTER TABLE business_hours DROP CONSTRAINT IF EXISTS uq_business_hours_business_day;
ALTER TABLE business_hours ADD COLUMN IF NOT EXISTS position integer;
UPDATE business_hours SET position = 0 WHERE position IS NULL;
ALTER TABLE business_hours ALTER COLUMN position SET NOT NULL;
ALTER TABLE business_hours DROP COLUMN IF EXISTS closed;
ALTER TABLE business_hours ADD CONSTRAINT uq_business_hours_business_day_position
  UNIQUE (business_id, day_of_week, position);
COMMIT;
```

Lo que hay que cuidar: **los turnos existentes pasan a `position = 0`**, así que ningún negocio pierde sus horarios; y las filas de días cerrados se borran sin pérdida, porque no llevan horarios (ese estado ahora se expresa con la ausencia de turnos).

## Contrato de la API

`hours` sigue siendo una lista de días; cada día ahora lleva una lista de turnos. Un día cerrado es una lista vacía.

```json
{
  "name": "Foodtruck Demo",
  "hours": [
    { "dayOfWeek": 5, "shifts": [
        { "opensAt": "11:00", "closesAt": "15:00" },
        { "opensAt": "19:00", "closesAt": "00:30" } ] },
    { "dayOfWeek": 7, "shifts": [] }
  ]
}
```

El servidor **ordena** los turnos de cada día por hora de apertura, así que el cliente puede mandarlos en cualquier orden; `position` es el índice después de ordenar y no se expone.

## Reglas de validación

1. `hours` nulo o vacío ⇒ todos los días cerrados (válido).
2. Día `dayOfWeek` fuera de 1..7 ⇒ `dayOfWeek debe estar entre 1 (lunes) y 7 (domingo)`.
3. Dos entradas para el mismo día ⇒ `dayOfWeek duplicado: N` (sigue siendo error).
4. Un turno con `opensAt` o `closesAt` nulo ⇒ `opensAt es obligatorio en cada turno del día abierto` (y su equivalente para `closesAt`).
5. `opensAt == closesAt` ⇒ `opensAt y closesAt no pueden ser iguales`.
6. **Máximo 4 turnos por día** (holgado para cualquier local; evita listas absurdas).
7. **Sin solapes dentro del día**: se comparan en minutos, tratando un turno que pasa la medianoche como si terminara pasado el día.
8. **Sólo el último turno del día puede pasar la medianoche**; si hay otro turno después, se rechaza con un mensaje que lo diga.
9. **Sin solape con el derrame**: si el último turno del día termina pasado el día (por ejemplo 00:30) y el día siguiente abre antes de que termine ese derrame, se rechaza. Se chequea de forma cíclica (después del domingo, el lunes).

Los mensajes de error se muestran tal cual en el panel del dueño, así que están redactados para ser leídos por él.

## Estado abierto/cerrado en la carta

`openStatus.ts` deja de usar `find(dayOfWeek)` (que hoy descarta los demás turnos en silencio) y pasa a evaluar listas:

- **Abierto** si el momento cae dentro de algún turno de hoy, **o** dentro del derrame del último turno de ayer (a las 00:10 del viernes el local está abierto por el turno del jueves). Es el punto que más fácil se rompe en silencio.
- `closingLine`: el cierre del turno que contiene el momento actual.
- `nextOpeningLine`: la próxima apertura mirando hasta 7 días adelante.
- `todayLine`: los turnos del día ("11:00–15:00 y 19:00–23:00") o "Cerrado".

## Tests que cambian (identificados)

- Backend `BusinessProfileAndStorefrontTests`: los 12 tests de horarios guardan hoy un rango por día; se reescriben al modelo de turnos y se agregan los casos de solape, derrame y medianoche.
- Frontend `storefront-open-status.test.ts`: todos (el helper `h()` construye un rango). `admin-business-profile.test.tsx`: el editor de 7 filas y el payload exacto. `storefront.test.tsx`: el texto del badge. `menu-redesign.test.tsx:104`: `hours: []`.
- Seed `docs/dev-seed.sql`: el upsert por `(business_id, day_of_week)` pasa a `(business_id, day_of_week, position)`, y el demo pasa a tener **dos turnos en varios días** (viernes y sábado 19:00→00:30) para que la función se vea en la carta.

## Tareas

- [ ] 1. Feature doc y plan (este documento)
- [ ] 2. Migración idempotente + entidad `BusinessHours` con `position` (sin `closed`)
- [ ] 3. DTO de turnos por día, validación nueva (reglas 1-9) y endpoints
- [ ] 4. Editor de turnos en el admin Local (agregar/quitar turno por día)
- [ ] 5. `openStatus.ts` con varios turnos y derrame; badge en la carta
- [ ] 6. Seed con dos turnos y verificación en el navegador (admin + carta)
- [ ] 7. Actualizar `storefront.md` y cerrar

## Riesgos anotados

- La migración es sobre **datos reales** del usuario (`demo-pizzeria`): se corre con `BEGIN/COMMIT`, se documenta, y el respaldo previo es decisión suya.
- `ddl-auto: update` puede agregar la columna `position` por su cuenta en una base nueva, pero **no** reemplaza el `UNIQUE` viejo en una existente: la migración es obligatoria igual.
- El badge se calcula al renderizar: si la carta queda abierta cruzando el cierre, no se refresca sola (ya anotado en `storefront.md`).
