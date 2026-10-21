import { useEffect, useState } from "react";
import { api } from "./api";
import { checkAuth } from "./auth";
import type {
  DashboardToday,
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
 */
export function useDashboardData(enabled: boolean) {
  const [today, setToday] = useState<DashboardToday | null>(null);
  const [topProducts, setTopProducts] = useState<TopProduct[]>([]);
  const [salesByHour, setSalesByHour] = useState<SalesByHour[] | null>(null);
  const [date, setDate] = useState("");

  useEffect(() => {
    if (!enabled) return;

    checkAuth();

    setDate(new Date().toLocaleDateString("es-ES"));

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
  }, [enabled]);

  return { today, topProducts, salesByHour, date };
}
