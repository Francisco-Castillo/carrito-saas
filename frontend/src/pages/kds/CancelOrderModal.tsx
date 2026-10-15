import { useEffect, useState } from "react";
import {
  cancelOrder,
  loadCancellationReasons,
  type CancellationReason,
} from "./api";
import { showToast } from "./toast";

// kds.js:549-560 — el legacy cachea los motivos: solo los carga si la
// lista está vacía, aunque se abra el modal varias veces.
let cachedReasons: CancellationReason[] | null = null;

interface CancelOrderModalProps {
  orderId: number;
  onClose: () => void;
  onCancelled: () => void;
}

/**
 * Puerto del modal de cancelación del legacy (index.html:120-143,
 * kds.js:547-655 y estilos kds.css:196-330): select de motivos cargado del
 * backend, nota opcional, validación con estado de error y toast de
 * advertencia, botón con loading "Cancelando...", toasts de resultado y
 * cierre con clic fuera del contenido.
 */
export default function CancelOrderModal({
  orderId,
  onClose,
  onCancelled,
}: CancelOrderModalProps) {
  const [reasons, setReasons] = useState<CancellationReason[]>([]);
  const [reasonId, setReasonId] = useState("");
  const [note, setNote] = useState("");
  const [reasonError, setReasonError] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  // kds.js:549-560 — showCancelModal carga los motivos una sola vez (cache).
  useEffect(() => {
    if (cachedReasons) {
      setReasons(cachedReasons);
      return;
    }
    loadCancellationReasons().then((loaded) => {
      cachedReasons = loaded;
      setReasons(loaded);
    });
  }, []);

  async function confirmCancelOrder(): Promise<void> {
    if (submitting) return;

    // kds.js:618-625 — validación: sin motivo → error + toast warning.
    if (!reasonId) {
      setReasonError(true);
      showToast("Selecciona un motivo de cancelación", "warning");
      return;
    }

    try {
      setSubmitting(true);
      // kds.js:630-633 — botón en loading.
      await cancelOrder(orderId, parseInt(reasonId), note);

      showToast("Pedido cancelado correctamente", "success"); // kds.js:649
      onCancelled(); // hideCancelModal + loadOrders (kds.js:650-651)
    } catch (e) {
      // kds.js:653-655
      console.error("Error cancelando pedido", e);
      showToast("Error al cancelar pedido", "error");
    } finally {
      // kds.js:658-661 — restaurar botón siempre.
      setSubmitting(false);
    }
  }

  return (
    <div
      className="modal"
      role="dialog"
      aria-modal="true"
      aria-labelledby="cancel-modal-title"
      onClick={(e) => {
        // kds.js:753-757 — clic fuera del contenido cierra el modal.
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="modal-content glass-panel">
        <h3 id="cancel-modal-title">Cancelar pedido</h3>
        <p className="modal-subtitle">Selecciona el motivo de la cancelación</p>

        <select
          className={`input${reasonError ? " error" : ""}`}
          value={reasonId}
          onChange={(e) => {
            setReasonId(e.target.value);
            setReasonError(false); // kds.js:615 — limpiar errores previos
          }}
        >
          <option value="">Seleccionar motivo</option>
          {reasons.map((r) => (
            <option key={r.id} value={r.id}>
              {r.description}
            </option>
          ))}
        </select>

        <input
          type="text"
          className="input"
          placeholder="Observación (opcional)"
          value={note}
          onChange={(e) => setNote(e.target.value)}
        />

        <div className="modal-buttons">
          <button type="button" className="btn btn-delivered" onClick={onClose}>
            No
          </button>
          <button
            type="button"
            className="btn btn-start"
            disabled={submitting}
            onClick={confirmCancelOrder}
          >
            {submitting ? "Cancelando..." : "Sí cancelar"}
          </button>
        </div>
      </div>
    </div>
  );
}
