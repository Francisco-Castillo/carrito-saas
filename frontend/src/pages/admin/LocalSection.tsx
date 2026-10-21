import { useState } from "react";
import DatosSection from "./DatosSection";
import HorariosSection from "./HorariosSection";
import LoadingPanel from "./LoadingPanel";
import PersonalizationSection from "./PersonalizationSection";
import QrTablesSection from "./QrTablesSection";
import { useBusinessProfile } from "./useBusinessProfile";

/**
 * Vista "Local" del panel admin, separada en CUATRO PESTAÑAS:
 *
 *   - "Datos del local" (DatosSection): nombre, logo, banner, dirección
 *     e Instagram — sólo lo informativo.
 *   - "Personalización" (PersonalizationSection): color de la carta y
 *     tipografía — lo visual del menú público.
 *   - "Horarios" (HorariosSection): horario semanal y fechas especiales.
 *   - "QR y mesas" (QrTablesSection): QR del menú público y mesas; NO se
 *     monta hasta que se elige su pestaña.
 *
 * El estado del perfil vive en useBusinessProfile, a nivel de esta vista:
 * el PUT /api/business/profile REEMPLAZA el perfil completo, así que el
 * guardado desde cualquier pestaña envía todo (lo editado acá + lo editado
 * en las otras) y el GET se hace una sola vez al abrir la vista.
 */

/** Pestañas de la vista Local: datos, personalización, horarios y QR/mesas. */
const LOCAL_TABS = [
  { id: "profile", label: "Datos del local" },
  { id: "personalizacion", label: "Personalización" },
  { id: "hours", label: "Horarios" },
  { id: "qr", label: "QR y mesas" },
] as const;

type LocalTab = (typeof LOCAL_TABS)[number]["id"];

export default function LocalSection() {
  const [tab, setTab] = useState<LocalTab>("profile");
  const profile = useBusinessProfile();

  // Primera carga del perfil: skeleton del dashboard en vez de la vista
  // invisible (return null) que no daba ninguna señal (S1 de
  // odd/tasks/carga-responsive-panel.md).
  if (profile.loading) return <LoadingPanel label="Cargando el perfil del local…" lines={6} />;

  return (
    <div>
      <div className="admin-local-tabs" role="tablist" aria-label="Secciones del local">
        {LOCAL_TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            role="tab"
            aria-selected={tab === t.id}
            className={`btn-ghost admin-local-tab${tab === t.id ? " admin-local-tab--active" : ""}`}
            onClick={() => setTab(t.id)}
          >
            {t.label}
          </button>
        ))}
      </div>
      {tab === "profile" && <DatosSection profile={profile} />}
      {tab === "personalizacion" && <PersonalizationSection profile={profile} />}
      {tab === "hours" && <HorariosSection profile={profile} />}
      {tab === "qr" && <QrTablesSection />}
    </div>
  );
}
