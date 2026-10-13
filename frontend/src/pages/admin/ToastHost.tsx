import { useCallback, useEffect, useState } from "react";
import { TOAST_EVENT } from "./toast";

interface ToastItem {
  id: number;
  message: string;
}

let nextId = 0;

/**
 * Host del toast-success del legacy admin.css/admin.js: apila toasts como el
 * legacy (cada llamada creaba su nodo) y respeta sus timings.
 */
export default function ToastHost() {
  const [toasts, setToasts] = useState<ToastItem[]>([]);

  useEffect(() => {
    function onToast(e: Event) {
      const message = (e as CustomEvent<string>).detail;
      setToasts((prev) => [...prev, { id: nextId++, message }]);
    }
    window.addEventListener(TOAST_EVENT, onToast);
    return () => window.removeEventListener(TOAST_EVENT, onToast);
  }, []);

  const remove = useCallback((id: number) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  return (
    <div aria-live="polite">
      {toasts.map((t) => (
        <Toast key={t.id} message={t.message} onDone={() => remove(t.id)} />
      ))}
    </div>
  );
}

function Toast({ message, onDone }: { message: string; onDone: () => void }) {
  const [show, setShow] = useState(false);

  useEffect(() => {
    const showTimer = window.setTimeout(() => setShow(true), 50);
    const hideTimer = window.setTimeout(() => setShow(false), 2500);
    const removeTimer = window.setTimeout(onDone, 3000);
    return () => {
      window.clearTimeout(showTimer);
      window.clearTimeout(hideTimer);
      window.clearTimeout(removeTimer);
    };
  }, [onDone]);

  return (
    <div className={`toast-success${show ? " show" : ""}`} role="status">
      {message}
    </div>
  );
}
