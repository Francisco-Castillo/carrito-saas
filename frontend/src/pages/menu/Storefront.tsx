import { useOpenStatus } from "./useOpenStatus";
import type { StorefrontBusiness } from "./types";

/**
 * Portada del local (storefront) para la carta pública: banner, logo, nombre,
 * insignia de abierto/cerrado con los horarios de hoy y la fila de contacto
 * (WhatsApp, Instagram y la dirección como enlace a Maps — no hay coordenadas
 * guardadas, se busca por la dirección codificada).
 *
 * El contacto vive DENTRO de la tarjeta (`.storefront-head`): logo, nombre,
 * insignia y pills forman UN bloque — en pantallas anchas los pills se apoyan
 * a la derecha de la columna de info y en teléfonos bajan debajo (CSS).
 *
 * Todo es opcional: cada elemento desaparece cuando su dato falta, y un local
 * sin portada no renderiza nada (la carta queda exactamente como era).
 *
 * Selector de sucursales (multi-sucursal, odd/tasks/multi-sucursal.md,
 * S4/T7): cuando `business.branches` trae MÁS de una sucursal activa, bajo la
 * tarjeta aparece la fila de botones (nombre + dirección). La identidad
 * pública es el id numérico (`?sucursal=<id>`); la selección y su reflejo en
 * la URL viven en Menu.tsx (mismo dueño del query string que el slug), la
 * portada sólo muestra el estado. Con UNA o ninguna sucursal —o payload viejo
 * sin `branches`— no se renderiza nada: la UI es idéntica a la de hoy. La
 * carta es compartida (S2): la lista no cambia productos ni precios.
 *
 * Los estilos van inline porque este selector no toca Menu.css: el bloque se
 * apoya en las variables del panel de vidrio existente y cualquier ajuste
 * fino queda para un cambio de estilos dedicado.
 *
 * El badge usa `useOpenStatus`: se recalcula en el próximo borde del horario,
 * con un re-chequeo acotado de respaldo y al volver la pestaña visible. Ya no
 * se congela en el render inicial (antes era un useMemo sobre `business`).
 */
