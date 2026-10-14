# Horarios: varios turnos por día

**Estado:** cerrado (2026-10-14)
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

`docs/migrations/2026-10-15-business-hours-shifts.sql` — el script real: el `DELETE` de los días cerrados va **dentro de un bloque `DO`** que primero chequea que la columna `closed` exista, y el `ADD CONSTRAINT` va precedido de un `DROP CONSTRAINT IF EXISTS` (Postgres no tiene `ADD CONSTRAINT IF NOT EXISTS`).

Lo que hay que cuidar: **los turnos existentes pasan a `position = 0`**, así que ningún negocio pierde sus horarios; y las filas de días cerrados se borran sin pérdida, porque no llevan horarios (ese estado ahora se expresa con la ausencia de turnos).

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

- [x] 1. Feature doc y plan (este documento)
- [x] 2. Migración idempotente + entidad `BusinessHours` con `position` — commit `d1179a8` (`closed` se va en el paso 3, junto con el DTO)
- [x] 3. DTO de turnos por día, validación nueva (reglas 1-9) y endpoints — commit `e9094fa`
- [x] 4. Editor de turnos en el admin Local (agregar/quitar turno por día) — commit `90af933`
- [x] 5. `openStatus.ts` con varios turnos y derrame; badge en la carta — commit `80a038a`
- [x] 6. Seed con dos turnos y verificación en el navegador — commit `0c4baea`
- [x] 7. Cerrar y dejar anotados los pendientes

## Riesgos anotados

- La migración es sobre **datos reales** del usuario (`demo-pizzeria`): se corre con `BEGIN/COMMIT`, se documenta, y el respaldo previo es decisión suya.
- `ddl-auto: update` puede agregar la columna `position` por su cuenta en una base nueva, pero **no** reemplaza el `UNIQUE` viejo en una existente: la migración es obligatoria igual.
- El badge se calcula al renderizar: si la carta queda abierta cruzando el cierre, no se refresca sola (ya anotado en `storefront.md`).

## Ensayo de la migración sobre una copia de la base real (commit `d1179a8`)

`CREATE DATABASE ... TEMPLATE carrito_db` falla con la base en uso (10 sesiones activas), así que el ensayo se hizo con una base descartable con la tabla **en su forma vieja** (constraint vieja incluida) y las filas reales copiadas.

| | Antes | Después |
| --- | --- | --- |
| Filas totales en `business_hours` | 7 | **6** |
| `demo-e2e` (990000001, el demo del seed) | 7 | 6, todas `position = 0` |
| `demo-pizzeria` (900000001, el negocio del usuario) | **0** | 0 |
| Filas de días cerrados | 1 (domingo del demo) | 0 (borrada, no llevaba horarios) |

Se aplicó **tres veces**: la pasada 1 con el script original, la 2 falló (el bug de idempotencia de abajo) y las pasadas 2 y 3 con el script corregido terminaron idénticas. La constraint nueva acepta un segundo turno (`position = 1` en el viernes) y **rechaza** un duplicado de `(negocio, día, position = 0)`. La base real quedó intacta con sus 7 filas.

### El bug que encontró el ensayo

La primera versión borraba los días cerrados con `DELETE ... WHERE closed IS TRUE OR ...` **antes** de eliminar la columna. En la segunda corrida esa sentencia falla al analizarse: Postgres resuelve las columnas al planificar, así que un guard en tiempo de ejecución no lo salva — hay que envolver el `DELETE` en un bloque `DO` que consulte `information_schema.columns`. Una migración que sólo funciona la primera vez **no es idempotente**, y esta tiene que poder correrse de nuevo sin pensarlo.

### Dato del ensayo que cambia prioridades

**`demo-pizzeria`, el negocio del usuario, tiene cero filas de horarios.** No hay nada que migrar del lado del negocio real: los 7 registros son del demo del seed. O sea que la migración no pone en riesgo ningún horario cargado a mano, y también que hoy su carta no muestra ningún horario — la pantalla "Local" del panel es la que va a permitir cargarlos.

## Cierre: cómo se verificó (commits `d1179a8` … `0c4baea`)

**Backend 140/140, frontend 95/95.** Y contra el servlet real, no sólo en tests:

| Regla | Respuesta |
| --- | --- |
| Solape dentro del día | 400 *Los turnos de un día no pueden superponerse* |
| Medianoche y no es el último | 400 *Un turno que pasa la medianoche tiene que ser el último del día* |
| Derrame choca con la mañana siguiente | 400 *…se superpone con la apertura del día siguiente* |
| Cinco turnos / horas iguales / día 8 / día repetido | 400 con su mensaje |
| Derrame que termina justo cuando el otro abre | **aceptado** (el borde exacto es válido) |
| Derrame domingo→lunes | 400 (el chequeo es cíclico) |

Y el **ida y vuelta completo en el navegador** (`~/.local/opt/e2e/shifts-roundtrip.mjs`, 5/5): el editor carga el viernes con dos turnos, la carta describe el día entero y está abierto a las 00:15 por el derrame, adelantar el cierre a las 23:30 **borra el derrame**, quitar el turno de la noche deja la noche cerrada y anuncia que abre mañana, y volver a agregarlo devuelve el demo a su estado. La portada y la medianoche: **7/7**.

## Lo que dejó este cambio (para no repetirlo)

- **La migración que "era idempotente" no lo era.** El `DELETE` de los días cerrados referenciaba la columna que el mismo script borraba: la primera corrida andaba y la segunda fallaba, porque Postgres resuelve columnas al planificar. Apareció **porque el ensayo la corrió dos veces**, no razonando. Cualquier migración va con ensayo de doble corrida.
- **Un tipo canónico que queda viejo es una mina.** `BusinessHours` en el frontend describía el payload anterior mientras el backend ya mandaba turnos: el worker se detuvo dos veces pidiendo la superficie correcta en vez de taparlo con casts, y tenía razón.
- **Un campo que falta no puede borrar datos.** Un cliente con la forma vieja mandaba `shifts: null` y el servidor lo leía como "día cerrado", así que guardaba un 200 y **borraba los horarios en silencio**. Ahora da 400 con un mensaje, y hay un test que comprueba que lo guardado sobrevive al rechazo (commit `261750e`).
- **Un test que depende del reloj real no es un test**: la comprobación del badge en la portada cambió a reloj fijo (miércoles 13:00).

## Pendientes que quedan anotados

- **Horario de verano y feriados**: el modelo no los representa.
- El estado se calcula al renderizar: si la carta queda abierta cruzando un cambio de turno, el badge no se actualiza solo.
- El `PUT` sigue siendo de reemplazo total: una pantalla con ediciones parciales necesita otro contrato.
- **`demo-pizzeria` (el negocio del usuario) no tiene horarios cargados**: la carta responde los 7 días cerrados, y se cargan desde la pantalla "Local".
