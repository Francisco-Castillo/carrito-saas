/**
 * Puerto 1:1 del guard de sesión del legacy dashboard/index.html (bloque
 * inline en <head>) y de checkAuth/isTokenExpired/logout del legacy
 * dashboard/dashboard.js.
 *
 * El rol lo valida el servidor (403 con sesión y rol insuficiente; 401 sin
 * sesión): este guard solo cubre la ausencia de una sesión usable. Nunca lanza y nunca deja la página corriendo
 * sin sesión: si falla la lectura del token (almacenamiento bloqueado, modo
 * privado), cae al redirect de todos modos.
 *
 * Fase 2 (login orgánico): el slug del restaurante vive en la sesión
 * (localStorage "restaurantSlug", lo escribe el login tras autenticar) y el
 * redirect al login ya no lleva ?restaurant=. La lectura del slug aplica el
 * mismo endurecimiento que el token: almacenamiento bloqueado y valores
 * muertos ("null", "undefined", vacío, espacios) no son un slug legible.
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

/**
 * Slug del restaurante resuelto desde la SESIÓN (localStorage
 * "restaurantSlug"), nunca de la URL. Mismo endurecimiento que el token:
 * un valor recortado vacío o "null"/"undefined" tampoco es un slug, y un
 * almacenamiento bloqueado devuelve null en vez de lanzar.
 */
export function getSessionRestaurantSlug(): string | null {
  try {
    const slug = localStorage.getItem("restaurantSlug");
    // Se compara el valor RECORTADO, igual que el token: un slug " null " o
    // "null\n" no es un slug de sesión.
    const session = typeof slug === "string" ? slug.trim() : "";
    return session !== "" && session !== "null" && session !== "undefined"
      ? session
      : null;
  } catch {
    // Slug ilegible (almacenamiento bloqueado): no hay slug de sesión.
    return null;
  }
}

/** Redirect del guard: siempre al login limpio, sin ?restaurant= — el login
 * orgánico resuelve el restaurante desde la sesión. */
export function enforceSession(): void {
  window.location.replace(LOGIN_PAGE);
}

/**
 * Slug de sesión o redirect al login: cierra el hueco de los paneles
 * silenciosamente vacíos. Una sesión autenticada sin slug legible (sesiones
 * creadas antes del login orgánico, o deep links/bookmarks que solo traían
 * la query) ya no sigue construyendo una base de API sin slug con todos los
 * requests fallando en silencio: vuelve al login limpio (sin ?restaurant=),
 * donde el login orgánico escribe el slug en la próxima autenticación.
 * Nunca lanza: sin slug legible pide el redirect y devuelve null.
 */
export function sessionRestaurantSlugOrRedirect(): string | null {
  const slug = getSessionRestaurantSlug();
  if (slug === null) {
    enforceSession();
    return null;
  }
  return slug;
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
  // La sesión incluye el slug del restaurante (fase 2, login orgánico): se
  // limpia junto con el token.
  localStorage.removeItem("token");
  localStorage.removeItem("restaurantSlug");
  window.location.href = LOGIN_PAGE;
}

export function checkAuth(): void {
  const token = localStorage.getItem("token");
  if (!token || isTokenExpired(token)) {
    logout();
  }
}
