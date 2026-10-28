# cancel-order-contract

Cierre del último pendiente registrado de `odd/tasks/cross-tenant-stock.md` que sigue abierto: `cancelOrder` no tiene ningún test (la ruta, el scoping y la devolución de stock no están pinneados). El scoping de la ruta ya es correcto en código (`OrderServiceImpl.cancelOrder` usa `findByIdAndBusinessId` y `incrementStock(..., businessId)`); este slice fija el contrato con tests HTTP contra PostgreSQL real, siguiendo los patrones de `CrossTenantStockIsolationTests` y `AdminProductAndComboEndpointTests`.

## Specs

- S1: Cancelar un pedido propio en `NEW` (`PATCH /api/orders/{id}/cancel` con `{reasonId, note}`) responde 200, persiste `status = CANCELLED`, el motivo, la nota y `cancelledAt`, y **devuelve el stock** del producto (el descuento de la creación se revierte).
- S2: Cancelar un pedido **ajeno** (id de otro negocio) responde 404, deja la fila ajena exactamente igual (estado y stock) y no persiste nada.
- S3: Transición inválida (`READY -> CANCELLED`) responde 409 con "Transición inválida", el pedido queda en `READY` y **no** se devuelve stock.
- S4: `reasonId` inexistente responde 400 con "Motivo inválido", el pedido no cambia.
- S5: Cancelar un pedido con combo devuelve el stock de **cada componente** del combo (según cantidad descompuesta) y **no** intenta devolver stock por el item root (`comboRoot = true`).
- S6: Los tests son tests HTTP por MockMvc con la cadena de seguridad real, fixtures con ids altos manuales (negocio A dueño autenticado + negocio B ajeno) y lectura de stock con flush+clear (SELECT fresco), igual que `CrossTenantStockIsolationTests`.

## Tasks

| ID | Specs | Ruta | Commit |
| --- | --- | --- | --- |
| T1 | S1, S6 | inline | 7c4f1a6 |
| T2 | S2 | inline | 7c4f1a6 |
| T3 | S3 | inline | 7c4f1a6 |
| T4 | S4 | inline | 7c4f1a6 |
| T5 | S5 | inline | 7c4f1a6 |

Nota: son tests de cobertura sobre comportamiento existente, no RED-first de un arreglo esperado. Resultado observado: 2 de 6 fallaron en la primera corrida y ambos fueron defectos DEL TEST, no del código:

- T4 (S4): el assert de stock veía el incremento del servicio dentro de la transacción compartida del test. En producción el rechazo es `RuntimeException` (`BusinessException`) y el rollback de la transacción del servicio deshace el incremento: la garantía vive en el límite de la transacción, no en estado visible. El assert de stock se quitó y el motivo quedó documentado en el test.
- T5 (S5): el assert de stock leía la entidad cacheada en L1 (el `@Modifying` UPDATE no invalida la cache y no hubo flush+clear previo). Se movió la lectura después del `reload()` (flush+clear).

## Log

- L1 (usuario): "que quedo pendiente ?" — relevamiento de pendientes registrados.
- L2 (usuario): "y puedo por ejemplo delegarte a ti el milti-tenant y a otra sesion lo del kds ?" y luego "si" — autorización para tomar multi-tenant en worktree propio (`/home/rodrigo/work/carrito-saas-mt`, rama `fix/cross-tenant-writes`).
- L3 (evidencia de exploración): los dos agujeros autenticados registrados por cross-tenant-stock (PUT/POST productos sin scope, crearCombo sin categoría) ya están arreglados y testeados por PRs posteriores (`CrossTenantProductWriteIsolationTests`, `ComboUpdateEndpointTests`, `AdminProductAndComboEndpointTests`). `cancelOrder` ya es tenant-safe. Queda abierto sólo el gap de cobertura: `cancelOrder` sin tests (S1-S6).
- L4 (resultado): clase nueva `CancelOrderEndpointTests` con 6 tests (S1-S6), 6/6 verdes contra PostgreSQL real. La suite foco encontró 2 fallos en la primera corrida; ambos resultaron defectos del test (transacción compartida en S4, cache L1 en S5), no del código — ver nota sobre la tabla. Sin defectos de producción encontrados en `cancelOrder`.
