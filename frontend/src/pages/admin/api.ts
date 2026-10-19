/**
 * Rutas y headers del panel admin: mismas paths y métodos que el legacy,
 * ahora autenticados con el Bearer token de localStorage leído en caliente
 * (los endpoints owner de cdaa4d0 responden 401 sin Authorization — entry
 * point de la cadena — y 403 con rol insuficiente, y scoped
 * por el negocio del JWT). El businessId hardcodeado del legacy ya no se
 * manda: el backend lo deduce del token.
 */

export const API = "/api";

/** Header Authorization del legacy: Bearer + token de localStorage en caliente. */
export function authHeader(): Record<string, string> {
  return { Authorization: "Bearer " + localStorage.getItem("token") };
}

export interface AdminProduct {
  id: number;
  name: string;
  price: number;
  stock?: number | null;
  active?: boolean;
}

export interface AdminCategory {
  id: number;
  name: string;
  order?: number;
  active: boolean;
}

export interface AdminCombo {
  id: number;
  name: string;
  price: number;
}
