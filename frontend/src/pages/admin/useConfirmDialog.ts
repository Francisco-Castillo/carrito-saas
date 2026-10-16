import { useEffect, useRef, useState } from "react";

/**
 * Confirmación de delete del panel, en un solo lugar. Las tres tablas
 * (Productos, Categorías, Combos) comparten el patrón del legacy
 * admin/admin.js: una promesa que responde true SOLO con "Confirmar", y
 * que resuelve false con Cancelar, clic fuera, Escape — o cuando el
 * componente dueño se desmonta con el confirm todavía abierto, para que
 * el flujo que la espera nunca quede colgado.
 */
export interface ConfirmState {
  message: string;
}

export function useConfirmDialog() {
  const [confirmState, setConfirmState] = useState<ConfirmState | null>(null);
  const resolveRef = useRef<((ok: boolean) => void) | null>(null);

  useEffect(() => {
    // El dueño se desmonta con una confirmación pendiente: resolver false
    // y soltar la referencia, para que el flujo que espera la promesa
    // continúe (como un Cancelar) y nada quede colgado.
    return () => {
      resolveRef.current?.(false);
      resolveRef.current = null;
    };
  }, []);

  function confirmAsync(message: string): Promise<boolean> {
    return new Promise((resolve) => {
      resolveRef.current = resolve;
      setConfirmState({ message });
    });
  }

  function settleConfirm(ok: boolean) {
    resolveRef.current?.(ok);
    resolveRef.current = null;
    setConfirmState(null);
  }

  return { confirmState, confirmAsync, settleConfirm };
}
