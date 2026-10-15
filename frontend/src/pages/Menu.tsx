import { useEffect, useMemo, useState } from "react";
import "./Menu.css";
import CartPanel from "./menu/CartPanel";
import ProductCard from "./menu/ProductCard";
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

const COMBO_FALLBACK_IMAGE =
  "https://images.unsplash.com/photo-1600891964599-f61ba0e24092";

const ERROR_NO_SLUG =
  "No pudimos abrir la carta: el enlace del QR está incompleto o no es válido. " +
  "Pedile al local un nuevo QR o hacé tu pedido directamente en el mostrador.";

const ERROR_RESTAURANT =
  "No pudimos cargar la carta de este local. Revisá tu conexión y volvé a escanear el QR; " +
  "si el problema continúa, avisale al local para que te dé un enlace nuevo.";

const ERROR_PRODUCTS =
  "No pudimos cargar los productos de la carta. Revisá tu conexión y probá de nuevo en unos minutos; " +
  "si sigue fallando, avisale al local o hacé tu pedido directamente en el mostrador.";

/** Convierte combos en productos visuales (paridad con legacy app.js). */
function mapCombosToProducts(combos: Combo[]): Product[] {
  if (!combos || combos.length === 0) return [];

  return combos.map((c) => ({
    id: `combo-${c.id}`,
    name: c.name,
    description: (c.items || [])
      .map((i) => `${i.quantity} x ${i.productName}`)
      .join(", "),
    price: c.price,
    categoryId: String(c.categoryId),
    categoryName: "Combos",
    imageUrl: COMBO_FALLBACK_IMAGE,
    isCombo: true,
  }));
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

  // Paridad con legacy: `cart` se resetea en cada carga de la página
  // (resetApp en init) y se persiste en localStorage con la clave "cart".
  const [cart, setCart] = useState<Cart>({});

  const [customerName, setCustomerName] = useState("");
  const [orderType, setOrderType] = useState<OrderType>("RETIRO");
  const [address, setAddress] = useState("");
  const [notes, setNotes] = useState("");
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>("EFECTIVO");

  const [sending, setSending] = useState(false);
  const [cartExpanded, setCartExpanded] = useState(false);

  useEffect(() => {
    document.title = "Carta Digital";
  }, []);

  useEffect(() => {
    if (!slug) {
      // Enlace QR truncado: mensaje legible y fin, sin pedidos rotos.
      setLoadError(ERROR_NO_SLUG);
      setCart({});
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

        setProducts([...baseProducts, ...mapCombosToProducts(data.combos || [])]);
      } catch {
        if (!cancelled) setLoadError(ERROR_PRODUCTS);
      }
    }

    init();
    return () => {
      cancelled = true;
    };
  }, [slug]);

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

  function addItem(product: Product) {
    const id = product.id;
    const next: Cart = { ...cart };
    if (!next[id]) {
      next[id] = { product, qty: 0, isCombo: product.isCombo || false };
    }
    next[id] = { ...next[id], qty: next[id].qty + 1 };
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
    setCartExpanded(false);
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
  const [openCategories, setOpenCategories] = useState<Record<string, boolean>>({});

  function toggleCategory(key: string) {
    setOpenCategories((prev) => ({ ...prev, [key]: !prev[key] }));
  }

  const cartVisible = Object.keys(cart).length > 0;
  const total = Object.values(cart).reduce(
    (sum, item) => sum + item.qty * item.product.price,
    0
  );

  return (
    <div className="menu-screen" data-cart-visible={cartVisible || undefined}>
      <div className="aurora" aria-hidden="true">
        <div className="aurora-blob aurora-blob--indigo" />
        <div className="aurora-blob aurora-blob--violet" />
        <div className="aurora-blob aurora-blob--fuchsia" />
        <div className="aurora-blob aurora-blob--cyan" />
      </div>

      {restaurant && (
        <header className="restaurant-header glass-panel fade-up">
          {restaurant.logoUrl && (
            <img className="restaurant-logo" src={restaurant.logoUrl} alt="" />
          )}
          <h1 className="restaurant-name">{restaurant.name}</h1>
        </header>
      )}

      {loadError ? (
        <div className="menu-load-error glass-panel fade-up" role="alert">
          {loadError}
        </div>
      ) : (
        <main className="menu-layout">
          <section className="menu-main">
            <h2 className="menu-title">Menú</h2>

            <div className="menu-categories stagger">
              {categories.map((cat, index) => {
                const key = String(index);
                const open = Boolean(openCategories[key]);
                return (
                  <section
                    key={key}
                    className={`category glass-panel${open ? " open" : ""}`}
                  >
                    <button
                      type="button"
                      className="category-header"
                      onClick={() => toggleCategory(key)}
                      aria-expanded={open}
                    >
                      <span>{cat.name}</span>
                      <span className="category-chevron" aria-hidden="true">
                        ▼
                      </span>
                    </button>

                    {open && (
                      <div className="category-content">
                        <div className="menu-grid stagger">
                          {cat.products.map((p) => (
                            <ProductCard
                              key={p.id}
                              product={p}
                              qty={cart[p.id]?.qty || 0}
                              onAdd={() => addItem(p)}
                              onRemove={() => removeItem(p.id)}
                            />
                          ))}
                        </div>
                      </div>
                    )}
                  </section>
                );
              })}
            </div>
          </section>

          {cartVisible && (
            <div className="menu-cart-wrap fade-up">
              <CartPanel
                cart={cart}
                total={total}
                customerName={customerName}
                orderType={orderType}
                address={address}
                notes={notes}
                paymentMethod={paymentMethod}
                sending={sending}
                expanded={cartExpanded}
                onCustomerNameChange={setCustomerName}
                onOrderTypeChange={setOrderType}
                onAddressChange={setAddress}
                onNotesChange={setNotes}
                onPaymentMethodChange={setPaymentMethod}
                onToggleExpanded={() => setCartExpanded((v) => !v)}
                onSubmit={handleSubmit}
              />
            </div>
          )}
        </main>
      )}
    </div>
  );
}
