import { useEffect, useRef, useState } from "react";
import type { ChangeEvent } from "react";
import { API, authHeader } from "./api";
import ColorPicker from "./ColorPicker";
import type { BusinessProfileHook } from "./useBusinessProfile";

/**
 * Pestaña "Datos del local": nombre, dirección, Instagram, color y las
 * IMÁGENES (logo y banner) que ahora se SUBEN, ya no se pegan como URL.
 *
 * Contrato del upload (espeja al de imágenes de producto):
 *   - La imagen viaja multipart (campo "file") a POST /api/business/logo o
 *     /api/business/banner: el backend la guarda con nombre UUID en
 *     <uploads>/business/ y persiste la URL pública /api/images/business/...
 *     La respuesta es el perfil actualizado y de ahí se toma la URL real.
 *   - Quitar hace DELETE al mismo endpoint; el backend limpia el campo y
 *     borra el archivo SI era del storage propio.
 *   - Validación local antes de tocar el backend: JPG/PNG/WebP y máximo
 *     5 MB, con los mismos mensajes del endpoint de productos. El tipo y el
 *     tamaño se revalidan en el servidor (400 con mensaje en español).
 *   - La subida es inmediata al elegir archivo (no espera al "Guardar
 *     cambios", que sólo manda los campos de texto del perfil — el PUT
 *     reemplaza todo el perfil pero las imágenes ya viven en su fila).
 *   - Un logoUrl heredado de la época de "pegar URL" se muestra en la
 *     preview tal cual; el primer reemplazo o "Quitar" lo migra al storage.
 */

/** Formato de imagen aceptado por el backend (ImageStorageService). */
const IMAGE_ACCEPT = "image/jpeg,image/png,image/webp";

/** Techo duro del backend: 5 MB por archivo. */
const IMAGE_MAX_BYTES = 5 * 1024 * 1024;

/**
 * Paleta curada para el color de la carta: tonos que se ven bien sobre el
 * fondo oscuro glass (el contraste del texto encima lo resuelve brandColor
 * en la carta, así que cualquier color es legible; la curaduría es estética).
 * El nombre es el accessible name del swatch (los tests lo usan).
 */
const CARTA_COLORS: Array<{ name: string; hex: string }> = [
  { name: "Violeta", hex: "#7C3AED" },
  { name: "Índigo", hex: "#6366F1" },
  { name: "Azul", hex: "#3B82F6" },
  { name: "Celeste", hex: "#22D3EE" },
  { name: "Verde", hex: "#22C55E" },
  { name: "Lima", hex: "#84CC16" },
  { name: "Amarillo", hex: "#FDE047" },
  { name: "Naranja", hex: "#F97316" },
  { name: "Rojo", hex: "#EF4444" },
  { name: "Rosa", hex: "#EC4899" },
  { name: "Fucsia", hex: "#D946EF" },
  { name: "Blanco humo", hex: "#E2E8F0" },
];

/** Miniatura de lo que apunta la URL: ver la imagen es la única devolución del owner. */
function UrlPreview({ url, alt }: { url: string; alt: string }) {
  const [failed, setFailed] = useState(false);
  const trimmed = url.trim();

  useEffect(() => {
    setFailed(false);
  }, [trimmed]);

  if (!trimmed || failed) {
    return (
      <span className="admin-profile-preview admin-profile-preview--empty" aria-label={alt}>
        Sin imagen
      </span>
    );
  }
  return (
    <img
      className="admin-profile-preview"
      src={trimmed}
      alt={alt}
      onError={() => setFailed(true)}
    />
  );
}

