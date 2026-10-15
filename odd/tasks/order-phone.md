# Celular del cliente en el pedido

**Estado:** cerrado (2026-10-14), con formato en cocina
**Objetivo:** Que el pedido lleve un celular de contacto y que la cocina pueda llamar al cliente.

## Hallazgos

- `OrderRequestDTO` **ya tenía** `customerPhone` y `OrderServiceImpl.createOrder` ya lo persistía: la creación no necesitó cambios.
- El hueco estaba en la **lectura de cocina**: `OrderKitchenDTO` (lo que sirve `GET /api/business/orders/active`) no incluía el teléfono, así que se guardaba y nadie lo veía nunca.

## Decisiones

- **Obligatorio** para retiro y delivery (la cocina necesita poder avisar). Si se prefiere opcional en retiro, es un cambio de dos líneas.
- **Una sola regla de validación en todo el sistema**: la misma forma que los teléfonos de negocio (`businesses_phone_shape`): se quitan espacios, `+`, `-`, `.` y paréntesis, y se exigen de 8 a 15 dígitos sin empezar en cero. El número se envía normalizado (solo dígitos).
- **Visible en el KDS como link para llamar** (`tel:`): un teléfono guardado que nadie ve no sirve; desde la tablet de cocina se toca y llama. Se reusa el chip existente (`.order-notes`), así que no hizo falta CSS nuevo.

## Evidencia

- Commits: `3fbc77d` (formulario: campo obligatorio + validación + payload normalizado + tests de vacío/inválido que no envían) y `af40910` (DTO de cocina + mapeo + chip `tel:` en la tarjeta + tests de presencia y ausencia).
- Tests: backend **102/102** en base descartable (verificado por el orquestador); frontend **44/44**.
- **Verificación en navegador** (`~/.local/opt/e2e/phone-check.mjs`), 5/5 PASS: el formulario pide el celular con `type=tel` e `inputmode=tel`; celular inválido (`123`) y celular vacío muestran su mensaje y **no** emiten ningún POST; un pedido válido con `11 5555-1234` viaja como `customerPhone: 1155551234`; y el KDS muestra el chip `📞 1155551234` con `href="tel:1155551234"`. Captura: `/tmp/e2e-menu/16-kds-celular.png`.

## Segunda vuelta (pedido del usuario)

- **Retiro sigue obligatorio**: decisión confirmada por el usuario, sin cambios.
- **El KDS lo muestra formateado** (commit posterior): el chip pasaba los dígitos crudos (`1155551234`) y obligaba a contar dígitos para marcar. Ahora agrupa según el largo (10 dígitos → `11 5555-1234`; 11 empezando en 9 → `9 11 5555-1234`; 8 → `5555-1234`) **sólo para mostrar**: el `href` sigue con los dígitos crudos para que el dialer funcione. Un largo desconocido se deja crudo y un valor sin dígitos se muestra como vino, en vez de inventar un formato o vaciarlo. Tests: `frontend/tests/kds-phone-format.test.ts` (6 casos) + las dos aserciones de la tarjeta. Verificado en navegador: chip `📞 11 5555-1234` con `href="tel:1155551234"`.

## Pendientes

- Si el negocio quiere conservar el formato o el código de país (`+54...`), hay que revisar la regla de normalización.
- El chip usa la clase de las notas en el KDS; si se quiere un estilo propio hace falta tocar `frontend/src/pages/Kds.css` (fuera de las superficies de esta tarea).
