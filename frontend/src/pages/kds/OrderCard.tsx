import { useEffect, useRef, type CSSProperties } from "react";
import type { KdsOrder } from "./api";

/** kds.js:11 — MAX_TIME = 900 (15 min para la barra de progreso). */
const MAX_TIME = 900;

/** kds.js:421 — umbral de swipe en px. */
const DRAG_THRESHOLD = 80;

/** kds.js:379 — duración de la presión larga en ms. */
const LONG_PRESS_MS = 1500;

/**
 * Containing block real de un elemento position:fixed: el ancestro más
 * cercano con transform, filter, backdrop-filter, perspective o will-change
 * de transform/filter lo reubica — sus coordenadas dejan de ser relativas
 * al viewport. Devuelve null si el containing block es el viewport.
 */
function findFixedContainingBlock(el: HTMLElement): HTMLElement | null {
  let node: HTMLElement | null = el.parentElement;
  while (node) {
    const s = window.getComputedStyle(node);
    const backdrop = s.getPropertyValue("backdrop-filter");
    if (
      s.transform !== "none" ||
      s.perspective !== "none" ||
      s.filter !== "none" ||
      (backdrop !== "" && backdrop !== "none") ||
      s.willChange.includes("transform") ||
      s.willChange.includes("filter")
    ) {
      return node;
    }
    node = node.parentElement;
  }
  return null;
}

interface OrderCardProps {
  order: KdsOrder;
  now: Date;
  animate: boolean;
  onAdvance: (orderId: number, status: string) => void;
  onRequestCancel: (orderId: number) => void;
}

/**
 * Puerto de createCard del legacy kds/kds.js (líneas 133-262): número,
 * cliente, tipo (Retiro/Delivery), items "Nx producto", notas, timer con
 * colores por minutos transcurridos, barra de progreso y botón de avance.
 * Incluye el drag & swipe del legacy (kds.js:334-513): swipe derecha avanza
 * al siguiente estado, swipe izquierda cancela (NEW) o revierte (PREPARING),
 * y presión larga sobre un pedido NEW abre el modal de cancelación.
 */
