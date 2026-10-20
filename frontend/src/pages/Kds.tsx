import { useEffect, useLayoutEffect, useMemo, useState } from "react";
import "./Kds.css";
import { enforceSession, hasPanelSession } from "./dashboard/auth";
import { getRestaurantSlug, logoutKds, type KdsOrder } from "./kds/api";
import { attachAudioUnlockListeners } from "./kds/audio";
import { useKdsOrders } from "./kds/useKdsOrders";
import OrderCard from "./kds/OrderCard";
import CancelOrderModal from "./kds/CancelOrderModal";
import KdsToastHost from "./kds/KdsToastHost";

/**
 * Puerto del KDS (kitchen display) legacy: api/src/main/resources/static/kds/
 * (index.html + kds.js + kds.css + toast.js) sobre el lenguaje liquid glass.
 *
 * - Guard de sesión: el bloque inline del <head> del legacy index.html:16-38,
 *   aquí reutilizando hasPanelSession/enforceSession de dashboard/auth.
 * - Bucle principal: WebSocket STOMP + carga inicial (useKdsOrders).
 * - Reloj: updateClock cada 1s (kds.js:279-290, 310).
 * - Desbloqueo de audio y fullscreen en la primera interacción (kds.js:292-303).
 * - Logout que conserva el slug (kds.js:313-320).
 */

// kds.js:91-129 — columnas del kanban con los mismos nombres de estado.
const COLUMNS: { status: string; label: string; className: string }[] = [
  { status: "NEW", label: "Nuevos", className: "new" },
  { status: "PREPARING", label: "Preparando", className: "preparing" },
  { status: "READY", label: "Listos", className: "ready" },
];

function titleCaseSlug(slug: string): string {
  return slug
    .replace(/-/g, " ")
    .replace(/\b\w/g, (l) => l.toUpperCase());
}

