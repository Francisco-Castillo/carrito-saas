import { useLayoutEffect, useState } from "react";
import "./Dashboard.css";
import { hasUsableSession, enforceSession, logout } from "./dashboard/auth";
import { useDashboardData } from "./dashboard/useDashboardData";
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

  // initDashboard() del legacy: checkAuth primero, luego loads en paralelo
  // (hook compartido con la sección embebida del admin).
  const { today, status, topProducts, salesByHour, date } =
    useDashboardData(sessionUsable);

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
