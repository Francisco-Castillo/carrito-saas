import type { MenuBranch } from "./types";

/**
 * Pantalla previa de elección de sucursal (multi-sucursal,
 * odd/tasks/multi-sucursal.md, S4/T8): con DOS o más sucursales activas y la
 * URL sin `?sucursal=`, el link del grupo abre ESTA pantalla en lugar de la
 * carta. Al elegir, Menu.tsx refleja `?sucursal=<id>` en la URL y vuelve a
 * pedir el menú YA scopeado a esa sucursal.
 *
 * El selector NO vive dentro de la carta (corrección de producto L10): la
 * carta nunca muestra un control de sucursal — esta pantalla es el único
 * lugar de elección cuando hay más de una. Con una o ninguna sucursal activa
 * este componente ni se monta (directo a la carta, cero regresión).
 *
 * La identidad pública es el id NUMÉRICO de la sucursal (el mismo que codifica
 * el QR por sucursal, T6); la lista no cambia productos ni precios: la carta
 * es compartida (S2).
 *
 * Estética: clases de `Menu.css` sobre los tokens del design system (vidrio +
 * acento), no estilos inline — así la pantalla se ve parte del storefront y
 * no una capa pegada encima.
 */
export default function BranchPicker({
  businessName,
  branches,
  onSelect,
}: {
  /** Nombre del grupo/local, para el copy de la pantalla (opcional). */
  businessName?: string;
  branches: MenuBranch[];
  /** Al elegir: Menu.tsx fija `?sucursal=<id>` en la URL y refresca el menú. */
  onSelect: (id: number) => void;
}) {
  return (
    <section className="branch-picker stagger" aria-label="Elegí una sucursal">
      <div className="glass-panel branch-picker-card">
        <div className="branch-picker-brand">
          <span className="branch-picker-mark" aria-hidden="true">
            <StoreIcon />
          </span>
          <div>
            <p className="branch-picker-brand-name">{businessName ?? "Nuestros locales"}</p>
            <p className="branch-picker-brand-hint">Carta compartida en todas las sucursales</p>
          </div>
        </div>

        <h2 className="branch-picker-title">¿En qué sucursal vas a pedir?</h2>

        <div className="branch-picker-options">
          {branches.map((b) => {
            const address = b.address?.trim() || "";
            return (
              <button
                key={b.id}
                type="button"
                className="branch-option"
                onClick={() => onSelect(b.id)}
              >
                <span className="branch-option-icon" aria-hidden="true">
                  <PinIcon />
                </span>
                <span className="branch-option-text">
                  <span className="branch-option-name">{b.name}</span>
                  {address && <span className="branch-option-address">{address}</span>}
                </span>
                <span className="branch-option-chevron" aria-hidden="true">
                  <ChevronIcon />
                </span>
              </button>
            );
          })}
        </div>
      </div>
    </section>
  );
}

/** Íconos inline: la carta no trae librería de íconos y no vale una dependencia. */
function StoreIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
      strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 9.5 4.5 4h15L21 9.5" />
      <path d="M3 9.5a2.5 2.5 0 0 0 5 0 2.5 2.5 0 0 0 5 0 2.5 2.5 0 0 0 5 0 2.5 2.5 0 0 0 3 0" />
      <path d="M5 11v9h14v-9" />
      <path d="M10 20v-5h4v5" />
    </svg>
  );
}

function PinIcon() {
  return (
    <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
      strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 21s7-5.5 7-11a7 7 0 1 0-14 0c0 5.5 7 11 7 11Z" />
      <circle cx="12" cy="10" r="2.5" />
    </svg>
  );
}

function ChevronIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
      strokeLinecap="round" strokeLinejoin="round">
      <path d="m9 6 6 6-6 6" />
    </svg>
  );
}
