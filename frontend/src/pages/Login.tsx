import { useEffect, useMemo, useState, type FormEvent } from "react";
import "./Login.css";

/** Neutral chip label shown when no restaurant slug is present in the URL
 * (organic login): the user logs in without a restaurant context and the
 * backend resolves membership. */
const ORGANIC_LOGIN_LABEL = "Ingreso general";

function titleCaseSlug(slug: string): string {
  return slug
    .replace(/-/g, " ")
    .replace(/\b\w/g, (l) => l.toUpperCase());
}

function redirectPathForRole(role: string, slug?: string | null): string | null {
  const suffix = slug ? `?restaurant=${slug}` : "";
  if (role.includes("OWNER")) return `/dashboard/index.html${suffix}`;
  if (role.includes("KITCHEN")) return `/kds/index.html${suffix}`;
  if (role.includes("ADMIN")) return `/admin/admin.html${suffix}`;
  return null;
}

interface LoginResponse {
  token: string;
  businessId: string | number;
  role: string;
  username: string;
  expiresAt: string;
  restaurantSlug?: string;
}

function UserIcon() {
  return (
    <svg
      className="input-icon"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
      <circle cx="12" cy="7" r="4" />
    </svg>
  );
}

function LockIcon() {
  return (
    <svg
      className="input-icon"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
      <path d="M7 11V7a5 5 0 0 1 10 0v4" />
    </svg>
  );
}

export default function Login() {
  const slug = useMemo(
    () => new URLSearchParams(window.location.search).get("restaurant"),
    []
  );

  const [restaurantName, setRestaurantName] = useState<string | null>(null);
  const [restaurantLoading, setRestaurantLoading] = useState<boolean>(Boolean(slug));

  const [username, setUsername] = useState<string>(() => localStorage.getItem("savedUser") ?? "");
  const [password, setPassword] = useState("");
  const [remember, setRemember] = useState<boolean>(() => Boolean(localStorage.getItem("savedUser")));
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!slug) {
      // Organic login: no restaurant context in the URL, show a neutral
      // chip and do not fire a slug lookup.
      setRestaurantName(ORGANIC_LOGIN_LABEL);
      setRestaurantLoading(false);
      return;
    }

    let cancelled = false;
    const cacheKey = `restaurantName_${slug}`;

    async function loadRestaurantInfo() {
      const cached = localStorage.getItem(cacheKey);
      if (cached) {
        setRestaurantName(cached);
        setRestaurantLoading(false);
        return;
      }

      try {
        const res = await fetch(`/api/restaurants/slug/${slug}`);
        if (!res.ok) throw new Error("Restaurante no encontrado");

        const data = await res.json();
        const name: string = data.name || slug!;

        if (!cancelled) {
          setRestaurantName(name);
          setRestaurantLoading(false);
        }
        localStorage.setItem(cacheKey, name);
      } catch (e) {
        console.error("Error cargando restaurante:", e);
        if (!cancelled) {
          setRestaurantName(titleCaseSlug(slug!));
          setRestaurantLoading(false);
        }
      }
    }

    loadRestaurantInfo();
    return () => {
      cancelled = true;
    };
  }, [slug]);

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);

    if (!username.trim() || !password.trim()) {
      setError("Completa todos los campos");
      return;
    }

    if (remember) {
      localStorage.setItem("savedUser", username.trim());
    } else {
      localStorage.removeItem("savedUser");
    }

    const body: Record<string, string> = {
      username: username.trim(),
      password: password.trim(),
    };
    // Organic login: only include the restaurant slug when the URL provided
    // one; otherwise the backend resolves the business from membership.
    if (slug) body.restaurantSlug = slug;

    try {
      setLoading(true);

      const res = await fetch("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });

      if (!res.ok) {
        let msg = "Error de autenticación";
        try {
          const errData = await res.json();
          msg = errData.message || msg;
        } catch {
          /* keep default message */
        }
        throw new Error(msg);
      }

      const data: LoginResponse = await res.json();

      localStorage.setItem("token", data.token);
      localStorage.setItem("businessId", String(data.businessId));
      localStorage.setItem("role", data.role);
      localStorage.setItem("username", data.username);
      localStorage.setItem("expiresAt", data.expiresAt);

      const target = redirectPathForRole(data.role, data.restaurantSlug ?? slug);
      if (!target) throw new Error("Rol no permitido");

      window.location.href = target;
    } catch (err) {
      setLoading(false);
      setError(err instanceof Error && err.message ? err.message : "Error inesperado");
      console.error("Login error:", err);
    }
  }

  return (
    <>
      <div className="aurora" aria-hidden="true">
        <div className="aurora-blob aurora-blob--indigo" />
        <div className="aurora-blob aurora-blob--violet" />
        <div className="aurora-blob aurora-blob--fuchsia" />
        <div className="aurora-blob aurora-blob--cyan" />
      </div>

      <main className="login-screen">
        <section className="login-card glass-panel fade-up" aria-label="Iniciar sesión">
          <header className="login-header">
            <div className="logo-badge glass-panel" aria-hidden="true">
              🍔
            </div>
            <h1 className="login-title">Carrito SaaS</h1>
            <p className="login-subtitle">Panel de gestión para foodtrucks</p>
          </header>

          <div className="restaurant-chip" data-loading={restaurantLoading || undefined}>
            <span className="restaurant-chip-label">Restaurante</span>
            <span className="restaurant-chip-name">
              {restaurantLoading
                ? "Cargando..."
                : restaurantName ?? ORGANIC_LOGIN_LABEL}
            </span>
          </div>

          <form className="login-form stagger" onSubmit={handleSubmit} noValidate>
            <div className="field">
              <label className="visually-hidden" htmlFor="username">
                Usuario
              </label>
              <div className="input-group">
                <UserIcon />
                <input
                  id="username"
                  type="text"
                  placeholder="Usuario"
                  autoComplete="username"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  disabled={loading}
                />
              </div>
            </div>

            <div className="field">
              <label className="visually-hidden" htmlFor="password">
                Contraseña
              </label>
              <div className="input-group">
                <LockIcon />
                <input
                  id="password"
                  type="password"
                  placeholder="Contraseña"
                  autoComplete="current-password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  disabled={loading}
                />
              </div>
            </div>

            <div className="form-options">
              <label className="remember-label">
                <input
                  type="checkbox"
                  checked={remember}
                  onChange={(e) => setRemember(e.target.checked)}
                  disabled={loading}
                />
                <span>Recordarme</span>
              </label>
            </div>

            <button className="login-button" type="submit" disabled={loading}>
              {loading ? (
                <span className="spinner" aria-label="Cargando" />
              ) : (
                "Iniciar sesión"
              )}
            </button>

            {error && (
              <p className="form-error" role="alert">
                {error}
              </p>
            )}
          </form>

          <footer className="login-footer">
            <p>© 2026 Carrito SaaS</p>
            <span className="powered">Powered by SDE Group</span>
          </footer>
        </section>
      </main>
    </>
  );
}
