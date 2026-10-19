/**
 * Lo que es exclusivo del dashboard. El formato de plata se mudó a
 * `src/format.ts`: lo comparten el dashboard, el panel y la carta.
 */

/** Puerto 1:1 del avgPrep del legacy: "Xm" con minutos redondeados hacia abajo. */
export function formatPrepTime(avgPrepTimeSeconds: number): string {
  return Math.floor(avgPrepTimeSeconds / 60) + "m";
}
