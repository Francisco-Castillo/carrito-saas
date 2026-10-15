import { useEffect, useMemo, useState } from "react";
import { getOpenStatus, nextStatusBoundaryAt } from "./openStatus";
import type { BusinessHours } from "./types";

/**
 * El estado abierto/cerrado de la portada, refrescado SOLO cuando puede
 * cambiar. `getOpenStatus` es puro (horarios + instante), así que el estado
 * viejo del badge no era un problema de cálculo sino de CUÁNDO se calculaba:
 * esto lo recalcula en tres disparadores complementarios:
 *
 *  1. un timeout agendado en el PRÓXIMO borde del horario (apertura o cierre
 *     de algún turno, vía `nextStatusBoundaryAt`): precisión al minuto sin
 *     despertar al navegador cada segundo;
 *  2. un re-chequeo de respaldo ACOTADO (cada 60 s, ni más frecuente ni con
 *     deriva): cubre lo que un timeout solo no ve — la lista de horarios que
 *     cambia debajo, un salto del reloj, una notebook que durmió cruzando el
 *     borde (al despertar los timers vencidos disparan una vez);
 *  3. `visibilitychange`: una pestaña que estuvo horas en segundo plano
 *     muestra la verdad en cuanto el cliente la mira.
 *
 * La identidad del objeto `hours` NO es dependencia: la clave serializada lo
 * es, así que un re-render con los mismos datos no re-agenda ni apila
 * listeners (el cleanup del efecto corre una sola vez por dato distinto).
 */
const FALLBACK_RECHECK_MS = 60_000;

export function useOpenStatus(hours: BusinessHours[] | null | undefined) {
  const hoursKey = useMemo(() => JSON.stringify(hours ?? null), [hours]);
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    let timeoutId: ReturnType<typeof setTimeout> | undefined;

    const scheduleNextBoundary = () => {
      if (timeoutId !== undefined) clearTimeout(timeoutId);
      const at = nextStatusBoundaryAt(hours, new Date());
      timeoutId = at === null ? undefined : setTimeout(tick, at - Date.now());
    };

    const tick = () => {
      setNow(new Date());
      scheduleNextBoundary();
    };

    const onVisibilityChange = () => {
      if (document.visibilityState === "visible") tick();
    };

    scheduleNextBoundary();
    const fallbackId = setInterval(tick, FALLBACK_RECHECK_MS);
    document.addEventListener("visibilitychange", onVisibilityChange);

    return () => {
      if (timeoutId !== undefined) clearTimeout(timeoutId);
      clearInterval(fallbackId);
      document.removeEventListener("visibilitychange", onVisibilityChange);
    };
    // `hours` se captura por closure; la clave serializada garantiza que el
    // efecto se re-ejecute exactamente cuando los DATOS cambian.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hoursKey]);

  return useMemo(() => getOpenStatus(hours, now), [hoursKey, now]);
}
