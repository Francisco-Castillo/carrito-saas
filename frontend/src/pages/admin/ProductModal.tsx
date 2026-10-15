import { useEffect, useRef, useState } from "react";
import { API, authHeader, type AdminCategory, type AdminProduct } from "./api";
import { showSuccessToast } from "./toast";

/**
 * El ProductDTO completo que el backend sirve en GET /api/products (con
 * descripción, costo, categoría y unidad) pero que la tabla no usa: la tabla
 * lista lo mínimo y el modal de edición necesita el resto para el prefill.
 * Vive acá (y no en api.ts) porque sólo el modal lo consume por ahora.
 */
export interface AdminProductFull extends AdminProduct {
  description?: string | null;
  cost?: number | null;
  categoryId?: number | null;
  unitMeasure?: string | null;
}

interface ProductModalProps {
  /** null = crear (POST /api/products); con producto = editar (PUT /api/products/{id}). */
  product: AdminProductFull | null;
  onClose: () => void;
  /** Guardado con éxito: el padre cierra el modal y recarga la lista. */
  onSaved: () => void;
}

/** Enum UnitMeasure del backend (repository/enums/UnitMeasure.java) con copy en español. */
const UNIT_MEASURES: Array<{ value: string; label: string }> = [
  { value: "UNIDAD", label: "Unidad" },
  { value: "KILOGRAMO", label: "Kilogramo (kg)" },
  { value: "MILILITRO", label: "Mililitro (ml)" },
  { value: "LITRO", label: "Litro (l)" },
  { value: "PORCION", label: "Porción" },
  { value: "PACK", label: "Pack" },
  { value: "DOCENA", label: "Docena" },
];

/**
 * Modal de crear/editar producto, mismo patrón que CreateCategoryModal:
 * toast-modal-backdrop + toast-modal + role="dialog", cierre con Escape o
 * clic fuera, admin-input y btn-primary. El servicio exige nombre,
 * descripción, precio (≥ 0), costo y categoría (validado también del lado
 * del cliente con borde rojo + mensaje); los 400 del backend muestran su
 * `message` en español INLINE en el modal (como el formulario de perfil) y
 * el modal queda abierto, en vez de un toast genérico.
 */
