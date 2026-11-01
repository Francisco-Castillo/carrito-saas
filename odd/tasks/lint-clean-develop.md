# Feature: lint-clean-develop

Dejar `develop` con `oxlint` en cero advertencias y con un gate en el CI que
impida que vuelvan a acumularse. Hoy hay **36 warnings** y el workflow no corre
lint, así que la deuda crece sola.

**Reparto real medido** (no estimado): los **17 `eslint(no-unused-vars)`** están
todos en `frontend/tests/**` y `frontend/e2e/**` (imports y variables sin usar,
parámetros sin usar); las **19 restantes** son de React y están en
`frontend/src/pages/**`: 11 `react(set-state-in-effect)`, 4
`react-hooks(exhaustive-deps)`, 2 `react(refs)`, 2
`react(only-export-components)`.

## Specs

S1. `oxlint` deja de reportar `eslint(no-unused-vars)`: en `frontend/tests/**` y
    `frontend/e2e/**` se quitan los imports y las variables que no se usan, y
    los parámetros que no se usan pasan a llevar prefijo `_` (la convención que
    el propio linter sugiere; renombrar el parámetro no cambia el test porque
    nunca se lee). Sin cambios de comportamiento: los 501 tests del frontend
    siguen pasando sin tocar una sola aserción.

S2. Los 11 `react(set-state-in-effect)` de `frontend/src/pages/**` se resuelven
    **en el diseño**, no silenciando la regla: derivar el valor durante el
    render, inicializar el estado directamente, o mover el `setState` al
    handler del evento que lo causa. Cada caso conserva su comportamiento
    observable (mismos textos, mismas llamadas HTTP, mismos estados): la suite
    de tests es la red, y ningún cambio de comportamiento entra "de paso".

S3. Las 4 `react-hooks(exhaustive-deps)`, los 2 `react(refs)` y los 2
    `react(only-export-components)` se resuelven en el código: dependencias
    reales declaradas (sin `useMemo` con claves derivadas a mano), acceso a refs
    sólo fuera del render, y constantes/helpers que hoy conviven con un
    componente se mueven a su propio módulo. Mismo criterio: la suite es la red.

S4. El CI corre lint **como gate**: `npm run lint` pasa a ejecutar `oxlint
    --deny-warnings` y el workflow lo corre junto a `npm test`. Un warning nuevo
    rompe el build; hoy `npm run lint` sale con código 0 pese a las 36
    advertencias, así que el gate no existe en los hechos.

## Tasks

- **T1** (S1) chore: quitar unused vars de `frontend/tests/**` y
  `frontend/e2e/**`. Commit: `23575e5`.
- **T2** (S2) refactor: los 11 `set-state-in-effect`. Commit: `1c82087`.
- **T3** (S3) refactor: deps, refs y only-export-components. Commit: `1c82087`
  (misma unidad que T2: los dos tocaban los mismos archivos y el mismo
  criterio).
- **T4** (S4) ci: `--deny-warnings` + paso de lint en el workflow, con la
  verificación final. Commit: `ccec153`.

## Log

- **L1** (pedido del dueño, verbatim): «si eso queda parado hasta nuevo aviso,
  primero quiero pulir develop». Contexto: multi-sucursal queda **parado**
  (rama `feat/sucursales-cuentas` sin pushear, issue #91 abierta) hasta nuevo
  aviso, y el pulido arranca por el eje elegido en el cuestionario: **lint +
  gate en CI** (por encima de las advertencias de React solas y de sincronizar
  la DB de desarrollo, que quedaron como frentes siguientes).
- **L2** (medición, no impresión): `npm run lint` → 36 warnings, salida 0. El
  reparto por archivo mostró que los 17 `no-unused-vars` viven en
  `tests/`/`e2e/` (no en `src/`), lo que separa el trabajo en una unidad
  mecánica y dos de comportamiento.

- **L3** (evidencia de cierre): `npm run lint` → **0 warnings, exit 0** con
  `--deny-warnings`. `npm test` → **501/501** (63 archivos), igual que antes de
  tocar una línea. `npm run build:spring` → exit 0. Tres commits:
  `23575e5` (tests/e2e), `1c82087` (src/), `ccec153` (gate).
- **L4** (dos tropiezos reales, registrados para no repetirlos):
  (a) el worker que limpió `tests/` borró al pasar la importación de `render`
  en `kds-fullscreen.test.tsx` (el warning apuntaba a `screen`): el efecto fue
  `ReferenceError: render is not defined` en la suite, detectado por la corrida
  del padre y corregido en la misma tarea;
  (b) su `npm run build` regeneró el bundle sin copiar las entradas legacy y
  rompió `tests/build-artifacts.test.ts` — el comando correcto en este repo es
  `npm run build:spring`, que es el que usa el CI vía el perfil `frontend`.
- **L5** (criterio de las supresiones): los 5 cargadores async conservan un
  `oxlint-disable react/set-state-in-effect` **con el motivo escrito al lado**,
  porque el `setState` ocurre después del `await` y la regla no analiza el
  límite async. Silenciar ahí es no tener gate; contorsionar ocho cargadores
  que funcionan sería peor. Las 14 advertencias reales se resolvieron en el
  diseño.

- **L6** (revisión nativa del arco): `assess` marcó **`reviewDue: true` con
  `nativeRisk: high`** porque el cambio toca `.github/workflows/ci.yml`
  (riesgo de delivery/CI), así que se abrió transacción
  `review-19640f8589d73d81` con las **cuatro lentes** (risk, resilience,
  readability, reliability) sobre 28 archivos / 295 líneas. La corrida grupal
  admitió tres lentes y **rechazó `reliability` en admisión** («reviewer finding
  location is outside the frozen candidate»): el slot no se consumió, se
  consultó STATUS y se corrió sólo el slot reofrecido, que sí fue admitido.
  Resultado final: **aprobada**, autoridad quemada, con seis hallazgos
  informativos (dos WARNING: `R2-001` sobre el ref de `useModalSheet` y
  `R3-date-init-when-disabled` sobre la fecha calculada aunque el hook esté
  deshabilitado; cuatro sugerencias, entre ellas la fragilidad del regex del
  test del gate y lo amplio de los bloques de supresión).
- **L7** (verificación independiente previa, `gentle-ai-verify`): sin
  bloqueantes. Reprodujo la línea base (36 warnings, salida 0), confirmó el flag
  en la versión fijada por el lockfile (`oxlint 1.88.0`, extraída del caché) y
  que sale 1 ante una advertencia, validó el YAML y el orden de pasos del
  workflow, y auditó las siete correcciones de comportamiento una por una con su
  cobertura de test. Advertencias que dejó: el rebuild de `SalesByDayChart` con
  `prevData` es un cambio real (arregla una serie comparativa vieja, sin test);
  `CancelOrderModal`, `DatosSection` y la fecha del dashboard no tienen test que
  cubra el cambio; y el lint corre después de `mvn verify` (feedback tardío).

