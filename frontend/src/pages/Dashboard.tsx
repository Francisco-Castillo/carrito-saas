import { useLayoutEffect, useState } from "react";
import "./Dashboard.css";
import { hasPanelSession, enforceSession, logout } from "./dashboard/auth";
import { useDashboardData } from "./dashboard/useDashboardData";
import { useLiveOrders } from "./dashboard/useLiveOrders";
import KpiCards from "./dashboard/KpiCards";
import SalesChart from "./dashboard/SalesChart";
import SalesByDayChart from "./dashboard/SalesByDayChart";
import TopProducts from "./dashboard/TopProducts";
import ProductTrends from "./dashboard/ProductTrends";

export default function Dashboard() {
  // Guard del legacy (index.html <head>): corre antes del primer paint y
  // nunca deja la página corriendo sin sesión usable. Fase 2 (login
  // orgánico): el panel también exige el slug de sesión (hasPanelSession) —
  // una sesión con token pero sin slug vuelve al login por enforceSession
  // antes de renderizar o fetchear, nunca con paneles vacíos en silencio.
  const [sessionUsable] = useState(hasPanelSession);

  useLayoutEffect(() => {
    if (!sessionUsable) {
      enforceSession();
    }
  }, [sessionUsable]);

  // initDashboard() del legacy: checkAuth primero, luego loads en paralelo
  // (hook compartido con la sección embebida del admin).
  const { today, topProducts, salesByHour, salesByDay, salesPrevWeek, trendsUp, trendsDown, date, loading, errors, refresh } =
    useDashboardData(sessionUsable);

  // Indicador en vivo (S9): badge de pedidos activos + ding al entrar un
  // pedido nuevo. El WS event dispara el refetch del dashboard, así que las
  // KPIs del día reaccionan al instante sin esperar el auto-refresh.
  const { activeCount, live } = useLiveOrders(sessionUsable, refresh);

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
          {/* Indicador en vivo: badge de pedidos activos + punto de conexión.
              Solo el dashboard pantalla completa — la sección del admin vive
              dentro de un panel más chico y no tiene topbar propia. */}
          <span
            className="live-badge"
            title={live ? "Conectado en vivo" : "Reconectando…"}
          >
            <span className={`live-dot ${live ? "live-dot--on" : "live-dot--off"}`} aria-hidden="true" />
            {activeCount} activos
          </span>

          {/* Entrada a la consola de pedidos (orders-console S3): mismo patrón
              que el botón de salir — un enlace de la topbar, sin rediseñar la
              navegación existente. */}
          <a href="/pedidos" className="topbar-link">
            Pedidos
          </a>
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
        <KpiCards today={today} loading={loading} error={errors.today} />

        <SalesChart data={salesByHour} loading={loading} error={errors.salesByHour} />

        <SalesByDayChart data={salesByDay} prevData={salesPrevWeek} loading={loading} error={errors.salesByDay} />

        <div className="top-grid">
          <TopProducts products={topProducts} loading={loading} error={errors.topProducts} />

          <ProductTrends trendsUp={trendsUp} trendsDown={trendsDown} loading={loading} error={errors.trends} />
        </div>
      </main>
      {/* Sonido de pedido nuevo: mismo <audio> por id que el KDS y la consola
          (playNewOrderSound lo busca por document.getElementById). */}
      <audio id="newOrderSound" src="/ding.mp3" preload="auto" />
    </div>
  );
}
