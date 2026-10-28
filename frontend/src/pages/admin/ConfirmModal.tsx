import { useEffect, useRef } from "react";

interface ConfirmModalProps {
  message: string;
  onConfirm: () => void;
  onCancel: () => void;
  /** Texto del botón de acción; por defecto «Confirmar» (resto del panel). */
  confirmLabel?: string;
}

/**
 * Puerto del showToastModal del legacy admin/admin.js: modal de confirmación
 * con botón "Confirmar", cierre con clic fuera y Escape (ambos resuelven
 * false, como el legacy).
 *
 * `confirmLabel` permite nombrar la acción (p. ej. «Desactivar»): un botón que
 * dice «Confirmar» obliga a releer el mensaje para saber qué se confirma.
 */
export default function ConfirmModal({ message, onConfirm, onCancel, confirmLabel }: ConfirmModalProps) {
  const confirmRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    confirmRef.current?.focus();

    function escHandler(e: KeyboardEvent) {
      if (e.key === "Escape") onCancel();
    }
    document.addEventListener("keydown", escHandler);
    return () => document.removeEventListener("keydown", escHandler);
  }, [onCancel]);

  return (
    <div
      className="toast-modal-backdrop"
      onClick={(e) => {
        if (e.target === e.currentTarget) onCancel();
      }}
    >
      <div className="toast-modal" role="alertdialog" aria-modal="true" aria-label={message}>
        <p>{message}</p>
        <div className="toast-modal-actions">
          <button type="button" className="btn-ghost" onClick={onCancel}>
            Cancelar
          </button>
          <button ref={confirmRef} type="button" className="btn-primary" onClick={onConfirm}>
            {confirmLabel ?? "Confirmar"}
          </button>
        </div>
      </div>
    </div>
  );
}
