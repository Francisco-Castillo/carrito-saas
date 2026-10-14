import { useEffect, useMemo, useRef, useState } from "react";
import "./Menu.css";
import CartPanel from "./menu/CartPanel";
import ProductCard from "./menu/ProductCard";
import ProductDetailSheet from "./menu/ProductDetailSheet";
import Sheet from "./menu/Sheet";
import { useMediaQuery } from "./menu/useMediaQuery";
import type {
  Cart,
  CategoryGroup,
  Combo,
  MenuResponse,
  OrderType,
  PaymentMethod,
  Product,
  Restaurant,
} from "./menu/types";

const ERROR_NO_SLUG =
  "No pudimos abrir la carta: el enlace del QR está incompleto o no es válido. " +
  "Pedile al local un nuevo QR o hacé tu pedido directamente en el mostrador.";

const ERROR_RESTAURANT =
  "No pudimos cargar la carta de este local. Revisá tu conexión y volvé a escanear el QR; " +
  "si el problema continúa, avisale al local para que te dé un enlace nuevo.";

const ERROR_PRODUCTS =
  "No pudimos cargar los productos de la carta. Revisá tu conexión y probá de nuevo en unos minutos; " +
  "si sigue fallando, avisale al local o hacé tu pedido directamente en el mostrador.";

function normalizeName(value: string): string {
  return value
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .toLowerCase()
    .trim();
}

/**
 * Heurística de foto para combos: el payload de la API solo trae los
 * componentes como `productName` (sin id ni imagen propia), así que reusamos
 * la foto del PRIMER componente buscándolo por nombre contra la lista de
 * productos reales (comparación insensible a mayúsculas, espacios y acentos).
 * Sin match → `imageUrl` queda undefined y la tarjeta muestra el placeholder
 * intencional en lugar de una foto rota o externa.
 */
function mapCombosToProducts(combos: Combo[], baseProducts: Product[]): Product[] {
  if (!combos || combos.length === 0) return [];

  const productsByName = new Map<string, Product>();
  baseProducts.forEach((p) => {
    if (!productsByName.has(normalizeName(p.name))) productsByName.set(normalizeName(p.name), p);
  });

  return combos.map((c) => {
    // La foto del combo es la del componente MÁS CARO con imagen disponible, no
    // la del primero: el payload lista los componentes en el orden en que se
    // cargaron, así que tomar el primero mostraba una gaseosa suelta en el
    // "Combo Clasica + Bebida" en vez del plato principal.
    const resolved = (c.items || [])
      .map((i) => productsByName.get(normalizeName(i.productName)))
      .filter((p): p is Product => Boolean(p?.imageUrl));
    const main = [...resolved].sort((a, b) => b.price - a.price)[0];

    return {
      id: `combo-${c.id}`,
      name: c.name,
      description: (c.items || [])
        .map((i) => `${i.quantity} x ${i.productName}`)
        .join(", "),
      price: c.price,
      categoryId: String(c.categoryId),
      categoryName: "Combos",
      imageUrl: main?.imageUrl,
      isCombo: true,
    };
  });
}

/** Agrupación por categoryId en orden de aparición (paridad con legacy). */
function groupByCategory(products: Product[]): CategoryGroup[] {
  const categories = new Map<string, CategoryGroup>();

  products.forEach((p) => {
    const key = String(p.categoryId);
    if (!categories.has(key)) {
      categories.set(key, { name: p.categoryName, products: [] });
    }
    categories.get(key)!.products.push(p);
  });

  return [...categories.values()];
}

