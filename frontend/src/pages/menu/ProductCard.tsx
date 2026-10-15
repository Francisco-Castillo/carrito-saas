import type { Product } from "./types";

interface ProductCardProps {
  product: Product;
  qty: number;
  onAdd: () => void;
  onRemove: () => void;
}

export default function ProductCard({ product, qty, onAdd, onRemove }: ProductCardProps) {
  return (
    <article className="product-card glass-panel">
      <div className="product-media">
        {product.imageUrl ? (
          <img src={product.imageUrl} alt={product.name} loading="lazy" />
        ) : (
          <span className="product-emoji" aria-hidden="true">
            🍽️
          </span>
        )}
        {product.isCombo && <span className="product-badge">Combo</span>}
      </div>

      <div className="product-info">
        <h3 className="product-name">{product.name}</h3>
        {product.description && <p className="product-description">{product.description}</p>}
        <p className="product-price">${product.price}</p>
      </div>

      <div className="product-controls">
        <button
          type="button"
          className="qty-btn qty-btn--minus"
          onClick={onRemove}
          aria-label={`Quitar ${product.name}`}
          disabled={qty === 0}
        >
          −
        </button>
        <span className="product-qty" aria-live="polite" aria-label={`Cantidad de ${product.name}`}>
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
    </article>
  );
}
