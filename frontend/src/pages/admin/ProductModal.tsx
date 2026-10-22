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
  optionGroups?: AdminOptionGroup[] | null;
}

/**
 * Grupo de opciones de un producto (odd/tasks/product-options.md S1/S2).
 * Espejo de ProductOptionGroupDTO del backend: el dueño lo define por
 * producto, sin biblioteca compartida (S3).
 */
export interface AdminOptionGroup {
  id?: number | null;
  name: string;
  required: boolean;
  multiple: boolean;
  minSelections?: number | null;
  maxSelections?: number | null;
  options: AdminOption[];
}

export interface AdminOption {
  id?: number | null;
  name: string;
  priceDelta: number;
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

  // Grupos de opciones (S1/S2): el dueño los define acá, por producto.
  const [optionGroups, setOptionGroups] = useState<AdminOptionGroup[]>(
    product?.optionGroups?.map((g) => ({ ...g, options: g.options.map((o) => ({ ...o })) })) ?? [],
  );
  const [optionGroupsError, setOptionGroupsError] = useState<string | null>(null);

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

  /** Espejo de la validación del backend (T2): mismo mensaje, falla antes del fetch. */
  function validarGrupos(grupos: AdminOptionGroup[]): string | null {
    for (const g of grupos) {
      if (g.name.trim() === "") return "Cada grupo de opciones necesita nombre";
      if (g.options.length === 0)
        return `El grupo "${g.name.trim()}" necesita al menos una opción`;
      for (const o of g.options) {
        if (o.name.trim() === "")
          return `Cada opción del grupo "${g.name.trim()}" necesita nombre`;
        if (Number(o.priceDelta) < 0)
          return "El recargo de una opción no puede ser negativo";
      }
      if (g.multiple) {
        const min = g.minSelections ?? null;
        const max = g.maxSelections ?? null;
        if (min != null && min < 0)
          return "El mínimo de selecciones no puede ser negativo";
        if (g.required && (min == null || min < 1))
          return "Un grupo obligatorio y múltiple necesita mínimo 1 selección";
        if (max != null && max < 1)
          return "El máximo de selecciones tiene que ser al menos 1";
        if (min != null && max != null && min > max)
          return "El mínimo de selecciones no puede superar el máximo";
      }
    }
    return null;
  }

  function addGroup(): void {
    setOptionGroups((gs) => [
      ...gs,
      { name: "", required: true, multiple: false, minSelections: null, maxSelections: null, options: [] },
    ]);
  }

  function updateGroup(index: number, changes: Partial<AdminOptionGroup>): void {
    setOptionGroups((gs) => gs.map((g, i) => (i === index ? { ...g, ...changes } : g)));
  }

  function removeGroup(index: number): void {
    setOptionGroups((gs) => gs.filter((_, i) => i !== index));
  }

  function addOption(groupIndex: number): void {
    setOptionGroups((gs) =>
      gs.map((g, i) =>
        i === groupIndex ? { ...g, options: [...g.options, { name: "", priceDelta: 0 }] } : g,
      ),
    );
  }

  function updateOption(groupIndex: number, optionIndex: number, changes: Partial<AdminOption>): void {
    setOptionGroups((gs) =>
      gs.map((g, i) =>
        i === groupIndex
          ? { ...g, options: g.options.map((o, j) => (j === optionIndex ? { ...o, ...changes } : o)) }
          : g,
      ),
    );
  }

