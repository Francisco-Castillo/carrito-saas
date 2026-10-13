/**
 * Rutas y headers del legacy admin/admin.js: mismas paths, mismos métodos,
 * mismos headers (el legacy manda Bearer token leído de localStorage en el
 * momento de la llamada en categorías, y NINGÚN header en products/combos).
 */

export const API = "/api";

/** businessId hardcodeado del legacy (admin.js:5, con su TODO). */
export const BUSINESS_ID = 1;

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
