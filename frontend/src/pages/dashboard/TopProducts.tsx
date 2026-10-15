import type { TopProduct } from "./api";

interface TopProductsProps {
  products: TopProduct[];
}

/** "Top productos" del legacy dashboard/index.html + loadTopProducts(). */
export default function TopProducts({ products }: TopProductsProps) {
  return (
    <section className="top-products panel glass-panel fade-up">
      <h2>Top productos</h2>

      <div className="table-scroll">
        <ul>
          {products.map((p) => (
            <li key={`${p.productName}-${p.quantity}`}>
              {p.productName} ({p.quantity})
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}
