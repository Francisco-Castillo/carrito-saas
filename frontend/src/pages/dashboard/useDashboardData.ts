import { useEffect, useState } from "react";
import { api } from "./api";
import { checkAuth } from "./auth";
import type {
  DashboardToday,
  SalesByDay,
  SalesByHour,
  TopProduct,
} from "./api";

/**
 * Loaders del dashboard, extraídos de Dashboard.tsx para poder reutilizarlos
 * desde la sección de dashboard embebida del admin (el legacy admin.js también
 * llama a initDashboard() al renderizar su vista "dashboard").
 *
 * checkAuth primero, luego loads en paralelo. Los catch reproducen los
 * console.error del legacy: sin datos, la UI conserva los valores iniciales
 * en vez de romper la página.
 *
 * Auto-refresh: los cuatro loads se repiten cada REFRESH_MS mientras la
 * pestaña esté visible — los pedidos cambian sin que el dueño recargue.
 * Con la pestaña oculta el intervalo sigue pero se salta el fetch (los
 * timers en background ya vienen throttled por el navegador; el salto
 * evita también el ráfaga de requests al volver a varias pestañas).
 */
const REFRESH_MS = 60_000;

/** Cada loader del hook, identificado para el estado de error por sección. */
type LoadKey = "today" | "topProducts" | "salesByHour" | "salesByDay";

export interface DashboardErrors {
  today: boolean;
  topProducts: boolean;
  salesByHour: boolean;
  salesByDay: boolean;
}

export function useDashboardData(enabled: boolean) {
  const [today, setToday] = useState<DashboardToday | null>(null);
  const [topProducts, setTopProducts] = useState<TopProduct[]>([]);
  const [salesByHour, setSalesByHour] = useState<SalesByHour[] | null>(null);
  const [salesByDay, setSalesByDay] = useState<SalesByDay[] | null>(null);
  const [date, setDate] = useState("");

  // Primera carga: los skeletons están arriba hasta que la tanda inicial
  // termina (bien o mal). Los refetch del auto-refresh NO muestran skeletons:
  // los datos en pantalla se actualizan en silencio.
  const [loading, setLoading] = useState(true);
  const [errors, setErrors] = useState<DashboardErrors>({
    today: false,
    topProducts: false,
    salesByHour: false,
    salesByDay: false,
  });

  useEffect(() => {
    if (!enabled) return;

    checkAuth();

    setDate(new Date().toLocaleDateString("es-ES"));

    const track = (key: LoadKey, promise: Promise<unknown>) =>
      promise
        .then(() => setErrors((prev) => ({ ...prev, [key]: false })))
        .catch((e) => {
          setErrors((prev) => ({ ...prev, [key]: true }));
          console.error(`Error cargando ${key}`, e);
        });

    const loadAll = () => {
      if (document.hidden) return;

      // Promise.allSettled: una sección fallida no tapa a las demás.
      void Promise.allSettled([
        track("today", api.getDashboardToday().then(setToday)),
        track("topProducts", api.getTopProducts().then(setTopProducts)),
        track("salesByHour", api.getSalesByHour().then(setSalesByHour)),
        track("salesByDay", api.getSalesLast7Days().then(setSalesByDay)),
      ]).then(() => setLoading(false));
    };

    loadAll();

    const intervalId = setInterval(loadAll, REFRESH_MS);

    return () => clearInterval(intervalId);
  }, [enabled]);

  return { today, topProducts, salesByHour, salesByDay, date, loading, errors };
}