export default function Kds() {
  // Guard del legacy (index.html <head>): corre antes del primer paint y
  // nunca deja la página corriendo sin sesión usable. Fase 2 (login
  // orgánico): el panel también exige el slug de sesión (hasPanelSession) —
  // una sesión con token pero sin slug vuelve al login por enforceSession
  // antes de renderizar o fetchear, nunca con "Restaurante no especificado"
  // ni paneles vacíos en silencio.
  const [sessionUsable] = useState(hasPanelSession);

  useLayoutEffect(() => {
    if (!sessionUsable) {
      enforceSession();
    }
  }, [sessionUsable]);

  const slug = useMemo(() => getRestaurantSlug(), []);

  // kds.js:279-290, 310 — reloj cada 1s; el mismo tick refresca los timers
  // de las tarjetas (el legacy solo los recalculaba al re-crear tarjetas).
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  // kds.js:292-294 — desbloqueo de audio en la primera interacción.
  useEffect(() => {
    attachAudioUnlockListeners();
  }, []);

  // kds.js:299-303 — fullscreen en el primer clic (una sola vez).
  useEffect(() => {
    function onFirstClick() {
      if (!document.fullscreenElement) {
        document.documentElement.requestFullscreen().catch(() => {});
      }
    }
    document.addEventListener("click", onFirstClick, { once: true });
    return () => document.removeEventListener("click", onFirstClick);
  }, []);

  const { sortedOrders, live, fatal, refresh, advance } = useKdsOrders(
    slug,
    sessionUsable && Boolean(slug)
  );

  // Modal de cancelación — kds.js:547-578 (showCancelModal guarda el orderId).
  const [cancelOrderId, setCancelOrderId] = useState<number | null>(null);

  // Entrada escalonada SOLO en el primer paint: en una cocina re-animar en
  // cada actualización distrae, así que la clase se retira tras el montaje.
  const [firstPaint, setFirstPaint] = useState(true);
  useEffect(() => {
    const timer = window.setTimeout(() => setFirstPaint(false), 900);
    return () => window.clearTimeout(timer);
  }, []);

  // kds.js:91-129 — reparto de pedidos por columna (sort ya aplicado).
  const byColumn = useMemo(() => {
    const map = new Map<string, KdsOrder[]>();
    COLUMNS.forEach((c) => map.set(c.status, []));
    sortedOrders.forEach((order) => {
      map.get(order.status)?.push(order);
    });
    return map;
  }, [sortedOrders]);

  if (!sessionUsable) {
    // Redirect en curso: no renderizamos la página sin sesión.
    return null;
  }

  // kds.js:3-9 — sin slug el legacy muestra "Restaurante no especificado".
  if (!slug) {
    return (
      <div className="kds-screen">
        <div className="kds-fatal glass-panel">
          <h1>Restaurante no especificado</h1>
        </div>
      </div>
    );
  }

  // kds.js:55-57 — 404 de /api/business/orders/active.
  if (fatal === "restaurant-not-found") {
    return (
      <div className="kds-screen">
        <div className="kds-fatal glass-panel">
          <h1>Restaurante no encontrado</h1>
        </div>
      </div>
    );
  }

  return (
    <div className="kds-screen">
      <div className="aurora" aria-hidden="true">
        <div className="aurora-blob aurora-blob--indigo" />
        <div className="aurora-blob aurora-blob--violet" />
        <div className="aurora-blob aurora-blob--fuchsia" />
        <div className="aurora-blob aurora-blob--cyan" />
      </div>

      <header className="topbar">
        <div className="title">
          <h1>KDS Cocina</h1>
          <span className="kds-restaurant">{titleCaseSlug(slug)}</span>
        </div>

        <div className="topbar-right">
          {/* Estado de conexión, no reloj: la hora real vive en .clock —
              mostrar "Act. HH:MM:SS" acá era redundante con ese reloj. */}
          <div className={`refresh-indicator${live ? " live" : ""}`} title={live ? "Conectado en vivo" : "Reconectando..."}>
            <span className="refresh-dot" aria-hidden="true" />
            <span className="refresh-label">
              {live ? "En vivo" : "Reconectando…"}
            </span>
          </div>

          {/* kds.js:279-290 — reloj */}
          <div className="clock">
            <div className="clock-time">{now.toLocaleTimeString()}</div>
            <div className="clock-date">
              {`${String(now.getDate()).padStart(2, "0")}/${String(
                now.getMonth() + 1
              ).padStart(2, "0")}/${now.getFullYear()}`}
            </div>
          </div>

          <button
            type="button"
            className="logout-btn"
            onClick={logoutKds}
            title="Cerrar sesión"
            aria-label="Cerrar sesión"
          >
            ⏻
          </button>
        </div>
      </header>

      {/* Kanban horizontal (1 columna por estado) / apilado en mobile (CSS). */}
      <main className={`kanban${firstPaint ? " first-paint" : ""}`}>
        {COLUMNS.map((col) => {
          const orders = byColumn.get(col.status) ?? [];
          return (
            <section key={col.status} className={`column column-${col.className} glass-panel`}>
              <div className="column-header">
                {col.label} ({orders.length})
              </div>
              <div className="column-body">
                {orders.map((order) => (
                  <OrderCard
                    key={order.orderId}
                    order={order}
                    now={now}
                    animate={!firstPaint}
                    onAdvance={advance}
                    onRequestCancel={setCancelOrderId}
                  />
                ))}
                {orders.length === 0 && (
                  <p className="column-empty">Sin pedidos</p>
                )}
              </div>
            </section>
          );
        })}
      </main>

      {cancelOrderId !== null && (
        <CancelOrderModal
          orderId={cancelOrderId}
          onClose={() => setCancelOrderId(null)}
          onCancelled={() => {
            setCancelOrderId(null);
            refresh(); // kds.js:650-651 — hideCancelModal + loadOrders
          }}
        />
      )}

      <KdsToastHost />

      {/* kds.js:17-31 + index.html:110-115 — sonidos del KDS (Vite public/). */}
      <audio id="newOrderSound" src="/ding.mp3" preload="auto" />
      <audio id="readySound" src="/ready.mp3" preload="auto" />
    </div>
  );
}
