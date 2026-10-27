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

export interface ProductOption {
  id: number;
  name: string;
  priceDelta: number;
}

/**
 * Grupo de opciones del producto, tal como lo sirve ProductDTO en el menú
 * (odd/tasks/product-options.md S1/S2). required → el cliente debe elegir
 * antes de agregar al carrito (S4).
 */
export interface ProductOptionGroup {
  id?: number;
  name: string;
  required: boolean;
  multiple: boolean;
  minSelections?: number | null;
  maxSelections?: number | null;
  options: ProductOption[];
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
  optionGroups?: ProductOptionGroup[] | null;
}

export interface Restaurant {
  name?: string;
  logoUrl?: string;
  whatsappNumber?: string;
  primaryColor?: string;
  /** Slug de la fuente curada elegida por el dueño (o ausente: Inter). */
  menuFont?: string;
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
 * Sucursal activa del link público (multi-sucursal,
 * odd/tasks/multi-sucursal.md, S4/T8), tal como viaja en `branches` del
 * payload del menú: sólo id, nombre y dirección — la identidad pública es el
 * id NUMÉRICO (`?sucursal=<id>`), nunca un slug inventado. La lista viene
 * ordenada por id y sólo trae activas (el backend la arma así).
 */
export interface MenuBranch {
  id: number;
  name: string;
  address?: string | null;
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
  tiktokUrl?: string | null;
  primaryColor?: string | null;
  /** Slug de la fuente curada elegida por el dueño (o null: Inter). */
  menuFont?: string | null;
  hours?: BusinessHours[] | null;
  dateExceptions?: DateException[] | null;
}

export interface MenuResponse {
  business?: StorefrontBusiness | null;
  /**
   * Sucursales activas del negocio (multi-sucursal, S4/T8). Viaja HERMANA de
   * `business` en el payload del menú público; `Menu.tsx` la guarda para la
   * pantalla previa (BranchPicker) — la carta NUNCA muestra un control de
   * sucursal (T10). La carta (S2) es compartida: esta lista NO cambia
   * productos ni precios.
   */
  branches?: MenuBranch[] | null;
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
  /**
   * Opciones elegidas (S4): ids de ProductOption, ordenados por grupo.
   * Vacío/ausente = producto sin opciones (S6).
   */
  selectedOptionIds?: number[];
  /** Preferencias del cliente ("sin mayonesa"): opcional, máx 300. */
  notes?: string;
  /** Precio unitario con recargos, ya calculado al agregar. */
  unitPrice: number;
}

/** Legacy shape: object keyed by product id → { product, qty, isCombo }. */
export type Cart = Record<string, CartEntry>;

/**
 * Tipos de pedido del carrito público. `SALON` sólo aparece fijado por la
 * página cuando el QR de una mesa abrió la carta (`?table=<id>`): no es una
 * opción del selector — el usuario decidió "Fijar Salón" (feature
 * pedido-qr-mesa).
 */
export type OrderType = "RETIRO" | "DELIVERY" | "SALON";

/**
 * Identidad pública de una mesa, tal como la sirve
 * `GET /api/menu/table/{id}` (endpoint anónimo minimalista): lo justo para el
 * badge — sin `qrToken`, sin `qrUrl`, sin estado interno.
 */
export interface PublicTable {
  id: number;
  tableNumber: number;
  tableName: string;
}
export type PaymentMethod = "EFECTIVO" | "TRANSFERENCIA";

export interface CategoryGroup {
  name: string;
  products: Product[];
}
