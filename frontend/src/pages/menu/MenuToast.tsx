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
 */
export type MenuToastType = "success" | "error";

interface MenuToastProps {
  message: string;
  type: MenuToastType;
  onDismiss: () => void;
}

export default function MenuToast({ message, type, onDismiss }: MenuToastProps) {
  return (
    <div
      className={`menu-toast glass-panel menu-toast--${type}`}
      role={type === "error" ? "alert" : "status"}
    >
      <p className="menu-toast-message">{message}</p>
      <button
        type="button"
        className="menu-toast-close"
        onClick={onDismiss}
        aria-label="Cerrar aviso"
      >
        ×
      </button>
    </div>
  );
}
