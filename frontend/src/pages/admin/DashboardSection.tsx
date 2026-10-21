import { useDashboardData } from "../dashboard/useDashboardData";
import KpiCards from "../dashboard/KpiCards";
import SalesChart from "../dashboard/SalesChart";
import TopProducts from "../dashboard/TopProducts";

/**
 * Sección de dashboard embebida del admin: el legacy admin.js renderizaba el
 * mismo bloque de métricas que dashboard/index.html y llamaba initDashboard()
 * (admin.js:3 y admin.js:96). Acá reutilizamos los componentes y loaders del
 * dashboard existentes en vez de duplicarlos.
 */
export default function DashboardSection() {
  const { today, topProducts, salesByHour } = useDashboardData(true);

  return (
    <div className="admin-dashboard">
      <KpiCards today={today} />

      <SalesChart data={salesByHour} />

      <TopProducts products={topProducts} />
    </div>
  );
}
