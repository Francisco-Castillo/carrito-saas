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
 * Un día del horario de atención del local (portada). `dayOfWeek` es
 * 1 = lunes … 7 = domingo; `opensAt`/`closesAt` son "HH:MM:SS" (null en días
 * cerrados). Convención del backend: `closesAt` MENOR que `opensAt` significa
 * que el cierre cae en la madrugada del día SIGUIENTE (viernes 11:00 → 00:30).
 */
export interface BusinessHours {
  dayOfWeek: number;
  opensAt?: string | null;
  closesAt?: string | null;
  closed?: boolean;
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
  hours?: BusinessHours[] | null;
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