export default function ProductModal({ product, onClose, onSaved }: ProductModalProps) {
  const creating = product === null;

  const [name, setName] = useState(product?.name ?? "");
  const [description, setDescription] = useState(product?.description ?? "");
  const [price, setPrice] = useState(product?.price != null ? String(product.price) : "");
  const [cost, setCost] = useState(product?.cost != null ? String(product.cost) : "");
  const [stock, setStock] = useState(product?.stock != null ? String(product.stock) : "");
  const [categoryId, setCategoryId] = useState(
    product?.categoryId != null ? String(product.categoryId) : "",
  );
  const [unitMeasure, setUnitMeasure] = useState(product?.unitMeasure ?? "");
  const [active, setActive] = useState(product?.active ?? true);

  const [categories, setCategories] = useState<AdminCategory[]>([]);
  const [missingFields, setMissingFields] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const nameRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    nameRef.current?.focus();

    function escHandler(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    document.addEventListener("keydown", escHandler);
    return () => document.removeEventListener("keydown", escHandler);
  }, [onClose]);

  useEffect(() => {
    // Categorías para el select: mismo endpoint owner que CategoriesTable.
    let cancelled = false;
    (async () => {
      try {
        const res = await fetch(`${API}/categorias`, { headers: authHeader() });
        if (!res.ok) return;
        const data = (await res.json()) as AdminCategory[];
        if (!cancelled) setCategories(data);
      } catch {
        // Sin categorías cargables el select queda vacío y el backend valida.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  function hasError(value: string): boolean {
    return missingFields && value.trim() === "";
  }

  async function handleSave() {
    const trimmedName = name.trim();
    const missing =
      trimmedName === "" ||
      description.trim() === "" ||
      price === "" ||
      cost === "" ||
      categoryId === "";

    if (missing) {
      setMissingFields(true);
      setServerError(null);
      return;
    }
    setMissingFields(false);
    setServerError(null);
    setSaving(true);

    // ProductCreateDTO (create) / ProductDTO (edit): números, no strings.
    const payload = {
      categoryId: Number(categoryId),
      name: trimmedName,
      description: description.trim(),
      price: Number(price),
      cost: Number(cost),
      stock: stock === "" ? null : Number(stock),
      active,
      unitMeasure: unitMeasure === "" ? null : unitMeasure,
      ...(creating ? {} : { id: product!.id }),
    };

    try {
      const res = await fetch(creating ? `${API}/products` : `${API}/products/${product!.id}`, {
        method: creating ? "POST" : "PUT",
        headers: {
          "Content-Type": "application/json",
          ...authHeader(),
        },
        body: JSON.stringify(payload),
      });

      if (!res.ok) {
        // 400 del servicio: mensaje en español del backend, inline en el modal.
        const data = (await res.json().catch(() => null)) as { message?: string } | null;
        setServerError(data?.message || "Error guardando el producto");
        return;
      }

      showSuccessToast(creating ? "Producto creado con éxito" : "Producto actualizado con éxito");
      onSaved();
    } catch (e) {
      setServerError((e as Error).message);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div
      className="toast-modal-backdrop"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="toast-modal" role="dialog" aria-modal="true" aria-labelledby="product-modal-title">
        <h3 id="product-modal-title">{creating ? "Nuevo producto" : "Editar producto"}</h3>

        {serverError && (
          <p className="name-error" role="alert">
            {serverError}
          </p>
        )}

        <div className="admin-product-form">
          <div className="admin-product-form--full">
            <input
              ref={nameRef}
              className={`admin-input${hasError(name) ? " admin-input--error" : ""}`}
              type="text"
              value={name}
              placeholder="Nombre del producto"
              aria-label="Nombre"
              aria-invalid={hasError(name)}
              onChange={(e) => setName(e.target.value)}
            />
          </div>

          <div className="admin-product-form--full">
            <textarea
              className={`admin-input${hasError(description) ? " admin-input--error" : ""}`}
              value={description}
              placeholder="Descripción"
              aria-label="Descripción"
              aria-invalid={hasError(description)}
              rows={2}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>

          <input
            className={`admin-input${hasError(price) ? " admin-input--error" : ""}`}
            type="number"
            min="0"
            step="0.01"
            value={price}
            placeholder="Precio de venta"
            aria-label="Precio"
            aria-invalid={hasError(price)}
            onChange={(e) => setPrice(e.target.value)}
          />

          <input
            className={`admin-input${hasError(cost) ? " admin-input--error" : ""}`}
            type="number"
            min="0"
            step="0.01"
            value={cost}
            placeholder="Costo"
            aria-label="Costo"
            aria-invalid={hasError(cost)}
            onChange={(e) => setCost(e.target.value)}
          />

          <input
            className="admin-input"
            type="number"
            min="0"
            step="1"
            value={stock}
            placeholder="Stock (vacío = ilimitado)"
            aria-label="Stock"
            onChange={(e) => setStock(e.target.value)}
          />

          <select
            className={`admin-input${hasError(categoryId) ? " admin-input--error" : ""}`}
            value={categoryId}
            aria-label="Categoría"
            aria-invalid={hasError(categoryId)}
            onChange={(e) => setCategoryId(e.target.value)}
          >
            <option value="">Seleccioná una categoría</option>
            {categories.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>

          <select
            className="admin-input"
            value={unitMeasure}
            aria-label="Unidad de medida"
            onChange={(e) => setUnitMeasure(e.target.value)}
          >
            <option value="">Sin unidad de medida</option>
            {UNIT_MEASURES.map((u) => (
              <option key={u.value} value={u.value}>
                {u.label}
              </option>
            ))}
          </select>

          <label className="admin-product-check">
            <input
              type="checkbox"
              checked={active}
              aria-label="Producto activo"
              onChange={(e) => setActive(e.target.checked)}
            />
            <span>Producto activo (visible en la carta)</span>
          </label>
        </div>

        <div className="toast-modal-actions admin-product-form-actions">
          <button type="button" className="btn-ghost" onClick={onClose}>
            Cancelar
          </button>
          <button type="button" className="btn-primary" onClick={() => void handleSave()} disabled={saving}>
            Guardar
          </button>
        </div>
      </div>
    </div>
  );
}