export default function OrderCard({
  order,
  now,
  animate,
  onAdvance,
  onRequestCancel,
}: OrderCardProps) {
  const cardRef = useRef<HTMLDivElement>(null);

  // La animación se decide SOLO al montar (como el legacy: kds.js:702 añade
  // fade-in únicamente a tarjetas nuevas del WS). Si no, al terminar el
  // stagger del primer paint la prop cambiaría y re-animaría todas las
  // tarjetas ya montadas.
  const animateAtMount = useRef(animate).current;

  /* TIEMPO TRANSCURRIDO — kds.js:141-155 */
  const created = new Date(order.createdAt);
  const seconds = Math.max(0, Math.floor((now.getTime() - created.getTime()) / 1000));
  const minutes = Math.floor(seconds / 60);

  let timerClass = "green";
  if (minutes > 5) timerClass = "orange";
  if (minutes > 10) timerClass = "red"; // >10 min también marca "urgent" (kds.js:150-151)

  let progress = (seconds / MAX_TIME) * 100;
  if (progress > 100) progress = 100;
  const progressPct = progress.toFixed(2);

  /* BOTÓN POR ESTADO — kds.js:216-247 */
  let button: { label: string; className: string; next: string } | null = null;
  if (order.status === "NEW") {
    button = { label: "COMENZAR", className: "btn-start", next: "PREPARING" };
  } else if (order.status === "PREPARING") {
    button = { label: "LISTO", className: "btn-ready", next: "READY" };
  } else if (order.status === "READY") {
    button = { label: "ENTREGADO", className: "btn-delivered", next: "DELIVERED" };
  }

  /* DRAG & SWIPE — kds.js:334-513 (enableDrag/dragStart/dragMove/dragEnd/dragCancel) */
  useEffect(() => {
    const card = cardRef.current;
    if (!card) return;

    let dragged = false; // el legacy usaba draggedCard = card; aquí la card ES el nodo
    let dragContainer: HTMLElement | null = null;
    let startX = 0;
    let startY = 0;
    let currentX = 0;
    let moved = false;
    let longPressTimeout: number | null = null;

    function dragCancel(): void {
      if (!dragged) return;
      // kds.js:460-486 — animación de retorno y limpieza de estilos.
      card!.classList.add("transition");
      card!.style.transform = "translate(0px,0px) rotate(0deg)";
      window.setTimeout(() => {
        card!.classList.remove("dragging", "rotate", "transition");
        card!.style.width = "";
        card!.style.height = "";
        card!.style.left = "";
        card!.style.top = "";
        dragContainer?.classList.remove("drag-container-active");
        dragContainer = null;
        dragged = false;
      }, 300);
    }

    function dragStart(e: MouseEvent | TouchEvent): void {
      const target = e.target as HTMLElement;
      // kds.js:355-357 — los botones no inician drag.
      if (target.classList.contains("btn") || target.closest("button")) return;

      dragged = true;
      moved = false;

      const rect = card!.getBoundingClientRect();

      // position:fixed NO siempre es viewport-relativo: un ancestro con
      // backdrop-filter (las columnas de vidrio), transform, filter o
      // perspective se vuelve su containing block, y las coordenadas de
      // viewport aterrizan desplazadas — la tarjeta saltaba al agarrarla,
      // el puntero quedaba fuera y el mouseleave cancelaba el drag solo.
      // Se calcula el origen real del containing block y se neutraliza el
      // overflow de ese ancestro mientras dura el gesto.
      dragContainer = findFixedContainingBlock(card!);
      let originLeft = 0;
      let originTop = 0;
      if (dragContainer) {
        const cbRect = dragContainer.getBoundingClientRect();
        const cs = window.getComputedStyle(dragContainer);
        originLeft = cbRect.left + parseFloat(cs.borderLeftWidth || "0");
        originTop = cbRect.top + parseFloat(cs.borderTopWidth || "0");
        dragContainer.classList.add("drag-container-active");
      }

      // kds.js:360-365
      card!.classList.add("dragging", "rotate");
      card!.style.width = rect.width + "px";
      card!.style.height = rect.height + "px";
      card!.style.left = rect.left - originLeft + "px";
      card!.style.top = rect.top - originTop + "px";

      // kds.js:367-374
      startX = "touches" in e ? e.touches[0].clientX : e.clientX;
      startY = "touches" in e ? e.touches[0].clientY : e.clientY;
      currentX = startX;

      // kds.js:376-387 — presión larga sobre NEW abre el modal de cancelación.
      if (card!.dataset.status === "NEW") {
        longPressTimeout = window.setTimeout(() => {
          if (!moved) {
            onRequestCancel(Number(card!.dataset.id));
            dragCancel();
          }
        }, LONG_PRESS_MS);
      }
    }

    function dragMove(e: MouseEvent | TouchEvent): void {
      if (!dragged) return;

      const clientX = "touches" in e ? e.touches[0].clientX : e.clientX;
      const clientY = "touches" in e ? e.touches[0].clientY : e.clientY;

      const diffX = clientX - startX;
      const diffY = clientY - startY;

      // kds.js:398-402
      if (Math.abs(diffX) > 5 || Math.abs(diffY) > 5) {
        moved = true;
      }

      currentX = clientX;

      const rotate = diffX / 10; // kds.js:407
      card!.style.transform = `translate(${diffX}px,${diffY}px) rotate(${rotate}deg)`;

      if (e.cancelable) e.preventDefault(); // kds.js:410
    }

    function dragEnd(): void {
      if (!dragged) return;
      if (longPressTimeout !== null) window.clearTimeout(longPressTimeout); // kds.js:416

      const diffX = currentX - startX;
      const orderId = Number(card!.dataset.id);
      const status = card!.dataset.status ?? "";

      if (diffX > DRAG_THRESHOLD) {
        // kds.js:423-425 → advanceNextState (kds.js:490-502)
        if (status === "NEW") onAdvance(orderId, "PREPARING");
        else if (status === "PREPARING") onAdvance(orderId, "READY");
      } else if (diffX < -DRAG_THRESHOLD) {
        // kds.js:427-437
        if (status === "NEW") {
          onRequestCancel(orderId); // showCancelModal
        } else if (status === "PREPARING") {
          onAdvance(orderId, "NEW"); // revertState (kds.js:504-512)
        }
      } else {
        dragCancel(); // kds.js:440-443
        return;
      }

      dragCancel(); // kds.js:450
    }

    function dragAbort(): void {
      // kds.js:461-462 — mouseleave/touchcancel también cancelan.
      if (longPressTimeout !== null) window.clearTimeout(longPressTimeout);
      dragCancel();
    }

    // kds.js:334-350 — enableDrag(): mismos listeners, touchmove no pasivo
    // para poder hacer preventDefault durante el arrastre.
    card.addEventListener("mousedown", dragStart);
    card.addEventListener("touchstart", dragStart, { passive: true });
    card.addEventListener("mousemove", dragMove);
    card.addEventListener("touchmove", dragMove, { passive: false });
    card.addEventListener("mouseup", dragEnd);
    card.addEventListener("touchend", dragEnd);
    card.addEventListener("mouseleave", dragAbort);
    card.addEventListener("touchcancel", dragAbort);

    return () => {
      if (longPressTimeout !== null) window.clearTimeout(longPressTimeout);
      card.removeEventListener("mousedown", dragStart);
      card.removeEventListener("touchstart", dragStart);
      card.removeEventListener("mousemove", dragMove);
      card.removeEventListener("touchmove", dragMove);
      card.removeEventListener("mouseup", dragEnd);
      card.removeEventListener("touchend", dragEnd);
      card.removeEventListener("mouseleave", dragAbort);
      card.removeEventListener("touchcancel", dragAbort);
    };
  }, [onAdvance, onRequestCancel]);

  return (
    <div
      ref={cardRef}
      className={`order-card glass-panel urgency-${timerClass}${
        timerClass === "red" ? " urgent" : ""
      }${animateAtMount ? " fade-in" : ""}`}
      style={{ "--progress": `${progressPct}%` } as CSSProperties}
      data-id={order.orderId}
      data-status={order.status}
    >
      <div className="order-card-main">
        <div className="order-head">
          <div className="order-number">#{order.orderNumber}</div>
        </div>
        <div className="customer">{order.customerName}</div>

        {/* ITEMS — kds.js:167-171 */}
        <div className="order-items">
          {order.items.map((i, idx) => (
            <div className="item" key={idx}>
              <span className="item-qty">{i.quantity}x</span> {i.productName}
            </div>
          ))}
        </div>

        {/* NOTAS — kds.js:203-213 */}
        {order.notes && order.notes.trim() !== "" && (
          <div className="order-notes">
            <span className="order-notes-icon" aria-hidden="true">
              📝
            </span>
            {order.notes}
          </div>
        )}
      </div>

      <div className="order-card-footer">
        <div className={`timer ${timerClass}`}>
          {minutes}:{String(seconds % 60).padStart(2, "0")}
        </div>
        {button && (
          <button
            type="button"
            className={`btn ${button.className}`}
            onClick={() => onAdvance(order.orderId, button.next)}
          >
            {button.label}
          </button>
        )}
      </div>
    </div>
  );
}
