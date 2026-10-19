/**
 * Toast in-app del menú público: reemplaza los alert() nativos del navegador
 * en el flujo del pedido (validación, confirmación y fallo de envío), mismo
 * espíritu que los toasts de admin y kds pero con estado local del Menu — no
 * hace falta un bus de eventos para un solo productor.
 *
 * `success` se anuncia con role="status" (educado); `error` con role="alert"
 * (asertivo), porque un pedido que no salió no puede esperarse a que el lector
 * de pantalla termine la frase. El cierre manual existe para los textos largos
 * del fallback sin WhatsApp, que no alcanzan a leerse en el auto-dismiss.
 *
 * La barra de progreso cuenta visualmente el tiempo que falta para el
 * auto-dismiss: `duration` (ms) debe ser el MISMO valor que usa el timer del
 * padre, si no la barra miente. 0 = sin barra (toast pegajoso).
 */
export type MenuToastType = "success" | "error";

interface MenuToastProps {
  message: string;
  type: MenuToastType;
  /** Ms hasta el auto-dismiss del padre; maneja la barra de progreso. 0 = sticky. */
  duration?: number;
  onDismiss: () => void;
}

const ICONS: Record<MenuToastType, string> = {
  success: "✓",
  error: "!",
};

export default function MenuToast({
  message,
  type,
  duration = 0,
  onDismiss,
}: MenuToastProps) {
  return (
    <div
      className={`menu-toast glass-panel menu-toast--${type}`}
      role={type === "error" ? "alert" : "status"}
    >
      <span className="menu-toast-icon" aria-hidden="true">
        {ICONS[type]}
      </span>
      <p className="menu-toast-message">{message}</p>
      <button
        type="button"
        className="menu-toast-close"
        onClick={onDismiss}
        aria-label="Cerrar aviso"
      >
        ×
      </button>
      {duration > 0 && (
        <span
          className="menu-toast-progress"
          aria-hidden="true"
          style={{ animationDuration: `${duration}ms` }}
        />
      )}
    </div>
  );
}
