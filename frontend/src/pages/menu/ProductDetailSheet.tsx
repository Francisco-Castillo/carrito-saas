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
   */
  onAdd: (qty: number, selectedOptionIds: number[]) => void;
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
    // marcando los grupos incompletos en rojo.
    const missing = groups
      .filter((g) => g.required)
      .filter((g) => !g.options.some((o) => selected.includes(o.id)))
      .map((g) => g.name);
    if (missing.length > 0) {
      setMissingGroups(missing);
      return;
    }
    // Orden de envío: el orden de definición de los grupos (el backend
    // valida por grupo, así el mensaje de error también es determinista).
    const ordered = groups.flatMap((g) => g.options.map((o) => o.id)).filter((id) => selected.includes(id));
    onAdd(qty, ordered);
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
        className="sheet glass-panel"
        role="dialog"
        aria-modal="true"
        aria-label={`Detalles de ${product.name}`}
        tabIndex={-1}
      >
        <button type="button" className="sheet-close" onClick={onClose} aria-label="Cerrar detalles">
          ×
        </button>

        <div className="sheet-media">
          <FoodImage
            src={product.imageUrl}
            alt={product.name}
            categoryName={product.categoryName}
          />
        </div>

        <div className="sheet-body">
          {product.isCombo && (
            <span className="product-badge product-badge--inline">Combo</span>
          )}
          <h2 className="sheet-name">{product.name}</h2>
          {product.description && <p className="sheet-description">{product.description}</p>}

          {groups.map((group) => {
            const groupMissing = missingGroups.includes(group.name);
            return (
              <div key={group.name} className={`sheet-option-group${groupMissing ? " sheet-option-group--missing" : ""}`}>
                <p className="sheet-option-group--name">
                  {group.name}
                  {group.required && <span className="sheet-option-required"> *</span>}
                  {group.multiple && <span className="sheet-option-hint"> (hasta {group.maxSelections ?? "∞"})</span>}
                </p>
                {groupMissing && (
                  <p className="sheet-option-error" role="alert">
                    Tenés que elegir una opción
                  </p>
                )}
                {group.options.map((option) => {
                  const checked = selected.includes(option.id);
                  return (
                    <label key={option.id} className="sheet-option">
                      <input
                        type={group.multiple ? "checkbox" : "radio"}
                        name={`group-${product.id}-${group.name}`}
                        checked={checked}
                        onChange={() => toggleOption(group, option.id)}
                      />
                      <span className="sheet-option-name">{option.name}</span>
                      {option.priceDelta > 0 && (
                        <span className="sheet-option-delta">+{formatARS(option.priceDelta)}</span>
                      )}
                    </label>
                  );
                })}
              </div>
            );
          })}

          <p className="sheet-price">{formatARS(unitPrice)}</p>

          <div className="sheet-actions">
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
    </div>
  );
}
