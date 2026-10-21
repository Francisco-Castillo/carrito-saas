import type { ProductTrend } from "./api";

interface ProductTrendsProps {
  trendsUp: ProductTrend[];
  trendsDown: ProductTrend[];
  /** Primera carga en curso: esqueleto en vez de columnas vacías. */
  loading?: boolean;
  /** La API falló y no hay datos: mensaje visible. */
  error?: boolean;
}

/**
 * Tendencias de productos (servicio product_trends, alimentado por
 * /api/trends/run): qué está subiendo y qué bajando vs. el período anterior.
 * Columna vacía = el servicio de tendencias todavía no corrió para este local
 * (o no hay datos suficientes), no un error.
 */
export default function ProductTrends({
  trendsUp,
  trendsDown,
  loading = false,
  error = false,
}: ProductTrendsProps) {
  const empty = trendsUp.length === 0 && trendsDown.length === 0;

  return (
    <section className="top-products panel glass-panel fade-up">
      <h2>Tendencias de productos</h2>

      {loading && empty ? (
        <div className="trends-grid" aria-busy="true" aria-label="Cargando tendencias">
          <div className="skeleton skeleton-line" style={{ width: "70%" }} />
          <div className="skeleton skeleton-line" style={{ width: "55%" }} />
        </div>
      ) : error && empty ? (
        <div className="section-error" aria-live="polite">
          <span>No se pudieron cargar las tendencias.</span>
          <p>Reintentamos automáticamente cada minuto.</p>
        </div>
      ) : empty ? (
        <p className="section-empty">
          Todavía no hay datos de tendencia suficientes para este local.
        </p>
      ) : (
        <div className="trends-grid">
          <div>
            <h3 className="trends-heading trends-heading--up">▲ Suben</h3>
            <ul>
              {trendsUp.map((t) => (
                <li key={`up-${t.productId}`}>
                  {t.productName}
                  <span className="trend-value trend-value--up">
                    +{t.trendPercentage?.toFixed(0) ?? 0}%
                  </span>
                </li>
              ))}
            </ul>
          </div>

          <div>
            <h3 className="trends-heading trends-heading--down">▼ Bajan</h3>
            <ul>
              {trendsDown.map((t) => (
                <li key={`down-${t.productId}`}>
                  {t.productName}
                  <span className="trend-value trend-value--down">
                    {t.trendPercentage?.toFixed(0) ?? 0}%
                  </span>
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}
    </section>
  );
}
