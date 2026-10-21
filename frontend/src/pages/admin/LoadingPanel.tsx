import { Fragment } from "react";

/**
 * Estado de carga de las secciones del panel, con el mismo efecto de
 * skeletons que el dashboard (clases .skeleton* de skeleton.css, ahora
 * compartidas). Reemplaza al `return null` que dejaba la sección sin
 * ninguna señal mientras la API respondía.
 *
 * El `role="status"` anuncia el label a screen readers; las líneas de
 * skeleton son decorativas y dan la forma de lo que va a aparecer.
 */
export default function LoadingPanel({
  label,
  lines = 5,
}: {
  label: string;
  lines?: number;
}) {
  return (
    <section className="glass-panel admin-table-panel" aria-busy="true">
      <p className="admin-loading-status" role="status" aria-label={label}>
        {label}
      </p>
      <div className="admin-loading-skeleton" aria-hidden="true">
        {Array.from({ length: lines }, (_, i) => (
          <Fragment key={i}>
            <div className="skeleton skeleton-line" style={{ width: `${88 - (i % 3) * 14}%` }} />
            {i === 0 && <div className="skeleton skeleton-line--lg" />}
          </Fragment>
        ))}
      </div>
    </section>
  );
}
