import { useEffect, useState } from "react";

/**
 * Comanda printing settings of the business, read once from the storefront
 * profile (GET /api/business/profile — open to every authenticated business
 * role, so the PEDIDOS console can read it too):
 *
 *   - printingEnabled: master switch set by the owner in the admin panel.
 *     OFF hides the manual Imprimir button and silences auto-printing.
 *   - autoPrint: print each incoming NEW order; only meaningful when the
 *     master is ON.
 *
 * Fail-closed: until the profile loads (or if it fails), both flags are OFF —
 * the panel never prints on a guess. Defaults are OFF server-side too, so
 * enabling the feature never changes an existing tenant's behavior.
 */
export interface ComandaSettings {
  printingEnabled: boolean;
  autoPrint: boolean;
}

export function useComandaSettings(enabled: boolean): ComandaSettings {
  const [printingEnabled, setPrintingEnabled] = useState(false);
  const [autoPrint, setAutoPrint] = useState(false);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    (async () => {
      try {
        const response = await fetch("/api/business/profile", {
          headers: {
            Authorization: "Bearer " + (localStorage.getItem("token") ?? ""),
          },
        });
        if (!response.ok) return;
        const data = (await response.json()) as {
          comandaPrintingEnabled?: boolean;
          comandaAutoPrint?: boolean;
        };
        if (cancelled) return;
        setPrintingEnabled(data.comandaPrintingEnabled === true);
        setAutoPrint(data.comandaAutoPrint === true);
      } catch (e) {
        // Sin perfil no se imprime: OFF es el default seguro.
        console.error("Error cargando la configuración de comandas", e);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [enabled]);

  return { printingEnabled, autoPrint };
}
