/**
 * Puerto 1:1 del guard de sesión del legacy dashboard/index.html (bloque
 * inline en <head>) y de checkAuth/isTokenExpired/logout del legacy
 * dashboard/dashboard.js.
 *
 * El rol lo valida el servidor (403 en la API): este guard solo cubre la
 * ausencia de una sesión usable. Nunca lanza y nunca deja la página corriendo
 * sin sesión: si falla la lectura del slug o del token (almacenamiento
 * bloqueado, modo privado), cae al redirect de todos modos. Sin slug legible
 * redirige sin él, con él conserva el restaurante.
 */

const LOGIN_PAGE = "/login/login.html";

/** Sesión usable según el guard del legacy: token recortado no vacío y no "null"/"undefined". */
export function hasUsableSession(): boolean {
  try {
    const token = localStorage.getItem("token");
    // Se compara el valor RECORTADO: un token " null " o "null\n" tampoco
    // es una sesión, y aceptarlo deja la página corriendo sin sesión.
    const session = typeof token === "string" ? token.trim() : "";
    return session !== "" && session !== "null" && session !== "undefined";
  } catch {
    // Sesión ilegible: no hay sesión usable.
    return false;
  }
}

/** Redirect del guard legacy: conserva el restaurante si el slug es legible. */
export function enforceSession(): void {
  let target = LOGIN_PAGE;
  try {
    const slug = new URLSearchParams(window.location.search).get("restaurant");
    if (slug) {
      target += "?restaurant=" + encodeURIComponent(slug);
    }
  } catch {
    // Slug ilegible: redirigimos sin él.
  }
  window.location.replace(target);
}

function isTokenExpired(token: string): boolean {
  try {
    const payload = JSON.parse(atob(token.split(".")[1] ?? "")) as {
      exp?: number;
    };
    const exp = (payload.exp ?? 0) * 1000;
    return Date.now() > exp;
  } catch {
    return true;
  }
}

export function logout(): void {
  localStorage.removeItem("token");
  window.location.href = LOGIN_PAGE;
}

export function checkAuth(): void {
  const token = localStorage.getItem("token");
  if (!token || isTokenExpired(token)) {
    logout();
  }
}
