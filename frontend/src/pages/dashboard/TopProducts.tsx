import type { TopProduct } from "./api";

interface TopProductsProps {
  products: TopProduct[];
  /** Primera carga en curso: lista esqueleto en vez de "sin productos". */
  loading?: boolean;
  /** La API falló y no hay datos: mensaje visible en vez de una lista vacía. */
  error?: boolean;
}

/** "Top productos" del legacy dashboard/index.html + loadTopProducts(). */
export default function TopProducts({ products, loading = false, error = false }: TopProductsProps) {
  const empty = products.length === 0;

  return (
    <section className="top-products panel glass-panel fade-up">
      <h2>Top productos</h2>

      {loading && empty ? (
        <div className="table-scroll" aria-busy="true" aria-label="Cargando top productos">
          {[0, 1, 2].map((i) => (
            <div key={i} className="skeleton skeleton-line" style={{ width: `${90 - i * 20}%` }} />
          ))}
        </div>
      ) : error && empty ? (
        <div className="section-error" aria-live="polite">
          <span>No se pudieron cargar los top productos.</span>
          <p>Reintentamos automáticamente cada minuto.</p>
        </div>
      ) : empty ? (
        <p className="section-empty">Todavía no hay ventas registradas hoy.</p>
      ) : (
        <div className="table-scroll">
          <ul>
            {products.map((p) => (
              <li key={`${p.productName}-${p.quantity}`}>
                {p.productName} ({p.quantity})
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
