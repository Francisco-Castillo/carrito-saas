import { useEffect, useLayoutEffect, useState } from "react";
import "./Admin.css";
import { enforceSession, hasUsableSession } from "./dashboard/auth";
import { API, authHeader } from "./admin/api";
import DashboardSection from "./admin/DashboardSection";
import ProductsTable from "./admin/ProductsTable";
import CategoriesTable from "./admin/CategoriesTable";
import CombosTable from "./admin/CombosTable";
import LocalSection from "./admin/LocalSection";
import CreateCategoryModal from "./admin/CreateCategoryModal";
import ToastHost from "./admin/ToastHost";

type AdminView = "dashboard" | "products" | "categories" | "combos" | "local";

/**
 * Label en español por vista: el título del panel nunca muestra la clave
 * interna del código (antes decía "PRODUCTS" mientras el sidebar decía
 * "Productos").
 */
const VIEW_LABELS: Record<AdminView, string> = {
  dashboard: "Dashboard",
  products: "Productos",
  categories: "Categorías",
  combos: "Combos",
  local: "Local",
};

const NAV_ITEMS: AdminView[] = ["dashboard", "products", "categories", "combos", "local"];

/**
 * Marca de respaldo: si el perfil del negocio no se puede cargar, el sidebar
 * muestra un rótulo neutro y no la marca del proveedor SaaS.
 */
const FALLBACK_BRAND = "Panel";

/**
 * Iconos SVG inline del nav (sin dependencias ni CDN). Todos son stroke-based
 * a 20px y aria-hidden: el nombre accesible del botón es el label en español.
 */
function Icon({ children }: { children: React.ReactNode }) {
  return (
    <svg
      aria-hidden="true"
      focusable="false"
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {children}
    </svg>
  );
}

function DashboardIcon() {
  return (
    <Icon>
      <rect x="3.5" y="3.5" width="7" height="7" rx="1.5" />
      <rect x="13.5" y="3.5" width="7" height="7" rx="1.5" />
      <rect x="3.5" y="13.5" width="7" height="7" rx="1.5" />
      <rect x="13.5" y="13.5" width="7" height="7" rx="1.5" />
    </Icon>
  );
}

function ProductsIcon() {
  return (
    <Icon>
      <path d="M4.5 9c0-3 3.4-5 7.5-5s7.5 2 7.5 5" />
      <path d="M4 12.5h16" />
      <path d="M4.5 16.5h15v.5a2.5 2.5 0 0 1-2.5 2.5H7a2.5 2.5 0 0 1-2.5-2.5z" />
    </Icon>
  );
}

function CategoriesIcon() {
  return (
    <Icon>
      <path d="M3.5 7.5a2 2 0 0 1 2-2h4l2 2.5h7a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z" />
    </Icon>
  );
}

function CombosIcon() {
  return (
    <Icon>
      <path d="M12 3.5c1.2 2.8 4.5 4.3 4.5 8a4.5 4.5 0 0 1-9 0c0-1.9.9-3.3 2-4.5.1 1.4.7 2.2 1.6 2.5-.4-2 .2-4.4.9-6z" />
      <path d="M12 20.5a4.5 4.5 0 0 1-4.5-4.3" />
    </Icon>
  );
}

function LocalIcon() {
  return (
    <Icon>
      <path d="M4.5 10 5.6 4.5h12.8L19.5 10" />
      <path d="M5.5 10v8a1.5 1.5 0 0 0 1.5 1.5h10a1.5 1.5 0 0 0 1.5-1.5v-8" />
      <path d="M4 10h16" />
      <path d="M9.5 19.5v-5h5v5" />
    </Icon>
  );
}

function LogoutIcon() {
  return (
    <Icon>
      <path d="M14.5 4.5H18a1.5 1.5 0 0 1 1.5 1.5v12a1.5 1.5 0 0 1-1.5 1.5h-3.5" />
      <path d="M10 8.5 6.5 12l3.5 3.5" />
      <path d="M6.5 12H16" />
    </Icon>
  );
}

const VIEW_ICONS: Record<AdminView, React.ReactNode> = {
  dashboard: <DashboardIcon />,
  products: <ProductsIcon />,
  categories: <CategoriesIcon />,
  combos: <CombosIcon />,
  local: <LocalIcon />,
};

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
  // ""/"null"/"undefined" → redirect a /login/login.html (sin ?restaurant=:
  // el slug vive en la sesión).
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

  // Nombre del negocio para el brand block del sidebar: mismo endpoint owner
  // que LocalSection (GET /api/business/profile, scoped por el JWT). Si falla
  // queda el rótulo neutro FALLBACK_BRAND — nunca la marca del proveedor.
  const [businessName, setBusinessName] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await fetch(`${API}/business/profile`, { headers: authHeader() });
        if (!res.ok) return;
        const data = (await res.json()) as { name?: string };
        if (!cancelled && data.name) setBusinessName(data.name);
      } catch {
        // Sin perfil cargable: se mantiene el rótulo de respaldo.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

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
  // al login limpio (fase 2: sin ?restaurant=, el slug vive en la sesión).
  function logout() {
    for (const key of ["token", "businessId", "role", "username", "expiresAt"]) {
      localStorage.removeItem(key);
    }

    window.location.href = "/login/login.html";
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
        <div className="admin-brand">
          <h2 className="admin-logo">{businessName ?? FALLBACK_BRAND}</h2>
          <span className="admin-brand-sub">Panel de gestión</span>
        </div>

        <nav aria-label="Secciones de administración">
          {NAV_ITEMS.map((item) => (
            <button
              key={item}
              type="button"
              data-view={item}
              className={`admin-nav-btn${view === item ? " is-active" : ""}`}
              onClick={() => goTo(item)}
            >
              {VIEW_ICONS[item]}
              <span>{VIEW_LABELS[item]}</span>
            </button>
          ))}
        </nav>

        <button type="button" className="admin-logout" onClick={logout}>
          <LogoutIcon />
          <span>Logout</span>
        </button>
      </aside>

      {/* MAIN */}
      <div className="admin-main">
        <header className="admin-topbar">
          <h1 id="view-title">{VIEW_LABELS[view]}</h1>
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
