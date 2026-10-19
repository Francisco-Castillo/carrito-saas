import { DAYS, exceptionLabel } from "./useBusinessProfile";
import type { BusinessProfileHook } from "./useBusinessProfile";

/**
 * Pestaña "Horarios": el horario semanal y las fechas especiales, con su
 * propio botón de guardar. NO tiene estado propio del perfil: todo vive en
 * useBusinessProfile, porque el PUT del backend reemplaza el perfil completo
 * y el guardado envía también lo editado en la pestaña "Datos del local".
 */
export default function HorariosSection({ profile }: { profile: BusinessProfileHook }) {
  const {
    days,
    exceptions,
    newExceptionDate,
    setNewExceptionDate,
    saving,
    error,
    setShift,
    addShift,
    removeShift,
    setExceptionShift,
    addExceptionShift,
    removeExceptionShift,
    removeException,
    setExceptionYearly,
    addException,
    save,
  } = profile;

  const sortedExceptions = [...exceptions].sort((a, b) => a.date.localeCompare(b.date));

  return (
    <div className="glass-panel fade-up admin-table-panel admin-profile-panel">
      {error && (
        <p className="admin-profile-error" role="alert">
          {error}
        </p>
      )}

      <div className="admin-profile-hours">
        <h3>Horario de atención</h3>
        <p className="admin-profile-hours-note">
          Si la hora de cierre es anterior a la de apertura, el local cierra después de medianoche.
        </p>

        {DAYS.map((day, i) => (
          <div
            key={day}
            className={`admin-profile-day${days[i].length === 0 ? " admin-profile-day--closed" : ""}`}
          >
            <div className="admin-profile-day-head">
              <span className="admin-profile-day-name">{day}</span>
              {days[i].length === 0 && <span className="admin-profile-day-closed">Cerrado {day}</span>}
              <button
                type="button"
                className="btn-ghost admin-profile-day-add"
                aria-label={`Agregar turno ${day}`}
                onClick={() => addShift(i)}
              >
                + Agregar turno
              </button>
            </div>
            {days[i].map((shift, j) => (
              <div key={j} className="admin-profile-shift">
                <input
                  type="time"
                  className="admin-input"
                  aria-label={`Apertura ${day} turno ${j + 1}`}
                  value={shift.opensAt}
                  onChange={(e) => setShift(i, j, { opensAt: e.target.value })}
                />
                <input
                  type="time"
                  className="admin-input"
                  aria-label={`Cierre ${day} turno ${j + 1}`}
                  value={shift.closesAt}
                  onChange={(e) => setShift(i, j, { closesAt: e.target.value })}
                />
                <button
                  type="button"
                  className="btn-ghost admin-profile-shift-remove"
                  aria-label={`Quitar turno ${day} ${j + 1}`}
                  onClick={() => removeShift(i, j)}
                >
                  Quitar
                </button>
              </div>
            ))}
          </div>
        ))}
      </div>

      <div className="admin-profile-hours admin-profile-exceptions">
        <h3>Fechas especiales</h3>
        <p className="admin-profile-hours-note">
          Un feriado se agrega sin turnos (cerrado); con turnos, rige ese horario sólo ese día.
        </p>

        {sortedExceptions.length === 0 ? (
          <p className="admin-profile-exceptions-empty">
            Sin fechas especiales: los días se rigen por el horario semanal.
          </p>
        ) : (
          sortedExceptions.map((exc) => {
            const label = exceptionLabel(exc);
            return (
              <div
                key={exc.date}
                className={`admin-profile-day${
                  exc.shifts.length === 0 ? " admin-profile-day--closed" : ""
                }${exc.yearly ? " admin-profile-day--yearly" : ""}`}
              >
                <div className="admin-profile-day-head">
                  <span className="admin-profile-day-name">{label}</span>
                  {exc.shifts.length === 0 && (
                    <span className="admin-profile-day-closed">Cerrado {label}</span>
                  )}
                  <label className="admin-profile-exception-yearly">
                    <input
                      type="checkbox"
                      className="admin-profile-exception-yearly-check"
                      aria-label={`Se repite cada año ${label}`}
                      checked={exc.yearly}
                      onChange={(e) => setExceptionYearly(exc.date, e.target.checked)}
                    />
                    Se repite cada año
                  </label>
                  <button
                    type="button"
                    className="btn-ghost admin-profile-day-add"
                    aria-label={`Agregar turno ${label}`}
                    onClick={() => addExceptionShift(exc.date)}
                  >
                    + Agregar turno
                  </button>
                  <button
                    type="button"
                    className="btn-ghost admin-profile-exception-remove"
                    aria-label={`Quitar fecha ${label}`}
                    onClick={() => removeException(exc.date)}
                  >
                    Quitar fecha
                  </button>
                </div>
                {exc.shifts.map((shift, j) => (
                  <div key={j} className="admin-profile-shift">
                    <input
                      type="time"
                      className="admin-input"
                      aria-label={`Apertura ${label} turno ${j + 1}`}
                      value={shift.opensAt}
                      onChange={(e) => setExceptionShift(exc.date, j, { opensAt: e.target.value })}
                    />
                    <input
                      type="time"
                      className="admin-input"
                      aria-label={`Cierre ${label} turno ${j + 1}`}
                      value={shift.closesAt}
                      onChange={(e) => setExceptionShift(exc.date, j, { closesAt: e.target.value })}
                    />
                    <button
                      type="button"
                      className="btn-ghost admin-profile-shift-remove"
                      aria-label={`Quitar turno ${label} ${j + 1}`}
                      onClick={() => removeExceptionShift(exc.date, j)}
                    >
                      Quitar
                    </button>
                  </div>
                ))}
              </div>
            );
          })
        )}

        <div className="admin-profile-exception-add">
          <input
            type="date"
            className="admin-input"
            aria-label="Nueva fecha especial"
            value={newExceptionDate}
            onChange={(e) => setNewExceptionDate(e.target.value)}
          />
          <button type="button" className="btn-ghost" aria-label="Agregar fecha" onClick={addException}>
            + Agregar fecha
          </button>
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
