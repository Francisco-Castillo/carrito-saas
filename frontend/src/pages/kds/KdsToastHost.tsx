import { useCallback, useEffect, useState } from "react";
import { KDS_TOAST_EVENT, type KdsToastDetail, type KdsToastType } from "./toast";

interface ToastItem extends KdsToastDetail {
  id: number;
}

let nextId = 0;

/**
 * Host del toast del legacy kds/toast.js + toast.css (.toast-container,
 * .toast.success/.error/.warning): apila toasts abajo a la derecha, cada uno
 * con su ciclo show (entrada) → hide a los 3000ms → remove a los 3300ms,
 * igual que el legacy (toast.js:19-21).
 */
export default function KdsToastHost() {
  const [toasts, setToasts] = useState<ToastItem[]>([]);

  useEffect(() => {
    function onToast(e: Event) {
      const detail = (e as CustomEvent<KdsToastDetail>).detail;
      // Tope de 3 en pantalla: un arranque de servicio dispara un toast por
      // pedido nuevo y sin tope la pila tapa media columna. Se descarta el
      // más viejo inmediatamente (no se encola).
      setToasts((prev) => [...prev, { id: nextId++, ...detail }].slice(-3));
    }
    window.addEventListener(KDS_TOAST_EVENT, onToast);
    return () => window.removeEventListener(KDS_TOAST_EVENT, onToast);
  }, []);

  const remove = useCallback((id: number) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  return (
    <div className="toast-container" aria-live="polite">
      {toasts.map((t) => (
        <KdsToast key={t.id} message={t.message} type={t.type} onDone={() => remove(t.id)} />
      ))}
    </div>
  );
}

function KdsToast({
  message,
  type,
  onDone,
}: {
  message: string;
  type: KdsToastType;
  onDone: () => void;
}) {
  const [leaving, setLeaving] = useState(false);

  useEffect(() => {
    // toast.js:19-21 — hide a los 3000ms, remove 300ms después (toastOut).
    const hideTimer = window.setTimeout(() => setLeaving(true), 3000);
    const removeTimer = window.setTimeout(onDone, 3300);
    return () => {
      window.clearTimeout(hideTimer);
      window.clearTimeout(removeTimer);
    };
  }, [onDone]);

  return (
    <div className={`toast ${type}${leaving ? " toast-out" : ""}`} role="status">
      {message}
    </div>
  );
}
