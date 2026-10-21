import type { DashboardToday } from "./api";
import { formatARS } from "../../format";

interface KpiCardsProps {
  /** null = sin datos aún: la card muestra los valores iniciales del legacy (0 / $0 / 0m). */
  today: DashboardToday | null;
  /** Total facturado de los últimos 7 días (suma de salesByDay); null = sin datos. */
  weekTotal: number | null;
  /** Primera carga en curso: muestra 4 skeletons en vez de ceros que parecen reales. */
  loading?: boolean;
  /** La API falló y no hay datos: mensaje visible en vez de $0 en silencio. */
  error?: boolean;
}

const SKELETON_CARDS = ["Pedidos hoy", "Total vendido hoy", "Ticket promedio", "Ventas 7 días"];

/** Las 4 KPIs del legacy dashboard/index.html, con el copy español intacto
 *  salvo la 4ª: el tiempo de cocina vivía mejor en el KDS, así que la card
 *  muestra el total de la semana (dato que el dueño sí mira). */
export default function KpiCards({ today, weekTotal, loading = false, error = false }: KpiCardsProps) {
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
  const week = weekTotal !== null ? formatARS(weekTotal) : "$0";

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
        <span>Ventas 7 días</span>
        <h2>{week}</h2>
      </article>
    </section>
  );
}
