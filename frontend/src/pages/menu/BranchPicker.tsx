import type { MenuBranch } from "./types";

/**
 * Pantalla previa de elección de sucursal (multi-sucursal,
 * odd/tasks/multi-sucursal.md, S4/T8): con DOS o más sucursales activas y la
 * URL sin `?sucursal=`, el link del grupo abre ESTA pantalla en lugar de la
 * carta. Lista las sucursales activas (nombre + dirección) como opciones
 * grandes tocables; al elegir, Menu.tsx refleja `?sucursal=<id>` en la URL y
 * vuelve a pedir el menú YA scopeado a esa sucursal.
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
 * Estilos inline porque la pantalla no toca Menu.css (fuera de las superficies
 * de esta unidad): se apoya en las clases de vidrio existentes.
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
    <section
      className="storefront stagger"
      aria-label="Elegí una sucursal"
      style={{
        maxWidth: "28rem",
        margin: "3rem auto 0",
        padding: "0 1rem",
        width: "100%",
        boxSizing: "border-box",
      }}
    >
      <div className="glass-panel" style={{ padding: "1.5rem", textAlign: "center" }}>
        <h2 style={{ margin: "0 0 0.25rem", fontSize: "1.4rem" }}>
          Elegí una sucursal
        </h2>
        <p style={{ margin: "0 0 1.25rem", opacity: 0.75 }}>
          {businessName
            ? `¿En qué local de ${businessName} vas a pedir?`
            : "¿En qué local vas a pedir?"}
        </p>
        <div
          style={{
            display: "flex",
            flexDirection: "column",
            gap: "0.75rem",
          }}
        >
          {branches.map((b) => {
            const address = b.address?.trim() || "";
            return (
              <button
                key={b.id}
                type="button"
                className="storefront-branch"
                onClick={() => onSelect(b.id)}
                style={{
                  display: "flex",
                  flexDirection: "column",
                  alignItems: "flex-start",
                  gap: "0.15rem",
                  width: "100%",
                  padding: "1rem 1.1rem",
                  borderRadius: "0.9rem",
                  border: "1px solid rgba(15, 23, 42, 0.15)",
                  background: "rgba(255, 255, 255, 0.6)",
                  color: "inherit",
                  font: "inherit",
                  textAlign: "left",
                  cursor: "pointer",
                }}
              >
                <span style={{ fontWeight: 700, fontSize: "1.05rem" }}>{b.name}</span>
                {address && (
                  <span style={{ fontSize: "0.85rem", opacity: 0.75 }}>{address}</span>
                )}
              </button>
            );
          })}
        </div>
      </div>
    </section>
  );
}
