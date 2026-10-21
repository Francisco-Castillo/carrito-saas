import { useState } from "react";
import { CARTA_COLORS } from "./cartaColors";
import ColorPicker from "./ColorPicker";
import { suggestColorsFromImage } from "./logoColors";
import { MENU_FONT_OPTIONS } from "../../menuFonts";
import type { BusinessProfileHook } from "./useBusinessProfile";

/**
 * Pestaña "Personalización" de la vista Local (spec S1/S2 de
 * odd/tasks/personalizacion-carta.md): todo lo visual de la carta en un solo
 * lugar.
 *
 *   - Color de marca: MUDADO desde "Datos del local" (que queda con lo
 *     informativo) — paleta curada + picker HSV + quitar, mismo contrato.
 *   - Fuente de la carta: las ocho fuentes curadas de MENU_FONT_OPTIONS,
 *     cada opción renderizada en su propia tipografía (la vista previa se
 *     carga on demand con loadMenuFonts al montar la pestaña). Sin fuente
 *     elegida, ninguna opción está marcada: la carta usa Inter.
 *
 * El guardado sigue siendo el PUT completo del perfil (useBusinessProfile):
 * lo editado acá viaja junto con lo de las otras pestañas.
 */
export default function PersonalizationSection({
  profile,
}: {
  profile: BusinessProfileHook;
}) {
  const { primaryColor, setPrimaryColor, menuFont, setMenuFont, logoUrl, saving, error, save } = profile;

  // Sugerencias desde el logo (spec S3): la extracción corre al hacer clic,
  // nunca al montar (analizar es gratis pero cargar la imagen no); un fallo
  // deja error legible y el resto de la pestaña intacto.
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [suggesting, setSuggesting] = useState(false);
  const [suggestError, setSuggestError] = useState<string | null>(null);

  async function suggestFromLogo() {
    setSuggesting(true);
    setSuggestError(null);
    try {
      setSuggestions(await suggestColorsFromImage(logoUrl));
    } catch (e) {
      setSuggestions([]);
      setSuggestError((e as Error).message);
    } finally {
      setSuggesting(false);
    }
  }

  return (
    <div className="glass-panel fade-up admin-table-panel admin-profile-panel">
      <h3>Personalización de la carta</h3>

      {error && (
        <p className="admin-profile-error" role="alert">
          {error}
        </p>
      )}

      <div className="admin-profile-fields">
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
          {logoUrl && (
            <div className="admin-logo-colors">
              <button
                type="button"
                className="btn-ghost"
                disabled={suggesting}
                onClick={() => void suggestFromLogo()}
              >
                {suggesting ? "Analizando logo…" : "Sugerir desde el logo"}
              </button>
              {suggestError && (
                <p className="admin-profile-error" role="alert">
                  {suggestError}
                </p>
              )}
              {suggestions.length > 0 && (
                <div
                  className="admin-color-palette"
                  role="group"
                  aria-label="Colores sugeridos del logo"
                >
                  {suggestions.map((hex) => (
                    <button
                      key={hex}
                      type="button"
                      className={`admin-color-swatch${primaryColor.toUpperCase() === hex ? " is-selected" : ""}`}
                      style={{ backgroundColor: hex }}
                      aria-label={`Sugerido ${hex}`}
                      aria-pressed={primaryColor.toUpperCase() === hex}
                      onClick={() => setPrimaryColor(hex)}
                    />
                  ))}
                </div>
              )}
            </div>
          )}
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
            Pinta la carta entera: fondo, botones y detalles. Cualquier color es
            legible (el texto se ajusta solo); el badge de Abierto/Cerrado y los
            precios mantienen siempre su color. Vacío = diseño estándar.
          </p>
        </div>

        <div className="admin-profile-field">
          <span className="admin-profile-image-label">Tipografía de la carta</span>
          <div className="admin-font-options" role="group" aria-label="Fuente de la carta">
            {MENU_FONT_OPTIONS.map(({ slug, name, family }) => (
              <button
                key={slug}
                type="button"
                className={`admin-font-option${(menuFont || "inter") === slug ? " is-selected" : ""}`}
                style={{ fontFamily: `"${family}"` }}
                aria-label={name}
                aria-pressed={menuFont === slug}
                onClick={() => setMenuFont(slug)}
              >
                <span className="admin-font-option-sample">Carta del local</span>
                <span className="admin-font-option-name">{name}</span>
              </button>
            ))}
          </div>
          <p className="admin-image-hint">
            La carta usa la tipografía elegida para títulos, precios y botones.
            Sin elección queda Inter, la fuente del tema.
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
