import { useState } from "react";
import { formatARS } from "../../format";
import FoodImage from "./FoodImage";
import type { Product, ProductOptionGroup } from "./types";
import { useModalSheet } from "./useModalSheet";

interface ProductDetailSheetProps {
  product: Product;
  onClose: () => void;
  /**
   * Adds `qty` units to the cart; the parent closes the sheet afterwards.
   * `selectedOptionIds` son las opciones elegidas (odd/tasks/product-options.md
   * S4): llegan validadas (obligatorios completos) y ordenadas por grupo.
   * `note` son las preferencias del cliente para el ítem ("sin mayonesa"):
   * opcional, máx 300 (el backend valida y recorta).
   */
  onAdd: (qty: number, selectedOptionIds: number[], note: string) => void;
}

/** Precio unitario del producto con los recargos de las opciones elegidas. */
function priceWithSelections(product: Product, selected: number[]): number {
  let total = product.price;
  for (const group of product.optionGroups ?? []) {
    for (const option of group.options) {
      if (selected.includes(option.id)) total += option.priceDelta;
    }
  }
  return total;
}

/** Photo-first detail sheet: large 16:9 photo, full description, stepper. */
export default function ProductDetailSheet({ product, onClose, onAdd }: ProductDetailSheetProps) {
  const [qty, setQty] = useState(1);
  const groups: ProductOptionGroup[] = product.optionGroups ?? [];
  // Estado de selección: grupo → ids elegidos. Radio (no múltiple) siempre
  // reemplaza; checkbox (múltiple) agrega/quita respetando maxSelections.
  const [selected, setSelected] = useState<number[]>([]);
  const [missingGroups, setMissingGroups] = useState<string[]>([]);
  // Grupos colapsables (referencia del dueño): todos abren expandidos; la
  // validación de obligatorios reabre los que falten (handleAdd).
  const [collapsed, setCollapsed] = useState<string[]>([]);
  // Preferencias del cliente para este ítem ("sin mayonesa"): opcional.
  const [note, setNote] = useState("");
  const sheetRef = useModalSheet<HTMLDivElement>(onClose);

  function toggleOption(group: ProductOptionGroup, optionId: number): void {
    setMissingGroups((m) => m.filter((name) => name !== group.name));
    setSelected((current) => {
      if (current.includes(optionId)) {
        return current.filter((id) => id !== optionId);
      }
      if (!group.multiple) {
        // Elegir 1: reemplaza la elección anterior del grupo.
        return [...current.filter((id) => !group.options.some((o) => o.id === id)), optionId];
      }
      if (group.maxSelections != null && current.length >= group.maxSelections) {
        return current;
      }
      return [...current, optionId];
    });
  }

  function handleAdd(): void {
    // S4: bloquear el alta al carrito mientras falte un grupo obligatorio,
    // marcando los grupos incompletos en rojo y REABRIENDO los colapsados:
    // el error de un grupo que no se ve no se puede corregir.
    const missing = groups
      .filter((g) => g.required)
      .filter((g) => !g.options.some((o) => selected.includes(o.id)))
      .map((g) => g.name);
    if (missing.length > 0) {
      setMissingGroups(missing);
      setCollapsed((c) => c.filter((name) => !missing.includes(name)));
      return;
    }
    // Orden de envío: el orden de definición de los grupos (el backend
    // valida por grupo, así el mensaje de error también es determinista).
    const ordered = groups.flatMap((g) => g.options.map((o) => o.id)).filter((id) => selected.includes(id));
    onAdd(qty, ordered, note.trim());
  }

  const unitPrice = priceWithSelections(product, selected);

  return (
    <div
      className="sheet-backdrop"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        ref={sheetRef}
        className="sheet sheet--product glass-panel"
        role="dialog"
        aria-modal="true"
        aria-label={`Detalles de ${product.name}`}
        tabIndex={-1}
      >
        {/* Encabezado fijo: título + X quedan a la vista mientras se escrollean
            los grupos (pedido del dueño). La imagen del encabezado es la
            miniatura de móvil; en desktop la imagen vive en su columna. */}
        <div className="sheet-header">
          <div className="sheet-header-title">
            <div className="sheet-thumb">
              <FoodImage
                src={product.imageUrl}
                alt={product.name}
                categoryName={product.categoryName}
              />
            </div>
            <div className="sheet-head-text">
              {product.isCombo && (
                <span className="product-badge product-badge--inline">Combo</span>
              )}
              <h2 className="sheet-name">{product.name}</h2>
              {product.description && <p className="sheet-description">{product.description}</p>}
            </div>
          </div>
          <button type="button" className="sheet-close" onClick={onClose} aria-label="Cerrar detalles">
            ×
          </button>
        </div>

        {/* La imagen es una columna propia en desktop (≥768px): alta y fija
            mientras se eligen opciones. En móvil se oculta (va en el header). */}
        <div className="sheet-media">
          <FoodImage
            src={product.imageUrl}
            alt={product.name}
            categoryName={product.categoryName}
          />
        </div>

        <div className="sheet-content">
        <div className="sheet-body">
          {groups.map((group) => {
            const groupMissing = missingGroups.includes(group.name);
            return (
              <div key={group.name} className={`sheet-option-group${groupMissing ? " sheet-option-group--missing" : ""}`}>
                <button
                  type="button"
                  className="sheet-option-group-toggle"
                  aria-expanded={!collapsed.includes(group.name)}
                  onClick={() =>
                    setCollapsed((c) =>
                      c.includes(group.name)
                        ? c.filter((name) => name !== group.name)
                        : [...c, group.name],
                    )
                  }
                >
                  <span className="sheet-option-group-head">
                    <span className="sheet-option-group--name">
                      {group.name}
                      {group.multiple && <span className="sheet-option-hint"> (hasta {group.maxSelections ?? "∞"})</span>}
                    </span>
                    {group.required && <span className="sheet-option-required">Obligatorio</span>}
                  </span>
                  <span className="sheet-option-group-chevron" aria-hidden="true">
                    {collapsed.includes(group.name) ? "▾" : "▴"}
                  </span>
                </button>
                {groupMissing && (
                  <p className="sheet-option-error" role="alert">
                    Tenés que elegir una opción
                  </p>
                )}
                {!collapsed.includes(group.name) &&
                group.options.map((option) => {
                  const checked = selected.includes(option.id);
                  return (
                    <label key={option.id} className="sheet-option">
                      <span className="sheet-option-text">
                        <span className="sheet-option-name">{option.name}</span>
                        {option.priceDelta > 0 && (
                          <span className="sheet-option-delta">+{formatARS(option.priceDelta)}</span>
                        )}
                      </span>
                      <input
                        type={group.multiple ? "checkbox" : "radio"}
                        name={`group-${product.id}-${group.name}`}
                        checked={checked}
                        onChange={() => toggleOption(group, option.id)}
                      />
                    </label>
                  );
                })}
              </div>
            );
          })}

          {/* Preferencias del cliente ("sin mayonesa"): texto libre por ítem,
              opcional, con el mismo límite que valida el backend (300). Vive
              DENTRO de la columna de contenido, junto a los grupos de
              opciones — no debajo de la imagen (bug de diseño 2026-10-22). */}
          <div className="sheet-notes">
            <label className="sheet-notes-label" htmlFor={`sheet-notes-${product.id}`}>
              Preferencias <span className="sheet-notes-hint">(opcional)</span>
            </label>
            <textarea
              id={`sheet-notes-${product.id}`}
              className="sheet-notes-input"
              maxLength={300}
              rows={2}
              placeholder="Sin mayonesa, sin lechuga, bien cocida…"
              value={note}
              onChange={(e) => setNote(e.target.value.slice(0, 300))}
            />
            <span className="sheet-notes-count" aria-live="polite">{note.length}/300</span>
          </div>
        </div>
        </div>

        {/* Barra fija de acciones: hija directa de la hoja y sticky bottom,
            abarcando ambas columnas — llega al ras del fondo porque su
            contenedor es la hoja entera, no la columna de contenido. */}
        <div className="sheet-actions">
          <p className="sheet-price">{formatARS(unitPrice)}</p>

          <div className="qty-stepper">
              <button
                type="button"
                className="qty-btn qty-btn--minus"
                aria-label={`Quitar ${product.name}`}
                disabled={qty <= 1}
                onClick={() => setQty((q) => Math.max(1, q - 1))}
              >
                −
              </button>
              <span
                className="qty-value"
                aria-live="polite"
                aria-label={`Cantidad de ${product.name}`}
              >
                {qty}
              </span>
              <button
                type="button"
                className="qty-btn qty-btn--plus"
                aria-label={`Agregar ${product.name}`}
                onClick={() => setQty((q) => q + 1)}
              >
                +
              </button>
            </div>
            <button
              type="button"
              className="cart-submit sheet-add"
              onClick={handleAdd}
            >
              Agregar al pedido
            </button>
        </div>
      </div>
    </div>
  );
}