/** Campos de una imagen del negocio: preview + elegir archivo + quitar. */
function ImageUploadField({
  id,
  label,
  endpoint,
  url,
  onUrl,
  hint,
}: {
  id: string;
  label: string;
  endpoint: "logo" | "banner";
  url: string;
  /** Recibe la URL definitiva devuelta por el backend (o "" al quitar). */
  onUrl: (url: string) => void;
  /** Formato, tamaño y dimensión recomendada, según cómo la muestra la carta. */
  hint: string;
}) {
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  /** Sube el archivo ya validado; devuelve el perfil actualizado o lanza. */
  async function upload(file: File): Promise<string> {
    const fd = new FormData();
    fd.append("file", file);
    const res = await fetch(`${API}/business/${endpoint}`, {
      method: "POST",
      headers: authHeader(),
      body: fd,
    });
    const data = (await res.json().catch(() => null)) as { logoUrl?: string; bannerUrl?: string; message?: string } | null;
    if (!res.ok) throw new Error(data?.message || "No se pudo guardar la imagen");
    const next = endpoint === "logo" ? data?.logoUrl : data?.bannerUrl;
    if (!next) throw new Error("No se pudo guardar la imagen");
    return next;
  }

  function handleFile(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0] ?? null;
    // El mismo input debe poder recargar el MISMO archivo tras un error.
    e.target.value = "";
    if (!file) return;

    if (!IMAGE_ACCEPT.split(",").includes(file.type)) {
      setError("Formato no soportado: usá JPG, PNG o WebP");
      return;
    }
    if (file.size > IMAGE_MAX_BYTES) {
      setError("La imagen supera el máximo de 5 MB");
      return;
    }

    setError(null);
    setUploading(true);
    upload(file)
      .then(onUrl)
      .catch((err) => setError((err as Error).message))
      .finally(() => setUploading(false));
  }

  async function remove() {
    setError(null);
    setUploading(true);
    try {
      const res = await fetch(`${API}/business/${endpoint}`, { method: "DELETE", headers: authHeader() });
      if (!res.ok) {
        const data = (await res.json().catch(() => null)) as { message?: string } | null;
        throw new Error(data?.message || "No se pudo quitar la imagen");
      }
      onUrl("");
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setUploading(false);
    }
  }

  return (
    <div className="admin-profile-field">
      <span className="admin-profile-image-label">{label}</span>
      <UrlPreview url={url} alt={`Vista previa del ${endpoint}`} />
      <div className="admin-profile-image-actions">
        <input
          ref={inputRef}
          id={id}
          type="file"
          className="admin-profile-image-input"
          accept={IMAGE_ACCEPT}
          aria-label={`Elegir imagen del ${endpoint}`}
          onChange={handleFile}
          disabled={uploading}
        />
        <button
          type="button"
          className="btn-ghost"
          disabled={uploading}
          onClick={() => inputRef.current?.click()}
        >
          {uploading ? "Subiendo…" : "Subir imagen"}
        </button>
        {url && (
          <button
            type="button"
            className="btn-ghost admin-profile-image-remove"
            aria-label={`Quitar ${endpoint}`}
            disabled={uploading}
            onClick={() => void remove()}
          >
            Quitar
          </button>
        )}
      </div>
      <p className="admin-image-hint">{hint}</p>
      {error && (
        <p className="admin-profile-error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

export default function DatosSection({ profile }: { profile: BusinessProfileHook }) {
  const {
    name,
    setName,
    phone,
    setPhone,
    logoUrl,
    setLogoUrl,
    bannerUrl,
    setBannerUrl,
    address,
    setAddress,
    instagramUrl,
    setInstagramUrl,
    primaryColor,
    setPrimaryColor,
    saving,
    error,
    save,
  } = profile;

  return (
    <div className="glass-panel fade-up admin-table-panel admin-profile-panel">
      <h3>Datos del local</h3>

      {error && (
        <p className="admin-profile-error" role="alert">
          {error}
        </p>
      )}

      <div className="admin-profile-fields">
        <div className="admin-profile-field admin-profile-field--wide">
          <label htmlFor="profile-name">Nombre del local</label>
          <input
            id="profile-name"
            className="admin-input"
            aria-label="Nombre del local"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </div>

        <ImageUploadField
          id="profile-logo-upload"
          label="Logo del local"
          endpoint="logo"
          url={logoUrl}
          onUrl={setLogoUrl}
          hint="JPG, PNG o WebP · hasta 5 MB · ideal 512×512: se muestra redonda en la carta"
        />

        <ImageUploadField
          id="profile-banner-upload"
          label="Banner del local"
          endpoint="banner"
          url={bannerUrl}
          onUrl={setBannerUrl}
          hint="JPG, PNG o WebP · hasta 5 MB · ideal 1600×400 panorámica: a ancho completo, se recorta en el centro"
        />

        <div className="admin-profile-field">
          <label htmlFor="profile-address">Dirección</label>
          <input
            id="profile-address"
            className="admin-input"
            aria-label="Dirección"
            value={address}
            onChange={(e) => setAddress(e.target.value)}
          />
        </div>

        <div className="admin-profile-field">
          <label htmlFor="profile-phone">WhatsApp del local</label>
          <input
            id="profile-phone"
            className="admin-input"
            aria-label="WhatsApp del local"
            placeholder="5491123456789"
            inputMode="tel"
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
          />
          <p className="admin-image-hint">
            Código de país + número, sin 0 ni 15 (ej: 5491123456789). Es el
            número que usa la carta para el fallback de WhatsApp.
          </p>
        </div>

        <div className="admin-profile-field">
          <label htmlFor="profile-instagram">URL de Instagram</label>
          <input
            id="profile-instagram"
            className="admin-input"
            aria-label="URL de Instagram"
            value={instagramUrl}
            onChange={(e) => setInstagramUrl(e.target.value)}
          />
        </div>

        <div className="admin-profile-field">
          <span className="admin-profile-image-label">Color de la carta</span>
          <div className="admin-color-palette" role="group" aria-label="Color de la carta">
            {CARTA_COLORS.map(({ name, hex }) => (
              <button
                key={hex}
                type="button"
                className={`admin-color-swatch${primaryColor.toUpperCase() === hex ? " is-selected" : ""}`}
                style={{ backgroundColor: hex }}
                aria-label={name}
                aria-pressed={primaryColor.toUpperCase() === hex}
                onClick={() => setPrimaryColor(hex)}
              />
            ))}
          </div>
          <ColorPicker value={primaryColor} onChange={setPrimaryColor} />
          {primaryColor && (
            <div className="admin-color-actions">
              <button
                type="button"
                className="btn-ghost admin-color-remove"
                aria-label="Quitar color"
                onClick={() => setPrimaryColor("")}
              >
                Quitar color
              </button>
            </div>
          )}
          <p className="admin-image-hint">
            Pinta la carta entera: fondo, precios, botones y detalles. Cualquier
            color es legible (el texto se ajusta solo); el badge de
            Abierto/Cerrado mantiene siempre su color de estado. Vacío = carta
            con el diseño estándar.
          </p>
        </div>
      </div>

      <button
        type="button"
        className="btn-primary admin-profile-save"
        disabled={saving}
        onClick={() => void save()}
      >
        {saving ? "Guardando…" : "Guardar cambios"}
      </button>
    </div>
  );
}
