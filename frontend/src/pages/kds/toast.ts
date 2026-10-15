/**
 * Puerto del showToast del legacy kds/toast.js (líneas 1-23): toast con
 * tipo (success/error/warning) y auto-cierre a los 3000ms con animación de
 * salida de 300ms (toastOut). En React lo implementamos como evento de
 * ventana que escucha el KdsToastHost montado por Kds — el mismo patrón que
 * admin/toast.ts + ToastHost.tsx.
 */

export type KdsToastType = "success" | "error" | "warning" | "info";

export interface KdsToastDetail {
  message: string;
  type: KdsToastType;
}

export const KDS_TOAST_EVENT = "kds:toast";

export function showToast(message: string, type: KdsToastType = "success"): void {
  window.dispatchEvent(
    new CustomEvent<KdsToastDetail>(KDS_TOAST_EVENT, {
      detail: { message, type },
    })
  );
}
