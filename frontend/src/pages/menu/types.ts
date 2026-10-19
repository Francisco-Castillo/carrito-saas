export interface ComboItem {
  quantity: number;
  productName: string;
}

export interface Combo {
  id: number | string;
  name: string;
  price: number;
  categoryId?: number | string;
  items?: ComboItem[];
  /** Imagen propia del combo (opcional); el menú cae al derivado si es null. */
  imageUrl?: string | null;
}

export interface Product {
  id: string;
  name: string;
  description?: string;
  price: number;
  categoryId: string;
  categoryName: string;
  imageUrl?: string;
  isCombo: boolean;
}

export interface Restaurant {
  name?: string;
  logoUrl?: string;
  whatsappNumber?: string;
  primaryColor?: string;
}

/**
 * Un turno del horario de atención. `opensAt`/`closesAt` llegan como
 * "HH:mm:ss" (a veces "HH:mm"; ambos formatos conviven en fixtures y
 * payloads). Convención del backend: `closesAt` MENOR que `opensAt` significa
 * que el cierre cae en la madrugada del día SIGUIENTE (viernes 19:00 → 00:30).
 */
export interface BusinessHoursShift {
  opensAt: string | null;
  closesAt: string | null;
}

/**
 * Un día del horario de atención del local (portada). `dayOfWeek` es
 * 1 = lunes … 7 = domingo y `shifts` es la lista ORDENADA de turnos del día.
 * Un día con la lista vacía ES un día cerrado: ya no existe `closed`, que
 * podía contradecir a los horarios cargados.
 */
export interface BusinessHours {
  dayOfWeek: number;
  shifts: BusinessHoursShift[];
}

/**
 * Una fecha excepcional del calendario (feriado o día con horario especial),
 * tal como la sirve el backend en el perfil y en `business` del payload del
 * menú (`dateExceptions`, ordenada por fecha). `date` es "YYYY-MM-DD" y
 * `shifts` tiene la MISMA forma que los turnos de un día.
 *
 * La semántica es la INVERSA de `business_hours`: una fecha presente con
 * `shifts: []` ES un feriado cerrado, pero una fecha AUSENTE de la lista es
 * un día normal (rige la plantilla de su día de la semana) — no hace falta
 * declarar los 364 días restantes.
 */
export interface DateException {
  date: string;
  shifts: BusinessHoursShift[];
  /**
   * `true`: la excepción rige ese DÍA Y MES en todos los años (Navidad es 25/12
   * todos los años); `false` o ausente: sólo esa fecha exacta. Opcional a
   * propósito — payloads anteriores y filas de una sola vez no lo traen, y su
   * ausencia significa `false`. Una fecha exacta gana sobre una anual que
   * comparta mes/día, que es una combinación válida del modelo.
   */
  yearly?: boolean;
}

/**
 * Storefront (portada) del local, tal como lo sirve `GET /api/menu/{slug}`
 * dentro de `business`. Todo es opcional: un local sin portada no renderiza
 * nada nuevo en la carta.
 */
export interface StorefrontBusiness {
  id?: number | string;
  name?: string;
  slug?: string;
  whatsappNumber?: string | null;
  logoUrl?: string | null;
  bannerUrl?: string | null;
  address?: string | null;
  instagramUrl?: string | null;
  primaryColor?: string | null;
  hours?: BusinessHours[] | null;
  dateExceptions?: DateException[] | null;
}

export interface MenuResponse {
  business?: StorefrontBusiness | null;
  products?: Array<Omit<Product, "id" | "categoryId" | "categoryName" | "isCombo"> & {
    id: number | string;
    categoryId?: number | string;
    categoryName?: string;
  }>;
  combos?: Combo[];
}

export interface CartEntry {
  product: Product;
  qty: number;
  isCombo: boolean;
}

/** Legacy shape: object keyed by product id → { product, qty, isCombo }. */
export type Cart = Record<string, CartEntry>;

export type OrderType = "RETIRO" | "DELIVERY";
export type PaymentMethod = "EFECTIVO" | "TRANSFERENCIA";

export interface CategoryGroup {
  name: string;
  products: Product[];
}
