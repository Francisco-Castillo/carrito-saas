import { useCallback, useEffect, useRef, useState } from "react";
import { API, authHeader, type AdminCategory } from "./api";
import { showSuccessToast } from "./toast";
import ConfirmModal from "./ConfirmModal";

interface CategoriesTableProps {
  /** Bump para recargar categorías (tras crear una, como el loadCategories() del legacy). */
  refreshKey: number;
  /** Cantidad de filas cargadas (el legacy la leía del tbody al crear una categoría). */
  onRowCountChange?: (count: number | null) => void;
}

interface ConfirmState {
  message: string;
  resolve: (ok: boolean) => void;
}

/**
 * Puerto 1:1 de loadCategories del legacy admin/admin.js: listado con badge,
 * toggle activar/desactivar con confirmación, edición inline del nombre
 * (Enter guarda con confirmación, Escape/blur cancelan, vacío → alert),
 * reordenamiento por drag & drop que persiste el orden, y tfoot con el total.
 *
 * Única desviación: el drag & drop usa HTML5 nativo en vez de SortableJS (CDN)
 * para no sumar una dependencia; mismas promesas: mismos endpoints, mismo
 * payload (array de ids) y mismos toasts.
 */
export default function CategoriesTable({ refreshKey, onRowCountChange }: CategoriesTableProps) {
  const [categories, setCategories] = useState<AdminCategory[] | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [confirmState, setConfirmState] = useState<ConfirmState | null>(null);
  const [editing, setEditing] = useState<{ id: number; value: string } | null>(null);
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  const confirmResolveRef = useRef<((ok: boolean) => void) | null>(null);

  async function loadCategories() {
    try {
      const token = localStorage.getItem("token");
      const response = await fetch(`${API}/categorias`, {
        headers: { Authorization: "Bearer " + token },
      });

      if (response.status === 404) {
        setNotFound(true);
        return;
      }
      if (!response.ok) throw new Error("Error cargando categorías");

      const data = (await response.json()) as AdminCategory[];
      setCategories(data);
    } catch (e) {
      console.error("Error cargando categorías", e);
    }
  }

  useEffect(() => {
    void loadCategories();
  }, [refreshKey]);

  const reportCount = useCallback(
    (count: number | null) => onRowCountChange?.(count),
    [onRowCountChange],
  );

  useEffect(() => {
    reportCount(notFound ? null : (categories?.length ?? null));
  }, [notFound, categories, reportCount]);

  useEffect(() => {
    // Si el componente se desmonta con una confirmación pendiente, resolverla.
    return () => confirmResolveRef.current?.(false);
  }, []);

  /** showToastModal del legacy: promesa que responde true solo con "Confirmar". */
  function confirmAsync(message: string): Promise<boolean> {
    return new Promise((resolve) => {
      confirmResolveRef.current = resolve;
      setConfirmState({ message, resolve });
    });
  }

  async function persistOrder(ids: number[]) {
    try {
      const res = await fetch(`${API}/categorias/orden`, {
        method: "PUT",
        headers: {
          "Content-Type": "application/json",
          ...authHeader(),
        },
        body: JSON.stringify(ids),
      });
      if (!res.ok) throw new Error("Error actualizando el orden");
      showSuccessToast("Orden de categorías actualizado");
    } catch (e) {
      console.error(e);
      showSuccessToast("Error al actualizar el orden");
    }
  }

  async function handleToggle(c: AdminCategory) {
    const activar = !c.active;

    // El legacy usa el nombre visible recortado y el orden = posición en la tabla.
    const name = c.name.trim();
    const order = (categories?.findIndex((x) => x.id === c.id) ?? -1) + 1;
    const payload = { name, order, active: activar };

    const ok = await confirmAsync(
      `¿Seguro que deseas ${activar ? "activar" : "desactivar"} esta categoría?`,
    );
    if (!ok) return;

    try {
      const res = await fetch(`${API}/categorias/${c.id}`, {
        method: "PUT",
        headers: {
          "Content-Type": "application/json",
          ...authHeader(),
        },
        body: JSON.stringify(payload),
      });

      const data = await res.json();
      if (!res.ok) throw new Error(data.message || "Error actualizando categoría");

      // El legacy actualiza badge y botón sin recargar toda la tabla.
      setCategories((prev) =>
        prev ? prev.map((x) => (x.id === c.id ? { ...x, active: activar } : x)) : prev,
      );
      showSuccessToast(`Categoría ${activar ? "activada" : "desactivada"} con éxito`);
    } catch (err) {
      showSuccessToast(`Error: ${(err as Error).message}`);
    }
  }

  async function handleRename(id: number, rawValue: string) {
    const newName = rawValue.trim();
    if (!newName) {
      window.alert("El nombre no puede estar vacío");
      return;
    }

    const ok = await confirmAsync(`¿Guardar nuevo nombre "${newName}"?`);
    if (!ok) {
      setEditing(null);
      return;
    }

    // Sin manejo de error, exactamente como el legacy (admin.js:432-443).
    void fetch(`${API}/categorias/${id}`, {
      method: "PUT",
      headers: {
        "Content-Type": "application/json",
        ...authHeader(),
      },
      body: JSON.stringify({ name: newName }),
    });

    void loadCategories();
    showSuccessToast("Nombre actualizado correctamente");
  }

  function handleDrop(targetIndex: number) {
    if (dragIndex === null || dragIndex === targetIndex) {
      setDragIndex(null);
      return;
    }
    const next = [...(categories ?? [])];
    const [moved] = next.splice(dragIndex, 1);
    next.splice(targetIndex, 0, moved);
    setDragIndex(null);
    setCategories(next);
    void persistOrder(next.map((c) => c.id));
  }

  function cancelConfirm(ok: boolean) {
    confirmResolveRef.current?.(ok);
    confirmResolveRef.current = null;
    setConfirmState(null);
  }

  if (notFound) {
    return <h1 className="admin-not-found">Categorías no encontradas</h1>;
  }
  if (categories === null) return null;

  return (
    <>
      <div className="glass-panel fade-up admin-table-panel">
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th>Nombre</th>
                <th>Estado</th>
                <th>Acciones</th>
              </tr>
            </thead>
            <tbody>
              {categories.map((c, index) => (
                <tr
                  key={c.id}
                  data-id={c.id}
                  draggable={dragIndex === index}
                  onDragStart={() => setDragIndex(index)}
                  onDragOver={(e) => e.preventDefault()}
                  onDrop={() => handleDrop(index)}
                  onDragEnd={() => setDragIndex(null)}
                >
                  <td>
                    <span
                      className="drag-handle"
                      role="button"
                      aria-label={`Reordenar ${c.name}`}
                      onMouseDown={() => setDragIndex(index)}
                      onTouchStart={() => setDragIndex(index)}
                    >
                      ≡
                    </span>
                    {editing?.id === c.id ? (
                      <input
                        className="inline-input"
                        type="text"
                        value={editing.value}
                        aria-label={`Nuevo nombre para ${c.name}`}
                        onChange={(e) => setEditing({ id: c.id, value: e.target.value })}
                        onKeyDown={(e) => {
                          if (e.key === "Enter") {
                            void handleRename(c.id, editing.value);
                          }
                          if (e.key === "Escape") {
                            setEditing(null);
                          }
                        }}
                        onBlur={() => setEditing(null)}
                        autoFocus
                      />
                    ) : (
                      <span
                        className="name-text"
                        role="button"
                        tabIndex={0}
                        onClick={() => setEditing({ id: c.id, value: c.name })}
                        onKeyDown={(e) => {
                          if (e.key === "Enter") setEditing({ id: c.id, value: c.name });
                        }}
                      >
                        {c.name}
                      </span>
                    )}
                  </td>
                  <td>
                    <span className={`badge ${c.active ? "active" : "inactive"}`}>
                      {c.active ? "Activo" : "Inactivo"}
                    </span>
                  </td>
                  <td>
                    <button
                      type="button"
                      className={`toggle-btn ${c.active ? "deactivate" : "activate"}`}
                      onClick={() => void handleToggle(c)}
                    >
                      {c.active ? "Desactivar" : "Activar"}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <td colSpan={3}>Total de registros: {categories.length}</td>
              </tr>
            </tfoot>
          </table>
        </div>
      </div>

      {confirmState && (
        <ConfirmModal
          message={confirmState.message}
          onConfirm={() => cancelConfirm(true)}
          onCancel={() => cancelConfirm(false)}
        />
      )}
    </>
  );
}
