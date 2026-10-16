import { useEffect, useRef, useState } from "react";
import { API, authHeader, type AdminCategory, type AdminProduct } from "./api";
import { showSuccessToast } from "./toast";

/**
 * El ComboDTO completo que el backend sirve en GET /api/combos (con categoría
 * e items) pero que la tabla no usa: el modal de edición necesita el resto
 * para el prefill. Vive acá (y no en api.ts) por la misma razón que
 * AdminProductFull: sólo el modal lo consume por ahora.
 *
 * El ComboItemDTO del backend trae productId además de productName y
 * quantity, así que el prefill de items cruza por id (dos productos pueden
 * compartir nombre: el id es lo único que los distingue). Si una respuesta
 * vieja no trae productId, se cae al cruce por nombre.
 */
export interface AdminComboFull {
  id: number;
  name: string;
  price: number;
  categoryId?: number | null;
  /** Estado on/off del combo: lo mapea IComboMapperImpl desde el backend;
   * la tabla lo muestra en el switch y el modal no lo consume. */
  active?: boolean;
  items?: Array<{ productId?: number; productName: string; quantity: number }> | null;
}

/** Una fila del picker de productos: producto elegido + cantidad. */
interface PickerRow {
  productId: string;
  quantity: string;
}

interface ComboModalProps {
  /** null = crear (POST /api/combos); con combo = editar (PUT /api/combos/{id}). */
  combo: AdminComboFull | null;
  onClose: () => void;
  /** Guardado con éxito: el padre cierra el modal y recarga la lista. */
  onSaved: () => void;
}

/**
 * Modal de crear/editar combo, mismo patrón que ProductModal: toast-modal +
 * role="dialog", cierre con Escape o clic fuera, admin-input y btn-primary.
 * Campos: nombre, precio, categoría (GET /api/categorias) y un picker de
 * productos con cantidades (GET /api/products): cada fila es un producto más
 * una cantidad, con alta y baja de filas. Los 400 del backend muestran su
 * `message` en español INLINE en el modal y el modal queda abierto.
 */
export default function ComboModal({ combo, onClose, onSaved }: ComboModalProps) {
  const creating = combo === null;

  const [name, setName] = useState(combo?.name ?? "");
  const [price, setPrice] = useState(combo?.price != null ? String(combo.price) : "");
  const [categoryId, setCategoryId] = useState(
    combo?.categoryId != null ? String(combo.categoryId) : "",
  );
  const [rows, setRows] = useState<PickerRow[]>([]);
  const [prefilled, setPrefilled] = useState(false);

  const [categories, setCategories] = useState<AdminCategory[]>([]);
  const [products, setProducts] = useState<AdminProduct[]>([]);
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
    // Categorías y productos para los selects: mismos endpoints owner que
    // ProductModal y ProductsTable.
    let cancelled = false;
    (async () => {
      try {
        const [catRes, prodRes] = await Promise.all([
          fetch(`${API}/categorias`, { headers: authHeader() }),
          fetch(`${API}/products`, { headers: authHeader() }),
        ]);
        if (catRes.ok) {
          const cats = (await catRes.json()) as AdminCategory[];
          if (!cancelled) setCategories(cats);
        }
        if (prodRes.ok) {
          const prods = (await prodRes.json()) as AdminProduct[];
          if (!cancelled) setProducts(prods);
        }
      } catch {
        // Sin catálogos cargables los selects quedan vacíos y el backend valida.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    // Prefill de items UNA vez que la lista de productos está disponible:
    // el cruce es EXCLUSIVAMENTE por productId, que el backend manda siempre
    // (ComboItemDTO desde admin-combo-item-product-id). Un item sin id no se
    // adivina por nombre: con nombres repetidos el pick silencioso puede
    // caer en el producto equivocado y el operador no se enteraría. La fila
    // nace vacía y el operador elige.
    if (prefilled || products.length === 0 || !combo?.items?.length) return;
    const seeded: PickerRow[] = [];
    for (const item of combo.items) {
      seeded.push({
        productId: item.productId != null ? String(item.productId) : "",
        quantity: String(item.quantity ?? 1),
      });
    }
    setRows(seeded);
    setPrefilled(true);
  }, [products, combo, prefilled]);

  function hasError(value: string): boolean {
    return missingFields && value.trim() === "";
  }

  function addRow() {
    setRows((prev) => [...prev, { productId: "", quantity: "1" }]);
  }

  function removeRow(index: number) {
    setRows((prev) => prev.filter((_, i) => i !== index));
  }

  function updateRow(index: number, patch: Partial<PickerRow>) {
    setRows((prev) => prev.map((row, i) => (i === index ? { ...row, ...patch } : row)));
  }

  async function handleSave() {
    const trimmedName = name.trim();
    const missing =
      trimmedName === "" ||
      price === "" ||
      categoryId === "" ||
      rows.length === 0 ||
      rows.some((r) => r.productId === "");

    if (missing) {
      setMissingFields(true);
      setServerError(null);
      return;
    }
    setMissingFields(false);
    setServerError(null);
    setSaving(true);

    // ComboCreateDTO (create y update): números, no strings.
    const payload = {
      name: trimmedName,
      price: Number(price),
      categoryId: Number(categoryId),
      products: rows.map((r) => ({ productId: Number(r.productId), quantity: Number(r.quantity || 1) })),
    };

    try {
      const res = await fetch(creating ? `${API}/combos` : `${API}/combos/${combo!.id}`, {
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
        setServerError(data?.message || "Error guardando el combo");
        return;
      }

      showSuccessToast(creating ? "Combo creado con éxito" : "Combo actualizado con éxito");
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
      <div className="toast-modal" role="dialog" aria-modal="true" aria-labelledby="combo-modal-title">
        <h3 id="combo-modal-title">{creating ? "Nuevo combo" : "Editar combo"}</h3>

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
              placeholder="Nombre del combo"
              aria-label="Nombre"
              aria-invalid={hasError(name)}
              onChange={(e) => setName(e.target.value)}
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
        </div>

        {/* Picker de productos con cantidades: cada fila es un producto del
            negocio más su cantidad. Alta con "+ Agregar producto", baja con
            el botón de quitar de cada fila. */}
        <div className="combo-picker">
          {rows.map((row, index) => (
            <div key={index} className="combo-picker-row">
              <select
                className={`admin-input${missingFields && row.productId === "" ? " admin-input--error" : ""}`}
                value={row.productId}
                aria-label="Producto del combo"
                aria-invalid={missingFields && row.productId === ""}
                onChange={(e) => updateRow(index, { productId: e.target.value })}
              >
                <option value="">Seleccioná un producto</option>
                {products.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
              <input
                className="admin-input combo-picker-quantity"
                type="number"
                min="1"
                step="1"
                value={row.quantity}
                aria-label="Cantidad"
                onChange={(e) => updateRow(index, { quantity: e.target.value })}
              />
              <button
                type="button"
                className="btn-ghost"
                aria-label="Quitar producto"
                onClick={() => removeRow(index)}
              >
                🗑
              </button>
            </div>
          ))}
          {missingFields && rows.length === 0 && (
            <p className="name-error" role="alert">
              El combo requiere al menos un producto
            </p>
          )}
          <button type="button" className="btn-ghost combo-picker-add" onClick={addRow}>
            + Agregar producto
          </button>
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
