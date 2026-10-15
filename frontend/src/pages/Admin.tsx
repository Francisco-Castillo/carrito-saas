import { useLayoutEffect, useState } from "react";
import "./Admin.css";
import { enforceSession, hasUsableSession } from "./dashboard/auth";
import DashboardSection from "./admin/DashboardSection";
import ProductsTable from "./admin/ProductsTable";
import CategoriesTable from "./admin/CategoriesTable";
import CombosTable from "./admin/CombosTable";
import LocalSection from "./admin/LocalSection";
import CreateCategoryModal from "./admin/CreateCategoryModal";
import ToastHost from "./admin/ToastHost";

type AdminView = "dashboard" | "products" | "categories" | "combos" | "local";

const NAV_ITEMS: { view: AdminView; label: string }[] = [
  { view: "dashboard", label: "📊 Dashboard" },
  { view: "products", label: "🍔 Productos" },
  { view: "categories", label: "📂 Categorías" },
  { view: "combos", label: "🔥 Combos" },
  { view: "local", label: "🏪 Local" },
];

/**
 * Puerto del panel de admin legacy (admin/admin.html + admin.js) al stack de
 * React, conservando el copy español, los endpoints, los headers, los flujos
 * de confirmación y las claves de localStorage.
 *
 * La vista "dashboard" embebe la misma sección de métricas del dashboard
 * legacy (admin.js llama initDashboard()): acá reutilizamos los componentes
 * y loaders de src/pages/dashboard.
 */
export default function Admin() {
  // Guard del legacy (mismas reglas que el dashboard): token recortado,
  // ""/"null"/"undefined" → redirect a /login/login.html?restaurant={slug}.
  const [sessionUsable] = useState(hasUsableSession);

  useLayoutEffect(() => {
    if (!sessionUsable) {
      enforceSession();
    }
  }, [sessionUsable]);

  const [view, setView] = useState<AdminView>("dashboard");
  const [createOpen, setCreateOpen] = useState(false);
  const [categoryRowCount, setCategoryRowCount] = useState<number | null>(null);
  const [categoriesRefreshKey, setCategoriesRefreshKey] = useState(0);

  function goTo(next: AdminView) {
    setView(next);
    // El legacy leía el tbody al momento de crear; si no hay tabla, order = 1.
    if (next !== "categories") {
      setCategoryRowCount(null);
    }
  }

  // Logout del legacy admin.js, adaptado: borra SOLO las claves de sesión que
  // escribe el login (a diferencia del localStorage.clear() COMPLETO del
  // legacy, que hoy borraría también savedUser del remember-me) + redirect
  // conservando el restaurante.
  function logout() {
    for (const key of ["token", "businessId", "role", "username", "expiresAt"]) {
      localStorage.removeItem(key);
    }

    const params = new URLSearchParams(window.location.search);
    const restaurant = params.get("restaurant");

    let loginUrl = "/login/login.html";
    if (restaurant) {
      loginUrl += `?restaurant=${restaurant}`;
    }

    window.location.href = loginUrl;
  }

  // El legacy determinaba el orden al final leyendo el tbody: filas + 1
  // (o 1 si no hay tabla). admin.js:517-519.
  const lastOrder = categoryRowCount === null ? 1 : categoryRowCount + 1;

  if (!sessionUsable) {
    // Redirect en curso: no renderizamos la página sin sesión.
    return null;
  }

  return (
    <div className="admin-screen">
      <div className="aurora" aria-hidden="true">
        <div className="aurora-blob aurora-blob--indigo" />
        <div className="aurora-blob aurora-blob--violet" />
        <div className="aurora-blob aurora-blob--fuchsia" />
        <div className="aurora-blob aurora-blob--cyan" />
      </div>

      {/* SIDEBAR (barra superior de vidrio en mobile) */}
      <aside className="admin-sidebar">
        <h2 className="admin-logo">SDE</h2>

        <nav aria-label="Secciones de administración">
          {NAV_ITEMS.map((item) => (
            <button
              key={item.view}
              type="button"
              data-view={item.view}
              className={`admin-nav-btn${view === item.view ? " is-active" : ""}`}
              onClick={() => goTo(item.view)}
            >
              {item.label}
            </button>
          ))}
        </nav>

        <button type="button" className="admin-logout" onClick={logout}>
          🚪 Logout
        </button>
      </aside>

      {/* MAIN */}
      <div className="admin-main">
        <header className="admin-topbar">
          <h1 id="view-title">{view.toUpperCase()}</h1>
          {/* "+ Nuevo" pertenece a la vista de categorías: abre el modal de
              crear categoría (admin.js:545-548). Solo se muestra ahí. */}
          {view === "categories" && (
            <button type="button" className="btn-primary" onClick={() => setCreateOpen(true)}>
              + Nuevo
            </button>
          )}
        </header>

        <section id="content" className="admin-content stagger">
          {view === "dashboard" && <DashboardSection />}
          {view === "products" && <ProductsTable />}
          {view === "categories" && (
            <CategoriesTable
              refreshKey={categoriesRefreshKey}
              onRowCountChange={setCategoryRowCount}
            />
          )}
          {view === "combos" && <CombosTable />}
          {view === "local" && <LocalSection />}
        </section>
      </div>

      {createOpen && (
        <CreateCategoryModal
          lastOrder={lastOrder}
          onClose={() => setCreateOpen(false)}
          onCreated={() => {
            setCreateOpen(false);
            // loadCategories() del legacy tras crear con éxito.
            setCategoriesRefreshKey((k) => k + 1);
          }}
        />
      )}

      <ToastHost />
    </div>
  );
}
