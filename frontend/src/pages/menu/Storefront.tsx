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
 * El badge usa `useOpenStatus`: se recalcula en el próximo borde del horario,
 * con un re-chequeo acotado de respaldo y al volver la pestaña visible. Ya no
 * se congela en el render inicial (antes era un useMemo sobre `business`).
 */
export default function Storefront({
  business,
}: {
  business?: StorefrontBusiness | null;
}) {
  const name = business?.name?.trim() || "";
  const bannerUrl = business?.bannerUrl?.trim() || "";
  const logoUrl = business?.logoUrl?.trim() || "";
  const address = business?.address?.trim() || "";
  const instagramUrl = business?.instagramUrl?.trim() || "";
  // wa.me quiere dígitos: "+54 9 11 2345-6789" → "5491123456789".
  const whatsappDigits = (business?.whatsappNumber ?? "").replace(/\D/g, "");
  const hasHours = Boolean(business?.hours && business.hours.length > 0);
  const hasContact = Boolean(whatsappDigits || instagramUrl || address);

  // Las excepciones por fecha viajan dentro de `business` (payload del menú):
  // el badge resuelve cada fecha con sus turnos efectivos, no con la plantilla.
  const status = useOpenStatus(business?.hours, business?.dateExceptions);

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
            {whatsappDigits && (
              <a
                className="storefront-link"
                href={`https://wa.me/${whatsappDigits}`}
                target="_blank"
                rel="noreferrer noopener"
              >
                WhatsApp
              </a>
            )}
            {instagramUrl && (
              <a
                className="storefront-link"
                href={instagramUrl}
                target="_blank"
                rel="noreferrer noopener"
              >
                Instagram
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
    </section>
  );
}
