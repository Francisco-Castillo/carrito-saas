/**
 * Lo que es exclusivo del dashboard. El formato de plata se mudó a
 * `src/format.ts`: lo comparten el dashboard, el panel y la carta.
 */

/** Puerto 1:1 del avgPrep del legacy: "Xm" con minutos redondeados hacia abajo. */
export function formatPrepTime(avgPrepTimeSeconds: number): string {
  return Math.floor(avgPrepTimeSeconds / 60) + "m";
}

/**
 * Label del eje X del gráfico semanal: "Mar 14" a partir de "2026-10-14".
 * Hoy se marca aparte para que el usuario lo identifique de un vistazo.
 */
export function formatDayLabel(isoDay: string): string {
  const date = new Date(isoDay + "T00:00:00");

  if (Number.isNaN(date.getTime())) {
    return isoDay;
  }

  return date.toLocaleDateString("es-AR", {
    weekday: "short",
    day: "numeric",
  });
}
