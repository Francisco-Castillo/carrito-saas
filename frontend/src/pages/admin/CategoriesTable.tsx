import { useCallback, useEffect, useRef, useState } from "react";
import { API, authHeader, type AdminCategory } from "./api";
import LoadingPanel from "./LoadingPanel";
import { showSuccessToast } from "./toast";
import ConfirmModal from "./ConfirmModal";
import { useConfirmDialog } from "./useConfirmDialog";

interface CategoriesTableProps {
  /** Bump para recargar categorías (tras crear una, como el loadCategories() del legacy). */
  refreshKey: number;
  /** Cantidad de filas cargadas (el legacy la leía del tbody al crear una categoría). */
  onRowCountChange?: (count: number | null) => void;
}

/**
 * El catálogo es en español: el buscador tiene que encontrar "Bebidas Frías"
 * cuando se escribe "BEBIDAS FRIAS". Mismo helper que ProductsTable (NFD
 * separa el diacrítico en un combining mark y el rango U+0300–U+036F lo
 * elimina; lowercase para el caso).
 */
function normalize(text: string): string {
  return text
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase();
}

/**
 * Listado de categorías del panel admin contra los endpoints owner:
 * GET /api/categorias (scoped por el negocio del JWT), PUT /api/categorias/{id}
 * para renombrar y cambiar estado, DELETE /api/categorias/{id} para eliminar
 * (409 si todavía tiene productos: el mensaje del backend se muestra en el
 * toast) y PUT /api/categorias/orden para reordenar.
 *
 * Unidad 3 del ODD (admin-panel-ui): buscador + edición descubrible + switch.
 * El buscador filtra EN CLIENTE sobre la lista ya cargada (misma piel de
 * toolbar que la tabla de productos: clases products-toolbar en Admin.css) y
 * mientras filtra se suspende el drag & drop: los índices de la vista filtrada
 * no corresponden con el orden real de la lista.
 *
 * El renombrado inline del legacy se conserva (una categoría sólo tiene
 * nombre/orden/activo: el orden es drag & drop y el activo es el switch, un
 * modal no aportaría nada) — lo que faltaba era la visibilidad: ahora hay un
 * lápiz en Acciones que abre la misma edición inline prefilled, y un 400 del
 * backend se muestra INLINE junto al input (role="alert") en vez del alert()
 * del legacy o de fallar en silencio. El toggle del legacy era un botón
 * "Desactivar" con confirmación: ahora es el mismo switch real (role="switch",
 * aria-checked, nombre accesible que dice qué hace) que la tabla de productos,
 * disparando el estado DESTINO sin confirmación.
 *
 * Dos desvíaciones deliberadas del legacy (reportadas al ODD): el renombrado
 * ya NO pide confirmación — la edición de productos no la pide, y el modal de
 * confirmación sobre un Enter era una trampa: la misma tecla que lo abría
 * activaba su botón enfocado. Y el vacío ya no va a window.alert: el error
 * queda inline como los demás.
 */
