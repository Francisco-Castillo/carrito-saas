import { useEffect, useMemo, useState } from "react";
import { getOpenStatus, nextStatusBoundaryAt } from "./openStatus";
import type { BusinessHours, DateException } from "./types";

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

// Por qué hace falta este recheck ADEMÁS del borde exacto: el timeout agenda con
// la diferencia entre dos instantes absolutos (`at - Date.now()`), así que un
// cambio de horario de verano no lo desvía. El único punto sensible es sumar
// minutos a la medianoche LOCAL el día de la transición, donde el borde puede
// quedar una hora corrido. Este recheck vuelve a evaluar el estado cada 60 s, así
// que el peor caso es un badge hasta un minuto viejo — nunca una hora — y por eso
// el DST no necesita código propio. No lo quites sin resolver eso antes.

export function useOpenStatus(
  hours: BusinessHours[] | null | undefined,
  dateExceptions?: DateException[] | null,
) {
  // Las excepciones entran en la clave: una lista que cambia bajo el mismo
  // `hours` tiene que re-agendar el timeout y recalcular el badge.
  const hoursKey = useMemo(
    () => JSON.stringify([hours ?? null, dateExceptions ?? null]),
    [hours, dateExceptions],
  );
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    let timeoutId: ReturnType<typeof setTimeout> | undefined;

    const scheduleNextBoundary = () => {
      if (timeoutId !== undefined) clearTimeout(timeoutId);
      const at = nextStatusBoundaryAt(hours, new Date(), dateExceptions);
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
    // `hours` y `dateExceptions` se capturan por closure; la clave serializada
    // garantiza que el efecto se re-ejecute exactamente cuando los DATOS cambian.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hoursKey]);

  return useMemo(() => getOpenStatus(hours, now, dateExceptions), [hoursKey, now]);
}
