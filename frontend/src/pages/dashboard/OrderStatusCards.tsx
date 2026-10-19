import type { OrderStatusSummary } from "./api";

interface OrderStatusCardsProps {
  /** null = sin datos aún: las cards muestran 0, como el DOM inicial del legacy. */
  status: OrderStatusSummary | null;
}

/** "Pedidos activos" del legacy dashboard/index.html. */
export default function OrderStatusCards({ status }: OrderStatusCardsProps) {
  const newOrders = String(status?.NEW || 0);
  const preparing = String(status?.PREPARING || 0);
  const ready = String(status?.READY || 0);

  return (
    <section className="orders-status panel glass-panel fade-up">
      <h2>Pedidos activos</h2>

      <div className="status-grid">
        <div className="status-card">
          <span>Nuevos</span>
          <h3>{newOrders}</h3>
        </div>

        <div className="status-card">
          <span>Preparando</span>
          <h3>{preparing}</h3>
        </div>

        <div className="status-card">
          <span>Listos</span>
          <h3>{ready}</h3>
        </div>
      </div>
    </section>
  );
}
