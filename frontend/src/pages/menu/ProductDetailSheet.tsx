import { useState } from "react";
import { formatARS } from "../../format";
import FoodImage from "./FoodImage";
import type { Product } from "./types";
import { useModalSheet } from "./useModalSheet";

interface ProductDetailSheetProps {
  product: Product;
  onClose: () => void;
  /** Adds `qty` units to the cart; the parent closes the sheet afterwards. */
  onAdd: (qty: number) => void;
}

/** Photo-first detail sheet: large 16:9 photo, full description, stepper. */
export default function ProductDetailSheet({ product, onClose, onAdd }: ProductDetailSheetProps) {
  const [qty, setQty] = useState(1);
  const sheetRef = useModalSheet<HTMLDivElement>(onClose);

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
          <p className="sheet-price">{formatARS(product.price)}</p>

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
              onClick={() => onAdd(qty)}
            >
              Agregar al pedido
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
