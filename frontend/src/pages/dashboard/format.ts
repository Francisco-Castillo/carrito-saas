/** Puerto 1:1 del formatARS del legacy dashboard/dashboard.js. */
export function formatARS(value: number): string {
  return (
    "$ " +
    new Intl.NumberFormat("es-AR", {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(value)
  );
}

/** Puerto 1:1 del avgPrep del legacy: "Xm" con minutos redondeados hacia abajo. */
export function formatPrepTime(avgPrepTimeSeconds: number): string {
  return Math.floor(avgPrepTimeSeconds / 60) + "m";
}
