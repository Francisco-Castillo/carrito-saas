import { useEffect, useRef, useState, type ChangeEvent } from "react";
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
  imageUrl?: string | null;
}

interface ProductModalProps {
  /** null = crear (POST /api/products); con producto = editar (PUT /api/products/{id}). */
  product: AdminProductFull | null;
  onClose: () => void;
  /** Guardado con éxito: el padre cierra el modal y recarga la lista. */
  onSaved: () => void;
}

/** Formato de imagen aceptado por el backend (ImageStorageService). */
const IMAGE_ACCEPT = "image/jpeg,image/png,image/webp";
/** Techo duro espejo del backend: 5 MB. */
const IMAGE_MAX_BYTES = 5 * 1024 * 1024;

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

  /** Archivo nuevo elegido; mientras no se guarde, la preview lo muestra. */
  const [imageFile, setImageFile] = useState<File | null>(null);
  /** URL de la preview: la actual del producto o un blob: del archivo nuevo. */
  const [imagePreview, setImagePreview] = useState<string | null>(product?.imageUrl ?? null);
  /** true cuando el usuario pidió quitar la imagen existente. */
  const [imageRemoved, setImageRemoved] = useState(false);
  const [imageError, setImageError] = useState<string | null>(null);

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
    return () => {
      document.removeEventListener("keydown", escHandler);
      // La preview blob: del archivo elegido deja un ObjectURL colgado.
      if (imagePreview?.startsWith("blob:")) URL.revokeObjectURL(imagePreview);
    };
  }, [onClose, imagePreview]);

  function handleImageChange(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0] ?? null;
    if (!file) return;

    if (!IMAGE_ACCEPT.split(",").includes(file.type)) {
      setImageError("Formato no soportado: usá JPG, PNG o WebP");
      e.target.value = "";
      return;
    }
    if (file.size > IMAGE_MAX_BYTES) {
      setImageError("La imagen supera el máximo de 5 MB");
      e.target.value = "";
      return;
    }

    setImageError(null);
    setImageRemoved(false);
    setImageFile(file);
    setImagePreview((prev) => {
      if (prev?.startsWith("blob:")) URL.revokeObjectURL(prev);
      return URL.createObjectURL(file);
    });
  }

  function handleImageRemove() {
    setImagePreview((prev) => {
      if (prev?.startsWith("blob:")) URL.revokeObjectURL(prev);
      return null;
    });
    setImageFile(null);
    setImageRemoved(true);
    setImageError(null);
  }

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

      const saved = (await res.json()) as AdminProductFull;

      // El producto ya quedó guardado: la imagen viaja después (subir nueva
      // o borrar la existente). Un fallo de imagen no revierte el guardado:
      // se avisa inline y el padre recarga la lista igual.
      if (imageFile || imageRemoved) {
        await syncImage(saved.id);
      }

      showSuccessToast(creating ? "Producto creado con éxito" : "Producto actualizado con éxito");
      onSaved();
    } catch (e) {
      setServerError((e as Error).message);
    } finally {
      setSaving(false);
    }
  }

  /** Sube (POST multipart) o borra (DELETE) la imagen del producto ya guardado. */
  async function syncImage(productId: number): Promise<void> {
    const url = `${API}/products/${productId}/image`;
    try {
      const res = imageFile
        ? await fetch(url, {
            method: "POST",
            headers: authHeader(),
            body: toFormData(imageFile),
          })
        : await fetch(url, { method: "DELETE", headers: authHeader() });
      if (!res.ok) {
        const data = (await res.json().catch(() => null)) as { message?: string } | null;
        setImageError(data?.message || "No se pudo guardar la imagen");
      }
    } catch {
      setImageError("No se pudo guardar la imagen");
    }
  }

  function toFormData(file: File): FormData {
    const fd = new FormData();
    fd.append("file", file);
    return fd;
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

        <div className="admin-product-image">
          {imagePreview && (
            <img className="admin-product-image--preview" src={imagePreview} alt="Imagen del producto" />
          )}
          <div className="admin-product-image--controls">
            <label className="btn-ghost admin-product-image--pick">
              {imagePreview ? "Cambiar imagen" : "Agregar imagen"}
              <input
                type="file"
                accept={IMAGE_ACCEPT}
                aria-label="Imagen del producto"
                onChange={handleImageChange}
              />
            </label>
            {imagePreview && (
              <button type="button" className="btn-ghost" onClick={handleImageRemove}>
                Quitar imagen
              </button>
            )}
          </div>
          <p className="admin-product-image--hint">JPG, PNG o WebP · hasta 5 MB</p>
          {imageError && (
            <p className="name-error" role="alert">
              {imageError}
            </p>
          )}
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
