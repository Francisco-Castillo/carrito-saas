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

export function useDashboardData(enabled: boolean) {
  const [today, setToday] = useState<DashboardToday | null>(null);
  const [topProducts, setTopProducts] = useState<TopProduct[]>([]);
  const [salesByHour, setSalesByHour] = useState<SalesByHour[] | null>(null);
  const [salesByDay, setSalesByDay] = useState<SalesByDay[] | null>(null);
  const [date, setDate] = useState("");

  useEffect(() => {
    if (!enabled) return;

    checkAuth();

    setDate(new Date().toLocaleDateString("es-ES"));

    const loadAll = () => {
      if (document.hidden) return;

      api
        .getDashboardToday()
        .then(setToday)
        .catch((e) => console.error("Error cargando dashboard", e));

      api
        .getTopProducts()
        .then(setTopProducts)
        .catch((e) => console.error("Error cargando top productos", e));

      api
        .getSalesByHour()
        .then(setSalesByHour)
        .catch((e) => console.error("Error cargando grafico", e));

      api
        .getSalesLast7Days()
        .then(setSalesByDay)
        .catch((e) => console.error("Error cargando ventas semanales", e));
    };

    loadAll();

    const intervalId = setInterval(loadAll, REFRESH_MS);

    return () => clearInterval(intervalId);
  }, [enabled]);

  return { today, topProducts, salesByHour, salesByDay, date };
}