export default function Storefront({
  business,
  selectedBranchId = null,
  onSelectBranch,
}: {
  business?: StorefrontBusiness | null;
  /** Sucursal elegida (id de `business.branches`) o null si no hay elección. */
  selectedBranchId?: number | null;
  /** Al elegir: Menu.tsx fija estado y refleja `?sucursal=<id>` en la URL. */
  onSelectBranch?: (id: number) => void;
}) {
  const name = business?.name?.trim() || "";
  const bannerUrl = business?.bannerUrl?.trim() || "";
  const logoUrl = business?.logoUrl?.trim() || "";
  const address = business?.address?.trim() || "";
  const instagramUrl = business?.instagramUrl?.trim() || "";
  const tiktokUrl = business?.tiktokUrl?.trim() || "";
  // wa.me quiere dígitos: "+54 9 11 2345-6789" → "5491123456789".
  const whatsappDigits = (business?.whatsappNumber ?? "").replace(/\D/g, "");
  const hasHours = Boolean(business?.hours && business.hours.length > 0);
  const hasContact = Boolean(whatsappDigits || instagramUrl || tiktokUrl || address);

  // Las excepciones por fecha viajan dentro de `business` (payload del menú):
  // el badge resuelve cada fecha con sus turnos efectivos, no con la plantilla.
  const status = useOpenStatus(business?.hours, business?.dateExceptions);

  // Selector de sucursales (S4/T7): sólo con DOS o más activas. Filtramos ids
  // ausentes por defensa: un payload malformado no debe romper la portada.
  const branches = (business?.branches ?? []).filter(
    (b): b is NonNullable<typeof b> => Boolean(b && typeof b.id === "number" && b.name),
  );
  const showBranchSelector = branches.length > 1;

  if (!name && !bannerUrl && !logoUrl && !hasContact && !hasHours) {
    return null;
  }

  return (
    <section className="storefront stagger" aria-label="El local">
      {bannerUrl && (
        <div className="storefront-banner">
          <img
            src={bannerUrl}
            alt={name ? `Portada de ${name}` : "Portada del local"}
          />
        </div>
      )}

      <div className="storefront-head glass-panel">
        {logoUrl && (
          <img
            className="storefront-logo"
            src={logoUrl}
            alt={name ? `Logo de ${name}` : "Logo del local"}
          />
        )}
        <div className="storefront-titles">
          {name && <h2 className="storefront-name">{name}</h2>}
          {hasHours && (
            <p className="storefront-status">
              <span
                className={`storefront-badge ${status.open ? "is-open" : "is-closed"}`}
              >
                {status.open ? "Abierto" : "Cerrado"}
              </span>
              {status.closingLine && (
                <span className="storefront-status-line">{status.closingLine}</span>
              )}
              {!status.closingLine && status.nextOpeningLine && (
                <span className="storefront-status-line">
                  {status.nextOpeningLine}
                </span>
              )}
            </p>
          )}
          {hasHours && status.todayLine && (
            <p className="storefront-hours">Hoy: {status.todayLine}</p>
          )}
        </div>

        {hasContact && (
          <div className="storefront-contact">
            {/* Redes sociales: ICONOS con aria-label (sin texto), feedback del
                dueño 2026-10-23. TikTok se suma a la fila. */}
            {whatsappDigits && (
              <a
                className="storefront-link storefront-link--icon"
                href={`https://wa.me/${whatsappDigits}`}
                target="_blank"
                rel="noreferrer noopener"
                aria-label="WhatsApp"
              >
                <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                  <path d="M12.04 2C6.58 2 2.13 6.45 2.13 11.91c0 1.75.46 3.45 1.32 4.95L2 22l5.25-1.38a9.9 9.9 0 0 0 4.79 1.22c5.46 0 9.91-4.45 9.91-9.91C21.95 6.45 17.5 2 12.04 2m0 18.12c-1.48 0-2.93-.4-4.2-1.15l-.3-.18-3.12.82.83-3.04-.2-.31a8.26 8.26 0 0 1-1.26-4.38c0-4.54 3.7-8.24 8.25-8.24 4.54 0 8.24 3.7 8.24 8.24s-3.7 8.24-8.24 8.24m4.52-6.16c-.25-.12-1.47-.72-1.69-.81-.23-.08-.39-.12-.56.13-.16.25-.64.81-.78.97-.14.17-.29.19-.54.06-.25-.12-1.05-.39-2-1.23-.74-.66-1.23-1.47-1.38-1.72-.14-.25-.01-.38.11-.51.11-.11.25-.29.37-.43.12-.14.16-.25.25-.41.08-.17.04-.31-.02-.43-.06-.12-.56-1.35-.76-1.84-.2-.48-.41-.42-.56-.43h-.48c-.17 0-.43.06-.66.31-.22.25-.87.85-.87 2.07 0 1.22.89 2.4 1.01 2.56.12.17 1.75 2.67 4.23 3.74.59.26 1.05.41 1.41.52.59.19 1.13.16 1.56.1.48-.07 1.47-.6 1.67-1.18.21-.58.21-1.07.14-1.18-.06-.1-.22-.16-.47-.28z" />
                </svg>
              </a>
            )}
            {instagramUrl && (
              <a
                className="storefront-link storefront-link--icon"
                href={instagramUrl}
                target="_blank"
                rel="noreferrer noopener"
                aria-label="Instagram"
              >
                <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                  <path d="M7.8 2h8.4A5.8 5.8 0 0 1 22 7.8v8.4A5.8 5.8 0 0 1 16.2 22H7.8A5.8 5.8 0 0 1 2 16.2V7.8A5.8 5.8 0 0 1 7.8 2m-.2 2A3.6 3.6 0 0 0 4 7.6v8.8A3.6 3.6 0 0 0 7.6 20h8.8a3.6 3.6 0 0 0 3.6-3.6V7.6A3.6 3.6 0 0 0 16.4 4H7.6m9.65 1.5a1.25 1.25 0 1 1 0 2.5 1.25 1.25 0 0 1 0-2.5M12 7a5 5 0 1 1 0 10 5 5 0 0 1 0-10m0 2a3 3 0 1 0 0 6 3 3 0 0 0 0-6z" />
                </svg>
              </a>
            )}
            {tiktokUrl && (
              <a
                className="storefront-link storefront-link--icon"
                href={tiktokUrl}
                target="_blank"
                rel="noreferrer noopener"
                aria-label="TikTok"
              >
                <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                  <path d="M16.6 5.82A4.28 4.28 0 0 1 15.54 3h-3.09v12.4a2.59 2.59 0 1 1-2.59-2.59c.28 0 .55.05.8.13V9.4a6.13 6.13 0 0 0-.8-.05 5.66 5.66 0 1 0 5.66 5.66V9.01a7.35 7.35 0 0 0 4.3 1.38V7.3a4.28 4.28 0 0 1-3.22-1.48z" />
                </svg>
              </a>
            )}
            {address && (
              <a
                className="storefront-link storefront-link--address"
                href={`https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(address)}`}
                target="_blank"
                rel="noreferrer noopener"
              >
                {address}
              </a>
            )}
          </div>
        )}
      </div>

      {showBranchSelector && (
        <div
          className="storefront-branches"
          role="group"
          aria-label="Elegí una sucursal"
          style={{
            display: "flex",
            flexWrap: "wrap",
            gap: "0.5rem",
            margin: "0.75rem auto 0",
            maxWidth: "56rem",
            width: "100%",
            padding: "0 1rem",
            boxSizing: "border-box",
          }}
        >
          {branches.map((b) => {
            const selected = b.id === selectedBranchId;
            const address = b.address?.trim() || "";
            return (
              <button
                key={b.id}
                type="button"
                className={`storefront-branch${selected ? " is-selected" : ""}`}
                aria-pressed={selected}
                onClick={() => onSelectBranch?.(b.id)}
                style={{
                  display: "flex",
                  flexDirection: "column",
                  alignItems: "flex-start",
                  gap: "0.15rem",
                  flex: "1 1 12rem",
                  padding: "0.65rem 0.9rem",
                  borderRadius: "0.9rem",
                  border: selected
                    ? "1px solid var(--primary-color, #7c3aed)"
                    : "1px solid rgba(15, 23, 42, 0.15)",
                  background: selected ? "rgba(124, 58, 237, 0.08)" : "rgba(255, 255, 255, 0.6)",
                  color: "inherit",
                  font: "inherit",
                  textAlign: "left",
                  cursor: "pointer",
                }}
              >
                <span style={{ fontWeight: 600 }}>{b.name}</span>
                {address && (
                  <span style={{ fontSize: "0.8rem", opacity: 0.75 }}>{address}</span>
                )}
              </button>
            );
          })}
        </div>
      )}
    </section>
  );
}
