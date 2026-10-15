/**
 * Puerto del showSuccessToast del legacy admin/admin.js: toast verde fijo
 * arriba a la derecha con el mismo timing (show a los 50ms, hide a los
 * 2500ms, remove a los 3000ms). En React lo implementamos como evento de
 * ventana que escucha el ToastHost montado por Admin.
 */
export const TOAST_EVENT = "admin:toast";

export function showSuccessToast(message: string): void {
  window.dispatchEvent(new CustomEvent<string>(TOAST_EVENT, { detail: message }));
}
