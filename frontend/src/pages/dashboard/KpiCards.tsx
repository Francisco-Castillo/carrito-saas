import type { DashboardToday } from "./api";
import { formatARS, formatPrepTime } from "./format";

interface KpiCardsProps {
  /** null = sin datos aún: la card muestra los valores iniciales del legacy (0 / $0 / 0m). */
  today: DashboardToday | null;
}

/** Las 4 KPIs del legacy dashboard/index.html, con el copy español intacto. */
export default function KpiCards({ today }: KpiCardsProps) {
  const orders = today ? String(today.orders) : "0";
  const revenue = today ? formatARS(today.revenue) : "$0";
  const avgTicket = today ? formatARS(today.avgTicket) : "$0";
  const avgPrep = today ? formatPrepTime(today.avgPrepTime) : "0m";

  return (
    <section className="stats stagger" aria-label="Métricas de hoy">
      <article className="stat-card glass-panel">
        <span>Pedidos hoy</span>
        <h2>{orders}</h2>
      </article>

      <article className="stat-card glass-panel">
        <span>Total vendido hoy</span>
        <h2>{revenue}</h2>
      </article>

      <article className="stat-card glass-panel">
        <span>Ticket promedio</span>
        <h2>{avgTicket}</h2>
      </article>

      <article className="stat-card glass-panel">
        <span>Tiempo cocina</span>
        <h2>{avgPrep}</h2>
      </article>
    </section>
  );
}