export default function Menu() {
  const slug = useMemo(
    () => new URLSearchParams(window.location.search).get("restaurant"),
    []
  );

  const [restaurant, setRestaurant] = useState<Restaurant | null>(null);
  const [products, setProducts] = useState<Product[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  // Paridad con legacy: `cart` se resetea en cada carga de la página
  // (resetApp en init) y se persiste en localStorage con la clave "cart".
  const [cart, setCart] = useState<Cart>({});

  const [customerName, setCustomerName] = useState("");
  const [orderType, setOrderType] = useState<OrderType>("RETIRO");
  const [address, setAddress] = useState("");
  const [notes, setNotes] = useState("");
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>("EFECTIVO");

  const [sending, setSending] = useState(false);
  const [cartSheetOpen, setCartSheetOpen] = useState(false);
  const [detailProduct, setDetailProduct] = useState<Product | null>(null);

  // Navegación de categorías: píldora activa por scroll-spy.
  const [activeCategory, setActiveCategory] = useState(0);
  const sectionEls = useRef<HTMLElement[]>([]);
  const observerRef = useRef<IntersectionObserver | null>(null);

  // Presentación del carrito: sidebar sticky en desktop, barra + hoja en móvil.
  const isDesktop = useMediaQuery("(min-width: 1024px)");

  useEffect(() => {
    document.title = "Carta Digital";
  }, []);

  useEffect(() => {
    if (!slug) {
      // Enlace QR truncado: mensaje legible y fin, sin pedidos rotos.
      setLoadError(ERROR_NO_SLUG);
      setCart({});
      setLoading(false);
      return;
    }

    let cancelled = false;

    async function init() {
      // resetApp(): estado limpio antes de cargar
      setCart({});
      setCustomerName("");
      setAddress("");
      setNotes("");
      setOrderType("RETIRO");
      setProducts([]);
      setRestaurant(null);
      setLoadError(null);
      setLoading(true);

      try {
        const res = await fetch(`/api/restaurants/slug/${slug}`);
        if (!res.ok) {
          throw new Error("Restaurant request failed with HTTP " + res.status);
        }
        const data: Restaurant = await res.json();
        if (cancelled) return;
        setRestaurant(data);

        if (data.primaryColor) {
          document.documentElement.style.setProperty(
            "--primary-color",
            data.primaryColor
          );
        }
      } catch {
        if (!cancelled) setLoadError(ERROR_RESTAURANT);
        return;
      }

      try {
        const res = await fetch(`/api/menu/${slug}`);
        if (!res.ok) {
          throw new Error("Menu request failed with HTTP " + res.status);
        }
        const data: MenuResponse = await res.json();
        if (cancelled) return;

        // 🔥 SAFE ASSIGN (evita errores)
        const baseProducts: Product[] = (data.products || []).map((p) => ({
          ...p,
          id: String(p.id),
          categoryId: String(p.categoryId),
          categoryName: p.categoryName ?? "",
          isCombo: false,
        }));

        setProducts([
          ...baseProducts,
          ...mapCombosToProducts(data.combos || [], baseProducts),
        ]);
      } catch {
        if (!cancelled) setLoadError(ERROR_PRODUCTS);
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    init();
    return () => {
      cancelled = true;
    };
  }, [slug]);

  // Scroll-spy: la píldora activa sigue la sección visible. En entornos sin
  // IntersectionObserver (jsdom, navegadores viejos) simplemente no se activa.
  //
  // El observer se crea UNA vez y cada sección se observa desde su propio ref
  // callback. Antes se creaba dentro de un efecto y leía los refs en ese
  // instante: si el efecto corría en un commit donde las secciones todavía no
  // habían montado, el observer quedaba observando nada y no se recreaba
  // (sus dependencias no volvían a cambiar), así que el scroll-spy moría sin
  // aviso y el fallo era intermitente.
  useEffect(() => {
    if (typeof IntersectionObserver === "undefined") return;

    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (!entry.isIntersecting) return;
          const index = sectionEls.current.indexOf(entry.target as HTMLElement);
          if (index >= 0) setActiveCategory(index);
        });
      },
      // Franja central del viewport: la sección que la cruza es "la actual".
      { rootMargin: "-40% 0px -55% 0px", threshold: 0 }
    );

    observerRef.current = observer;
    // Secciones ya montadas antes de crear el observer (montaje posterior a la
    // carga de datos): se observan acá y las nuevas lo hacen en su ref.
    sectionEls.current.forEach((el) => el && observer.observe(el));

    return () => {
      observer.disconnect();
      observerRef.current = null;
    };
  }, []);

  const whatsapp = useMemo(() => {
    const value = restaurant?.whatsappNumber;
    return typeof value === "string" ? value.trim() : "";
  }, [restaurant]);

  function hasWhatsappNumber(): boolean {
    return whatsapp !== "";
  }

  function saveCart(next: Cart) {
    setCart(next);
    localStorage.setItem("cart", JSON.stringify(next));
  }

  function addItem(product: Product, qty = 1) {
    const id = product.id;
    const next: Cart = { ...cart };
    if (!next[id]) {
      next[id] = { product, qty: 0, isCombo: product.isCombo || false };
    }
    next[id] = { ...next[id], qty: next[id].qty + qty };
    saveCart(next);
  }

  function removeItem(id: string) {
    const entry = cart[id];
    if (!entry || entry.qty <= 0) return;

    const next: Cart = { ...cart };
    if (entry.qty - 1 === 0) {
      delete next[id];
    } else {
      next[id] = { ...entry, qty: entry.qty - 1 };
    }
    saveCart(next);
  }

  /** Estado limpio tras un pedido confirmado (paridad con legacy). */
  function resetOrderState() {
    setCart({});
    localStorage.removeItem("cart");
    setCustomerName("");
    setAddress("");
    setNotes("");
    setOrderType("RETIRO");
    setCartSheetOpen(false);
  }

  function sendOrderByWhatsApp() {
    if (!hasWhatsappNumber()) {
      alert(
        "No pudimos enviar tu pedido al local y este local no tiene WhatsApp configurado. " +
        "Por favor confirmá tu pedido directamente con el local."
      );
      return;
    }

    let message = "Hola! Quiero hacer el siguiente pedido:%0A%0A";

    Object.values(cart).forEach((item) => {
      message += `• ${item.qty} x ${item.product.name}%0A`;
    });

    let total = 0;
    Object.values(cart).forEach((item) => {
      total += item.qty * item.product.price;
    });

    message += `%0A*Total:* $${total}%0A`;
    message += "----------------%0A";
    message += `*Nombre:* ${customerName}%0A`;
    message += `*Tipo de pedido:* ${orderType}%0A`;

    if (orderType === "DELIVERY") {
      message += `*Dirección:* ${address}%0A`;
    }

    message += `*Forma de pago:* ${paymentMethod}%0A`;

    if (notes) {
      message += `*Observaciones:* ${notes}%0A`;
    }

    // Navegación en vez de window.open(): nunca bloqueada por el navegador.
    window.location.href = `https://wa.me/${whatsapp}?text=${message}`;
  }

  async function handleSubmit() {
    if (Object.keys(cart).length === 0) {
      alert("Agrega productos primero");
      return;
    }

    if (customerName.trim() === "") {
      alert("Por favor ingresa tu nombre");
      return;
    }

    if (orderType === "DELIVERY" && address.trim() === "") {
      alert("Por favor ingresa la dirección para el delivery");
      return;
    }

    // El pedido entra primero a la cocina
    const items = Object.entries(cart).map(([id, item]) => {
      if (item.isCombo) {
        return { comboId: Number(id.replace("combo-", "")), quantity: item.qty };
      }
      return { productId: Number(id), quantity: item.qty };
    });

    const payload: Record<string, unknown> = {
      customerName,
      orderType,
      paymentMethod,
      notes,
      items,
    };

    if (orderType === "DELIVERY") {
      payload.customerAddress = address;
    }

    // Evita doble envío: un doble tap no debe crear dos pedidos
    setSending(true);

    try {
      const response = await fetch(`/api/orders/menu/${slug}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });

      if (!response.ok) {
        throw new Error("El local rechazó el pedido (HTTP " + response.status + ")");
      }

      alert("¡Pedido recibido! Ya lo enviamos a la cocina.");
      resetOrderState();
      return;
    } catch {
      // Fallback: el pedido no se pierde, pero nunca prometemos un canal que no existe.
      if (hasWhatsappNumber()) {
        alert("No pudimos enviar tu pedido al local. Vamos a confirmarlo por WhatsApp.");
        sendOrderByWhatsApp();
      } else {
        alert(
          "No pudimos enviar tu pedido al local. " +
          "Este local no tiene un número de WhatsApp configurado para confirmarlo automáticamente. " +
          "Por favor confirmá tu pedido directamente con el local " +
          "(en el mostrador o por cualquier otro medio de contacto que tenga publicado)."
        );
      }
    } finally {
      setSending(false);
    }
  }

  const categories = useMemo(() => groupByCategory(products), [products]);

  function scrollToCategory(index: number) {
    setActiveCategory(index);
    const el = sectionEls.current[index];
    if (!el || typeof el.scrollIntoView !== "function") return;
    const reduceMotion =
      typeof window.matchMedia === "function" &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    el.scrollIntoView({ behavior: reduceMotion ? "auto" : "smooth", block: "start" });
  }

  const cartVisible = Object.keys(cart).length > 0;
  const total = Object.values(cart).reduce(
    (sum, item) => sum + item.qty * item.product.price,
    0
  );
  const totalUnits = Object.values(cart).reduce((sum, item) => sum + item.qty, 0);

  return (
    <div className="menu-screen" data-cart-visible={cartVisible || undefined}>
      <div className="aurora" aria-hidden="true">
        <div className="aurora-blob aurora-blob--indigo" />
        <div className="aurora-blob aurora-blob--violet" />
        <div className="aurora-blob aurora-blob--fuchsia" />
        <div className="aurora-blob aurora-blob--cyan" />
      </div>

      {restaurant && (
        <header className="menu-topbar glass-panel">
          <p className="menu-eyebrow">Carta digital</p>
          <h1 className="restaurant-name">{restaurant.name}</h1>
        </header>
      )}

      {loadError ? (
        <div className="menu-load-error glass-panel fade-up" role="alert">
          {loadError}
        </div>
      ) : (
        <>
          {!loading && categories.length > 0 && (
            <nav className="menu-nav" aria-label="Categorías">
              <div className="menu-nav-track">
                {categories.map((cat, index) => (
                  <button
                    key={index}
                    type="button"
                    className={`nav-pill${index === activeCategory ? " active" : ""}`}
                    aria-current={index === activeCategory ? "true" : undefined}
                    onClick={() => scrollToCategory(index)}
                  >
                    {cat.name}
                  </button>
                ))}
              </div>
            </nav>
          )}

          <main className="menu-layout">
            <section className="menu-main" aria-busy={loading || undefined}>
              {loading ? (
                <div className="menu-skeleton" role="status">
                  <span className="visually-hidden">Cargando la carta…</span>
                  {Array.from({ length: 6 }).map((_, i) => (
                    <div key={i} className="skeleton-card glass-panel" aria-hidden="true">
                      <div className="skeleton-media shimmer" />
                      <div className="skeleton-line shimmer" />
                      <div className="skeleton-line skeleton-line--short shimmer" />
                    </div>
                  ))}
                </div>
              ) : (
                <div className="menu-sections stagger">
                  {categories.map((cat, index) => (
                    <section
                      key={index}
                      id={`categoria-${index}`}
                      ref={(el) => {
                        const previous = sectionEls.current[index];
                        if (el) {
                          sectionEls.current[index] = el;
                          if (previous !== el) observerRef.current?.observe(el);
                        } else {
                          if (previous) observerRef.current?.unobserve(previous);
                          delete sectionEls.current[index];
                        }
                      }}
                      className="menu-section"
                      aria-labelledby={`categoria-heading-${index}`}
                    >
                      <div className="section-heading">
                        <h2 id={`categoria-heading-${index}`}>{cat.name}</h2>
                        <span className="section-count">
                          {cat.products.length} {cat.products.length === 1 ? "ítem" : "ítems"}
                        </span>
                      </div>
                      <div className="menu-grid">
                        {cat.products.map((p) => (
                          <ProductCard
                            key={p.id}
                            product={p}
                            qty={cart[p.id]?.qty || 0}
                            onAdd={() => addItem(p)}
                            onRemove={() => removeItem(p.id)}
                            onOpen={() => setDetailProduct(p)}
                          />
                        ))}
                      </div>
                    </section>
                  ))}
                </div>
              )}
            </section>

            {cartVisible && isDesktop && (
              <aside
                className="cart cart--sidebar glass-panel fade-up"
                role="complementary"
                aria-label="Tu pedido"
              >
                <CartPanel
                  cart={cart}
                  total={total}
                  customerName={customerName}
                  orderType={orderType}
                  address={address}
                  notes={notes}
                  paymentMethod={paymentMethod}
                  sending={sending}
                  onCustomerNameChange={setCustomerName}
                  onOrderTypeChange={setOrderType}
                  onAddressChange={setAddress}
                  onNotesChange={setNotes}
                  onPaymentMethodChange={setPaymentMethod}
                  onAddItem={(id) => {
                    const entry = cart[id];
                    if (entry) addItem(entry.product);
                  }}
                  onRemoveItem={removeItem}
                  onSubmit={handleSubmit}
                />
              </aside>
            )}
          </main>

          {cartVisible && !isDesktop && (
            <div className="cart-bar glass-panel">
              <div className="cart-bar-info">
                <span className="cart-bar-count">
                  {totalUnits} {totalUnits === 1 ? "ítem" : "ítems"}
                </span>
                <span className="cart-bar-total">${total}</span>
              </div>
              <button
                type="button"
                className="cart-bar-open"
                onClick={() => setCartSheetOpen(true)}
              >
                Ver pedido
              </button>
            </div>
          )}

          {cartVisible && !isDesktop && cartSheetOpen && (
            <Sheet
              label="Tu pedido"
              closeLabel="Cerrar el pedido"
              onClose={() => setCartSheetOpen(false)}
              className="cart-sheet"
            >
              <aside
                className="cart cart--sheet"
                role="complementary"
                aria-label="Tu pedido"
              >
                <CartPanel
                  cart={cart}
                  total={total}
                  customerName={customerName}
                  orderType={orderType}
                  address={address}
                  notes={notes}
                  paymentMethod={paymentMethod}
                  sending={sending}
                  onCustomerNameChange={setCustomerName}
                  onOrderTypeChange={setOrderType}
                  onAddressChange={setAddress}
                  onNotesChange={setNotes}
                  onPaymentMethodChange={setPaymentMethod}
                  onAddItem={(id) => {
                    const entry = cart[id];
                    if (entry) addItem(entry.product);
                  }}
                  onRemoveItem={removeItem}
                  onSubmit={handleSubmit}
                />
              </aside>
            </Sheet>
          )}
        </>
      )}

      {detailProduct && (
        <ProductDetailSheet
          product={detailProduct}
          onClose={() => setDetailProduct(null)}
          onAdd={(qty) => {
            addItem(detailProduct, qty);
            setDetailProduct(null);
          }}
        />
      )}
    </div>
  );
}