export default function CategoriesTable({ refreshKey, onRowCountChange }: CategoriesTableProps) {
  const [categories, setCategories] = useState<AdminCategory[] | null>(null);
  // Primera carga en curso: skeleton del dashboard mientras la API responde.
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const [query, setQuery] = useState("");
  const [editing, setEditing] = useState<{ id: number; value: string } | null>(null);
  const [editError, setEditError] = useState<string | null>(null);
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  /** Guardia de blur: mientras el PUT está en vuelo el input no se cierra, si
   * el 400 tiene dónde mostrarse. */
  const savingRef = useRef(false);
  /** Confirmación de delete: hook compartido por las tres tablas (la promesa
   * resuelve false también si el componente se desmonta con el confirm
   * abierto). */
  const { confirmState, confirmAsync, settleConfirm } = useConfirmDialog();

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
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadCategories();
  }, [refreshKey]);

  async function deleteCategory(c: AdminCategory) {
    const ok = await confirmAsync(`¿Seguro que deseas eliminar la categoría "${c.name}"?`);
    if (!ok) return;

    try {
      const res = await fetch(`${API}/categorias/${c.id}`, {
        method: "DELETE",
        headers: authHeader(),
      });

      if (res.status === 409) {
        // La categoría todavía tiene productos: el backend manda un mensaje
        // legible que nombra al bloqueador.
        const data = (await res.json().catch(() => null)) as { message?: string } | null;
        showSuccessToast(`Error: ${data?.message || "La categoría tiene productos asociados"}`);
        return;
      }
      if (!res.ok) throw new Error("Error eliminando la categoría");

      showSuccessToast("Categoría eliminada con éxito");
      void loadCategories();
    } catch (e) {
      showSuccessToast(`Error: ${(e as Error).message}`);
    }
  }

  const reportCount = useCallback(
    (count: number | null) => onRowCountChange?.(count),
    [onRowCountChange],
  );

  useEffect(() => {
    reportCount(notFound ? null : (categories?.length ?? null));
  }, [notFound, categories, reportCount]);

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

    // El backend actualiza sólo los campos no-null: se mandan nombre y orden
    // actuales + el estado DESTINO (igual que el switch de productos).
    const payload = { name: c.name.trim(), order: (categories?.findIndex((x) => x.id === c.id) ?? -1) + 1, active: activar };

    try {
      const res = await fetch(`${API}/categorias/${c.id}`, {
        method: "PUT",
        headers: {
          "Content-Type": "application/json",
          ...authHeader(),
        },
        body: JSON.stringify(payload),
      });

      if (!res.ok) {
        const data = (await res.json().catch(() => null)) as { message?: string } | null;
        throw new Error(data?.message || "Error actualizando categoría");
      }

      // El legacy actualizaba badge y botón sin recargar toda la tabla.
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
      // Inline, como los demás errores del panel (antes: window.alert).
      setEditError("El nombre no puede estar vacío");
      return;
    }

    // El backend hace update parcial (sólo campos no-null): el payload del
    // legacy ({name}) basta y no pisa orden ni estado. Sin confirmación
    // previa (ver comentario de la clase).
    savingRef.current = true;
    try {
      const res = await fetch(`${API}/categorias/${id}`, {
        method: "PUT",
        headers: {
          "Content-Type": "application/json",
          ...authHeader(),
        },
        body: JSON.stringify({ name: newName }),
      });

      if (!res.ok) {
        // 400 del servicio: mensaje en español del backend, inline en la fila.
        const data = (await res.json().catch(() => null)) as { message?: string } | null;
        setEditError(data?.message || "Error actualizando la categoría");
        return;
      }

      setEditing(null);
      setEditError(null);
      showSuccessToast("Nombre actualizado correctamente");
      void loadCategories();
    } catch (err) {
      setEditError((err as Error).message);
    } finally {
      savingRef.current = false;
    }
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

  if (notFound) {
    return <h1 className="admin-not-found">Categorías no encontradas</h1>;
  }
  // Carga fallida (API error): la vista no desaparece — el panel se muestra
  // con mensaje de error en la tabla. Mismo hallazgo/criterio que Products.
  const list = categories ?? [];

  // Filtro en cliente sobre la lista ya cargada (accent- y case-insensitive).
  const needle = normalize(query.trim());
  const filtered = needle === "" ? list : list.filter((c) => normalize(c.name).includes(needle));

  // Primera carga: skeleton en vez de una lista vacía. Con notFound/loading
  // false cae al panel de not-found de arriba.
  if (loading && categories === null) {
    return <LoadingPanel label="Cargando categorías…" lines={6} />;
  }

  return (
    <>
      <div className="glass-panel fade-up admin-table-panel">
        {/* Misma piel de toolbar que la tabla de productos (clases compartidas
            en Admin.css): los dos catálogos tienen que verse como hermanos. */}
        <div className="products-toolbar">
          <input
            type="search"
            className="admin-input products-toolbar-search"
            placeholder="Buscar categorías…"
            aria-label="Buscar categorías por nombre"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>

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
              {filtered.length === 0 && (
                <tr>
                  <td colSpan={3} className="admin-empty-state">
                    {categories === null
                      ? "No se pudieron cargar las categorías. Reintentá recargando la página."
                      : categories.length === 0
                        ? 'Todavía no hay categorías. Creá la primera con "+ Nuevo".'
                        : "No hay categorías que coincidan con la búsqueda"}
                  </td>
                </tr>
              )}
              {filtered.map((c, index) => (
                <tr
                  key={c.id}
                  data-id={c.id}
                  draggable={needle === "" && dragIndex === index}
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
                      <>
                        <input
                          className="inline-input"
                          type="text"
                          value={editing.value}
                          aria-label={`Nuevo nombre para ${c.name}`}
                          aria-invalid={editError !== null}
                          onChange={(e) => {
                            setEditing({ id: c.id, value: e.target.value });
                            setEditError(null);
                          }}
                          onKeyDown={(e) => {
                            if (e.key === "Enter") {
                              void handleRename(c.id, editing.value);
                            }
                            if (e.key === "Escape") {
                              setEditing(null);
                              setEditError(null);
                            }
                          }}
                          onBlur={() => {
                            // Mientras el PUT está en vuelo el blur no cierra:
                            // el error del backend tiene dónde aparecer.
                            if (!savingRef.current) {
                              setEditing(null);
                              setEditError(null);
                            }
                          }}
                          autoFocus
                        />
                        {editError && (
                          <p className="name-error" role="alert">
                            {editError}
                          </p>
                        )}
                      </>
                    ) : (
                      <span
                        className="name-text"
                        role="button"
                        tabIndex={0}
                        onClick={() => {
                          setEditError(null);
                          setEditing({ id: c.id, value: c.name });
                        }}
                        onKeyDown={(e) => {
                          if (e.key === "Enter") {
                            setEditError(null);
                            setEditing({ id: c.id, value: c.name });
                          }
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
                  <td className="row-actions">
                    <button
                      type="button"
                      className="btn-ghost"
                      aria-label={`Editar ${c.name}`}
                      onClick={() => {
                        setEditError(null);
                        setEditing({ id: c.id, value: c.name });
                      }}
                    >
                      ✏️
                    </button>
                    <button
                      type="button"
                      role="switch"
                      aria-checked={c.active}
                      aria-label={`${c.active ? "Desactivar" : "Activar"} ${c.name}`}
                      className={`admin-switch${c.active ? " is-on" : ""}`}
                      onClick={() => void handleToggle(c)}
                    >
                      <span className="admin-switch-knob" aria-hidden="true" />
                    </button>
                    <button
                      type="button"
                      className="btn-ghost"
                      onClick={() => void deleteCategory(c)}
                      aria-label={`Eliminar ${c.name}`}
                    >
                      🗑
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <td colSpan={3}>Total de registros: {list.length}</td>
              </tr>
            </tfoot>
          </table>
        </div>
      </div>

      {confirmState && (
        <ConfirmModal
          message={confirmState.message}
          onConfirm={() => settleConfirm(true)}
          onCancel={() => settleConfirm(false)}
        />
      )}
    </>
  );
}
