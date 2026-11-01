import { useEffect, useRef, useState } from "react";
import { API, authHeader } from "./api";
import { showSuccessToast } from "./toast";

interface CreateCategoryModalProps {
  onClose: () => void;
  onCreated: () => void;
}

/**
 * Puerto del openCreateCategoryModal del legacy admin/admin.js: validación
 * (nombre obligatorio con borde rojo + mensaje), POST /api/categorias con
 * {name, active: true} — el orden ya no lo manda el cliente: el servidor
 * asigna max(orden) + 1 para que la categoría nazca última —, toast de
 * éxito/error y cierre con Escape o clic fuera. El modal queda abierto si la
 * creación falla, como el legacy.
 */
export default function CreateCategoryModal({ onClose, onCreated }: CreateCategoryModalProps) {
  const [name, setName] = useState("");
  const [error, setError] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    inputRef.current?.focus();

    function escHandler(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    document.addEventListener("keydown", escHandler);
    return () => document.removeEventListener("keydown", escHandler);
  }, [onClose]);

  async function handleCreate() {
    const trimmed = name.trim();

    if (!trimmed) {
      // Mostrar borde rojo y mensaje, como el legacy.
      setError(true);
      return;
    }
    setError(false);

    try {
      const res = await fetch(`${API}/categorias`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...authHeader(),
        },
        body: JSON.stringify({ name: trimmed, active: true }),
      });

      const data = await res.json();
      if (!res.ok) throw new Error(data.message || "Error creando categoría");

      showSuccessToast("Categoría creada con éxito");
      onCreated();
    } catch (e) {
      showSuccessToast(`Error: ${(e as Error).message}`);
    }
  }

  return (
    <div
      className="toast-modal-backdrop"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="toast-modal" role="dialog" aria-modal="true" aria-labelledby="create-category-title">
        <h3 id="create-category-title">Crear nueva categoría</h3>
        <input
          ref={inputRef}
          className={`admin-input${error ? " admin-input--error" : ""}`}
          type="text"
          value={name}
          placeholder="Nombre de la categoría"
          aria-label="Nombre de la categoría"
          aria-invalid={error}
          onChange={(e) => {
            setName(e.target.value);
            setError(false);
          }}
          onKeyDown={(e) => {
            if (e.key === "Enter") void handleCreate();
          }}
        />
        {error && <p className="name-error">El nombre es obligatorio</p>}
        <div className="toast-modal-actions">
          <button type="button" className="btn-primary" onClick={() => void handleCreate()}>
            Crear
          </button>
        </div>
      </div>
    </div>
  );
}
