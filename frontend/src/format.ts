/**
 * Formato de plata de la app, en un solo lugar.
 *
 * Vivía en `pages/dashboard/format.ts` como puerto del legacy del dashboard,
 * pero lo usan tres pantallas (dashboard, panel y carta) y la carta no debería
 * depender de la carpeta del dashboard para mostrar un precio.
 *
 * Es el mismo `Intl.NumberFormat` que ya venía del legacy: "$ 3.200,00". La
 * carta mostraba el número crudo ("$3200", y al multiplicar por cantidad
 * "$3200.5"), distinto de lo que el dueño ve en su panel.
 *
 * El formatter se instancia UNA vez a nivel de módulo con locale explícito:
 * construirlo en cada llamada es trabajo repetido por precio renderizado y
 * el resultado es idéntico (finding R3-intl-env del review de #50; el dorado
 * de menu-prices.test.tsx fija el output).
 */
const arsFormatter = new Intl.NumberFormat("es-AR", {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

export function formatARS(value: number): string {
  return "$ " + arsFormatter.format(value);
}
