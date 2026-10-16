import { formatARS } from "../../format";
import FoodImage from "./FoodImage";
import type { Product } from "./types";

interface ProductCardProps {
  product: Product;
  qty: number;
  onAdd: () => void;
  onRemove: () => void;
  /** Opens the detail sheet (card body tap; add controls stay separate). */
  onOpen: () => void;
}

export default function ProductCard({ product, qty, onAdd, onRemove, onOpen }: ProductCardProps) {
  return (
    <article className="product-card glass-panel">
      <button type="button" className="product-open" onClick={onOpen}>
        <div className="product-media">
          {product.isCombo && <span className="product-badge">Combo</span>}
          <FoodImage
            src={product.imageUrl}
            alt={product.name}
            categoryName={product.categoryName}
          />
        </div>
        <h3 className="product-name">{product.name}</h3>
        {product.description && (
          <p className="product-description">{product.description}</p>
        )}
      </button>

      <div className="product-footer">
        <span className="product-price">{formatARS(product.price)}</span>
        {qty === 0 ? (
          <button
            type="button"
            className="qty-btn qty-btn--plus"
            onClick={onAdd}
            aria-label={`Agregar ${product.name}`}
          >
            +
          </button>
        ) : (
          <div className="qty-stepper">
            <button
              type="button"
              className="qty-btn qty-btn--minus"
              onClick={onRemove}
              aria-label={`Quitar ${product.name}`}
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
              onClick={onAdd}
              aria-label={`Agregar ${product.name}`}
            >
              +
            </button>
          </div>
        )}
      </div>
    </article>
  );
}
