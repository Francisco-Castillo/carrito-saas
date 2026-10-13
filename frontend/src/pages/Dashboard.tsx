import { useEffect, useLayoutEffect, useState } from "react";
import "./Dashboard.css";
import { api } from "./dashboard/api";
import { checkAuth, enforceSession, hasUsableSession, logout } from "./dashboard/auth";
import type {
  DashboardToday,
  OrderStatusSummary,
  SalesByHour,
  TopProduct,
} from "./dashboard/api";
import KpiCards from "./dashboard/KpiCards";
import OrderStatusCards from "./dashboard/OrderStatusCards";
import SalesChart from "./dashboard/SalesChart";
import TopProducts from "./dashboard/TopProducts";

export default function Dashboard() {
  // Guard del legacy (index.html <head>): corre antes del primer paint y
  // nunca deja la página corriendo sin sesión usable.
  const [sessionUsable] = useState(hasUsableSession);

  useLayoutEffect(() => {
    if (!sessionUsable) {
      enforceSession();
    }
  }, [sessionUsable]);

  const [today, setToday] = useState<DashboardToday | null>(null);
  const [status, setStatus] = useState<OrderStatusSummary | null>(null);
  const [topProducts, setTopProducts] = useState<TopProduct[]>([]);
  const [salesByHour, setSalesByHour] = useState<SalesByHour[] | null>(null);
  const [date, setDate] = useState("");

  useEffect(() => {
    if (!sessionUsable) return;

    // initDashboard() del legacy: checkAuth primero, luego loads en paralelo.
    // Los catch reproducen los console.error del legacy: sin datos, la UI
    // conserva los valores iniciales en vez de romper la página.
    checkAuth();

    setDate(new Date().toLocaleDateString("es-ES"));

    api
      .getDashboardToday()
      .then(setToday)
      .catch((e) => console.error("Error cargando dashboard", e));

    api
      .getOrderStatusSummary()
      .then(setStatus)
      .catch((e) => console.error("Error cargando estados", e));

    api
      .getTopProducts()
      .then(setTopProducts)
      .catch((e) => console.error("Error cargando top productos", e));

    api
      .getSalesByHour()
      .then(setSalesByHour)
      .catch((e) => console.error("Error cargando grafico", e));
  }, [sessionUsable]);

  if (!sessionUsable) {
    // Redirect en curso: no renderizamos la página sin sesión.
    return null;
  }

  return (
    <div className="dashboard-screen">
      <div className="aurora" aria-hidden="true">
        <div className="aurora-blob aurora-blob--indigo" />
        <div className="aurora-blob aurora-blob--violet" />
        <div className="aurora-blob aurora-blob--fuchsia" />
        <div className="aurora-blob aurora-blob--cyan" />
      </div>

      <header className="topbar">
        <div className="title">
          <h1>Dashboard</h1>
          <span id="date">{date}</span>
        </div>

        <div className="actions">
          <button
            type="button"
            onClick={logout}
            className="logout-btn"
            title="Cerrar sesión"
          >
            <svg
              xmlns="http://www.w3.org/2000/svg"
              width="18"
              height="18"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
              <polyline points="16 17 21 12 16 7" />
              <line x1="21" y1="12" x2="9" y2="12" />
            </svg>
            <span>Salir</span>
          </button>
        </div>
      </header>

      <main>
        <KpiCards today={today} />

        <OrderStatusCards status={status} />

        <SalesChart data={salesByHour} />

        <TopProducts products={topProducts} />
      </main>
    </div>
  );
}
