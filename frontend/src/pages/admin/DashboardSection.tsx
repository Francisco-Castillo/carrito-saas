import { useDashboardData } from "../dashboard/useDashboardData";
import KpiCards from "../dashboard/KpiCards";
import SalesChart from "../dashboard/SalesChart";
import SalesByDayChart from "../dashboard/SalesByDayChart";
import TopProducts from "../dashboard/TopProducts";

/**
 * Sección de dashboard embebida del admin: el legacy admin.js renderizaba el
 * mismo bloque de métricas que dashboard/index.html y llamaba initDashboard()
 * (admin.js:3 y admin.js:96). Acá reutilizamos los componentes y loaders del
 * dashboard existentes en vez de duplicarlos.
 */
export default function DashboardSection() {
  const { today, topProducts, salesByHour, salesByDay, loading, errors } =
    useDashboardData(true);

  return (
    <div className="admin-dashboard">
      <KpiCards today={today} loading={loading} error={errors.today} />

      <SalesChart data={salesByHour} loading={loading} error={errors.salesByHour} />

      <SalesByDayChart data={salesByDay} loading={loading} error={errors.salesByDay} />

      <TopProducts products={topProducts} loading={loading} error={errors.topProducts} />
    </div>
  );
}
