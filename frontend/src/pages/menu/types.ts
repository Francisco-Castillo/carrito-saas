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

export interface MenuResponse {
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