  function removeOption(groupIndex: number, optionIndex: number): void {
    setOptionGroups((gs) =>
      gs.map((g, i) => (i === groupIndex ? { ...g, options: g.options.filter((_, j) => j !== optionIndex) } : g)),
    );
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

    // Validación local de grupos (espejo del backend, T4): fallar antes del
    // fetch con un mensaje que nombra el grupo en problema.
    const groupError = validarGrupos(optionGroups);
    if (groupError) {
      setOptionGroupsError(groupError);
      setServerError(null);
      return;
    }
    setOptionGroupsError(null);
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
      // Grupos de opciones: siempre la lista completa (el backend reemplaza
      // el set entero; lista vacía = el dueño borró todos los grupos).
      optionGroups: optionGroups.map((g) => ({
        name: g.name,
        required: g.required,
        multiple: g.multiple,
        minSelections: g.multiple ? g.minSelections ?? null : null,
        maxSelections: g.multiple ? g.maxSelections ?? null : null,
        options: g.options.map((o) => ({ name: o.name, priceDelta: Number(o.priceDelta) || 0 })),
      })),
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
      <div className="toast-modal product-modal" role="dialog" aria-modal="true" aria-labelledby="product-modal-title">
        <h3 id="product-modal-title">{creating ? "Nuevo producto" : "Editar producto"}</h3>

        {serverError && (
          <p className="name-error" role="alert">
            {serverError}
          </p>
        )}

        <section className="pm-section">
          <h4 className="pm-section-title">Datos del producto</h4>
          <div className="admin-product-form">
          <div className="admin-product-form--full">
            <label className="pm-field">
              <span className="pm-label">Nombre</span>
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
            </label>
          </div>

          <div className="admin-product-form--full">
            <label className="pm-field">
              <span className="pm-label">Descripción</span>
              <textarea
              className={`admin-input${hasError(description) ? " admin-input--error" : ""}`}
              value={description}
              placeholder="Descripción"
              aria-label="Descripción"
              aria-invalid={hasError(description)}
              rows={2}
              onChange={(e) => setDescription(e.target.value)}
            />
            </label>
          </div>

          <label className="pm-field">
            <span className="pm-label">Precio de venta</span>
            <input
            className={`admin-input${hasError(price) ? " admin-input--error" : ""}`}
            type="number"
            min="0"
            step="0.01"
            value={price}
            placeholder="0.00"
            aria-label="Precio"
            aria-invalid={hasError(price)}
            onChange={(e) => setPrice(e.target.value)}
          />
          </label>

          <label className="pm-field">
            <span className="pm-label">Costo</span>
            <input
            className={`admin-input${hasError(cost) ? " admin-input--error" : ""}`}
            type="number"
            min="0"
            step="0.01"
            value={cost}
            placeholder="0.00"
            aria-label="Costo"
            aria-invalid={hasError(cost)}
            onChange={(e) => setCost(e.target.value)}
          />
          </label>

          <label className="pm-field">
            <span className="pm-label">Stock</span>
            <input
            className="admin-input"
            type="number"
            min="0"
            step="1"
            value={stock}
            placeholder="Vacío = ilimitado"
            aria-label="Stock"
            onChange={(e) => setStock(e.target.value)}
          />
          </label>

          <label className="pm-field">
            <span className="pm-label">Categoría</span>
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
          </label>

          <label className="pm-field">
            <span className="pm-label">Unidad de medida</span>
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
          </label>

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
        </section>

        <section className="pm-section">
          <h4 className="pm-section-title">Opciones del producto</h4>
          <div className="admin-product-options">
          <div className="admin-product-options--header">
            <p className="admin-image-hint">
              Ej: "Tipo de pan" (obligatorio, elegir 1) o "Extras" (opcional, elegir varios con recargo).
            </p>
            <button type="button" className="btn-ghost" onClick={addGroup}>
              + Agregar grupo
            </button>
          </div>
          {optionGroupsError && (
            <p className="name-error" role="alert">
              {optionGroupsError}
            </p>
          )}
          {optionGroups.map((group, gi) => (
            <fieldset key={gi} className="admin-option-group">
              <legend>Grupo {gi + 1}</legend>
              <div className="admin-option-group--row">
                <input
                  className="admin-input"
                  type="text"
                  value={group.name}
                  placeholder="Nombre del grupo (ej: Tipo de pan)"
                  aria-label={`Nombre del grupo ${gi + 1}`}
                  onChange={(e) => updateGroup(gi, { name: e.target.value })}
                />
                <button type="button" className="btn-ghost" onClick={() => removeGroup(gi)}>
                  Quitar grupo
                </button>
              </div>
              <div className="admin-option-group--row">
                <label className="admin-product-check">
                  <input
                    type="checkbox"
                    checked={group.required}
                    aria-label={`Grupo ${gi + 1} obligatorio`}
                    onChange={(e) => updateGroup(gi, { required: e.target.checked })}
                  />
                  <span>Obligatorio</span>
                </label>
                <label className="admin-product-check">
                  <input
                    type="checkbox"
                    checked={group.multiple}
                    aria-label={`Grupo ${gi + 1} permite varias opciones`}
                    onChange={(e) => updateGroup(gi, { multiple: e.target.checked })}
                  />
                  <span>Permite elegir varias</span>
                </label>
              </div>
              {group.multiple && (
                <div className="admin-option-group--row">
                  <input
                    className="admin-input"
                    type="number"
                    min="0"
                    step="1"
                    value={group.minSelections ?? ""}
                    placeholder="Mínimo de selecciones"
                    aria-label={`Mínimo del grupo ${gi + 1}`}
                    onChange={(e) =>
                      updateGroup(gi, { minSelections: e.target.value === "" ? null : Number(e.target.value) })
                    }
                  />
                  <input
                    className="admin-input"
                    type="number"
                    min="1"
                    step="1"
                    value={group.maxSelections ?? ""}
                    placeholder="Máximo (vacío = sin tope)"
                    aria-label={`Máximo del grupo ${gi + 1}`}
                    onChange={(e) =>
                      updateGroup(gi, { maxSelections: e.target.value === "" ? null : Number(e.target.value) })
                    }
                  />
                </div>
              )}
              {group.options.map((option, oi) => (
                <div key={oi} className="admin-option-group--row">
                  <input
                    className="admin-input"
                    type="text"
                    value={option.name}
                    placeholder="Nombre de la opción (ej: Integral)"
                    aria-label={`Opción ${oi + 1} del grupo ${gi + 1}`}
                    onChange={(e) => updateOption(gi, oi, { name: e.target.value })}
                  />
                  <input
                    className="admin-input"
                    type="number"
                    min="0"
                    step="0.01"
                    value={option.priceDelta}
                    placeholder="Recargo"
                    aria-label={`Recargo de la opción ${oi + 1} del grupo ${gi + 1}`}
                    onChange={(e) => updateOption(gi, oi, { priceDelta: Number(e.target.value) })}
                  />
                  <button
                    type="button"
                    className="btn-ghost"
                    aria-label={`Quitar opción ${oi + 1} del grupo ${gi + 1}`}
                    onClick={() => removeOption(gi, oi)}
                  >
                    ✕
                  </button>
                </div>
              ))}
              <button type="button" className="btn-ghost" onClick={() => addOption(gi)}>
                + Agregar opción
              </button>
            </fieldset>
          ))}
          </div>
        </section>

        <section className="pm-section">
          <h4 className="pm-section-title">Imagen</h4>
          <div className="admin-product-image">
          {imagePreview && (
            <img className="admin-product-image--preview" src={imagePreview} alt="Imagen del producto" />
          )}
          <div className="admin-product-image--controls">
            <label className="btn-ghost admin-product-image--pick">
              <span className="admin-product-image--pick-icon" aria-hidden="true">🖼️</span>
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
          <p className="admin-image-hint">JPG, PNG o WebP · hasta 5 MB · ideal 800×600 (4:3): en la carta se recorta al centro</p>
          {imageError && (
            <p className="name-error" role="alert">
              {imageError}
            </p>
          )}
          </div>
        </section>

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
