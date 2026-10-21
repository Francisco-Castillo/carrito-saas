/**
 * Formato de plata de la app, en un solo lugar.
 *
 * Vivía en `pages/dashboard/format.ts` como puerto del legacy del dashboard,
 * pero lo usan tres pantallas (dashboard, panel y carta) y la carta no debería
 * depender de la carpeta del dashboard para mostrar un precio.
 *
 * Es el `Intl.NumberFormat` que ya venía del legacy, con un ajuste pedido por
 * el dueño: sin centavos — "$ 3.200", nunca "$ 3.200,00". Los precios de la
 * carta son enteros y los totales ganan legibilidad sin decimales. Se sigue
 * instanciando UNA vez a nivel de módulo con locale explícito: construirlo en
 * cada llamada es trabajo repetido por precio renderizado y el resultado es
 * idéntico (finding R3-intl-env del review de #50; el dorado de
 * menu-prices.test.tsx fija el output).
 */
const arsFormatter = new Intl.NumberFormat("es-AR", {
  minimumFractionDigits: 0,
  maximumFractionDigits: 0,
});

export function formatARS(value: number): string {
  return "$ " + arsFormatter.format(value);
}
