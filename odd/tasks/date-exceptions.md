# Excepciones por fecha (feriados y horarios especiales)

**Estado:** en curso
**Rama:** `feat/date-exceptions`
**Origen:** el modelo sabe de la semana repetida y de nada más. Un feriado (25/12 cerrado) o un día especial (24/12 hasta las 18:00) no se pueden representar, así que el local queda abierto según su horario habitual justo el día que no atiende.

## Modelo: la excepción tiene turnos, igual que un día

```
business_date_exception
  business_id  → businesses.id
  date         fecha concreta (una sola vez, no se repite)
  position     0,1,2…  orden dentro de la fecha
  opens_at     time
  closes_at    time
  UNIQUE (business_id, date, position)
```

**Por qué reusar la forma de los turnos y no un par de columnas con un flag `closed`:** una fecha excepcional es, para todo lo demás, *un día con turnos*. Si su forma es la misma, entonces el DTO es el mismo, las nueve reglas de validación son las mismas, y la carta resuelve la fecha con la misma función que ya usa para un día de la semana — `effectiveShifts(date)` = la excepción si existe, si no los turnos del día de la semana. Un flag aparte obligaría a un segundo camino de validación y a un segundo camino de cálculo, que es donde aparecen las inconsistencias.

**Por qué una tabla nueva y no extender `business_hours`:** `business_hours` está indexada por día de la semana y se repite toda la vida; una excepción es una fecha del calendario y vive una vez. Son claves distintas.

`business_date_exception` es una tabla **nueva**, así que la crea Hibernate al arrancar (es lo que `ddl-auto: update` hace de forma confiable; lo que no hace bien es alterar una tabla existente). No necesita migración manual — a diferencia de los turnos, que sí cambiaron una tabla con datos.

## Contrato de la API

`dateExceptions` viaja en el mismo perfil que los horarios. Una fecha con `shifts: []` **es un feriado cerrado**.

```json
{
  "hours": [ { "dayOfWeek": 5, "shifts": [ { "opensAt": "19:00", "closesAt": "00:30" } ] } ],
  "dateExceptions": [
    { "date": "2026-12-24", "shifts": [ { "opensAt": "11:00", "closesAt": "18:00" } ] },
    { "date": "2026-12-25", "shifts": [] }
  ]
}
```

La respuesta viene ordenada por fecha. En el `PUT` el reemplazo es total, como el resto del perfil (y el guardado parcial, cuando llegue, deja la lista intacta si el campo no viaja).

## Reglas de validación

1. `date` obligatoria y con formato de fecha real (`YYYY-MM-DD`); una fecha inexistente como `2026-02-30` se rechaza.
2. Dos entradas con la misma fecha ⇒ `Ya hay una excepción para esa fecha`.
3. Hasta **100 fechas** por negocio (`No se pueden cargar más de 100 fechas especiales`): el perfil viaja entero en cada request, así que una lista sin techo es un problema de carga.
4. Cada fecha cumple **las mismas reglas que un día**: hasta 4 turnos, sin solapes, sólo el último puede pasar la medianoche.
5. **Sin solape con el día siguiente**: el derrame del último turno de una fecha no puede chocar con la apertura del día siguiente, y el "día siguiente" se resuelve **contra los turnos efectivos** de esa fecha — su propia excepción si la tiene, si no el día de la semana. Es la regla que evita que una excepción abra un agujero en el horario normal.
6. Una fecha sin entrada en la lista es un día normal: no hace falta declarar los 364 días restantes.

## La carta

`openStatus` resuelve los turnos de una fecha con `effectiveShifts(date)`:

- **hoy**: la excepción si existe ⇒ el badge dice la verdad el 25/12;
- **el derrame de ayer**: si ayer fue una fecha excepcional, el derrame sale de *sus* turnos, no de los del día de la semana;
- **la próxima apertura**: el recorrido de 7 días ya consulta cada fecha, así que un feriado en el medio se saltea y anuncia la apertura real siguiente.

El feriado también aporta a la línea del día (`Hoy: …` / `Hoy: Cerrado`) sin ningún camino nuevo.

## Panel

Sección **"Fechas especiales"** en la pantalla Local: agregar una fecha (selector de fecha), elegir **cerrado** o cargar turnos (los mismos controles que un día), y quitar. Las fechas se muestran ordenadas y con el día de la semana al lado, porque en el panel "2026-12-25" dice mucho menos que "25/12 (jueves)".

## Fuera de alcance (anotado, no prometido)

- **Recurrencia**: un feriado fijo todos los años (25/12 siempre) no se repite solo; el dueño carga la fecha de cada año. Una regla de recurrencia es otro modelo y otra validación.
- **Feriados nacionales precargados**: no hay integración con ningún calendario de feriados.
- **Horario de verano (DST)**: el cálculo de la carta usa el reloj del navegador; un salto de hora entre hoy y el próximo borde puede desviar el timeout del badge hasta que lo corrija el respaldo (≥1 tick).

## Tareas

- [x] 1. Feature doc y plan (este documento)
- [ ] 2. Backend: tabla, entidad, DTO, reglas 1-6 y endpoints
- [ ] 3. Carta: `effectiveShifts` en `openStatus` (hoy, derrame de ayer y próxima apertura)
- [ ] 4. Panel: sección "Fechas especiales"
- [ ] 5. Verificación en navegador (panel → carta con un feriado) y cierre
