import type { DashboardToday } from "./api";
import { formatARS } from "../../format";
import { formatPrepTime } from "./format";

interface KpiCardsProps {
  /** null = sin datos aún: la card muestra los valores iniciales del legacy (0 / $0 / 0m). */
  today: DashboardToday | null;
  /** Primera carga en curso: muestra 4 skeletons en vez de ceros que parecen reales. */
  loading?: boolean;
  /** La API falló y no hay datos: mensaje visible en vez de $0 en silencio. */
  error?: boolean;
}

const SKELETON_CARDS = ["Pedidos hoy", "Total vendido hoy", "Ticket promedio", "Tiempo cocina"];

/** Las 4 KPIs del legacy dashboard/index.html, con el copy español intacto. */
export default function KpiCards({ today, loading = false, error = false }: KpiCardsProps) {
  if (loading && !today) {
    return (
      <section className="stats stagger" aria-label="Cargando métricas de hoy" aria-busy="true">
        {SKELETON_CARDS.map((label) => (
          <article key={label} className="stat-card glass-panel skeleton-card">
            <span>{label}</span>
            <div className="skeleton skeleton-line--lg" />
          </article>
        ))}
      </section>
    );
  }

  if (error && !today) {
    return (
      <section className="stats stagger" aria-label="Métricas de hoy" aria-live="polite">
        <article className="stat-card glass-panel section-error">
          <span>No se pudieron cargar las métricas de hoy.</span>
          <p>Reintentamos automáticamente cada minuto.</p>
        </article>
      </section>
    );
  }

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
