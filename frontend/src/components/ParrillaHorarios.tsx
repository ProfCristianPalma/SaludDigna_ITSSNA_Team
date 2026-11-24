import { useEffect, useState } from 'react';
import { getParrilla, isApiError } from '../api';
import type { Parrilla } from '../api';

/**
 * Parrilla de horarios de una clínica en un día: slots de 30 min,
 * 07:00–16:30, con las horas ocupadas deshabilitadas (según las citas
 * CONFIRMADAS de la base de datos, para todos los usuarios). La hora
 * elegida se marca; el padre decide qué hacer con ella (confirmar).
 */
export default function ParrillaHorarios({
  clinicaId,
  fechaISO,
  estudioId,
  exceptoTramiteId,
  horaSeleccionada,
  onSeleccionar,
}: {
  clinicaId: string;
  fechaISO: string;
  estudioId?: string;
  exceptoTramiteId?: string;
  horaSeleccionada: string;
  onSeleccionar: (hora: string) => void;
}) {
  const [parrilla, setParrilla] = useState<Parrilla | null>(null);
  const [cargando, setCargando] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    async function cargar() {
      if (!clinicaId || !fechaISO) {
        setParrilla(null);
        setError('');
        return;
      }
      setCargando(true);
      setError('');
      try {
        setParrilla(await getParrilla(clinicaId, fechaISO, estudioId, exceptoTramiteId));
      } catch (err) {
        setParrilla(null);
        setError(
          isApiError(err)
            ? `No se pudo consultar la disponibilidad (HTTP ${err.status}: ${err.message}).`
            : 'El backend no respondió, no se pudo consultar la disponibilidad.',
        );
      } finally {
        setCargando(false);
      }
    }
    void cargar();
  }, [clinicaId, fechaISO, estudioId, exceptoTramiteId]);

  if (!clinicaId || !fechaISO) {
    return <p className="muted">Elige la clínica y la fecha para ver las horas disponibles.</p>;
  }
  if (cargando && !parrilla) return <p className="muted">Consultando horas disponibles…</p>;
  if (error) return <p className="notice" role="alert">{error}</p>;
  if (!parrilla) return null;

  const libres = parrilla.slots.filter((s) => !s.ocupado).length;
  return (
    <div>
      <p className="muted">
        Horarios de 30 minutos, de 07:00 a 17:00 (cada cita dura {parrilla.duracionMin} min).{' '}
        {libres === 0
          ? 'Ese día ya no quedan horas libres en esta clínica.'
          : `${libres} de ${parrilla.slots.length} horas libres.`}
      </p>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
        {parrilla.slots.map((s) => (
          <button
            key={s.hora}
            type="button"
            className={`btn btn-small ${horaSeleccionada === s.hora ? 'btn-primary' : 'btn-secondary'}`}
            disabled={s.ocupado || cargando}
            onClick={() => onSeleccionar(s.hora)}
            title={s.ocupado ? 'Hora ocupada' : `Elegir las ${s.hora}`}
          >
            {s.hora}
          </button>
        ))}
      </div>
      {horaSeleccionada && (
        <p className="muted" style={{ marginTop: 8 }}>
          Hora elegida: <strong>{horaSeleccionada}</strong>
        </p>
      )}
    </div>
  );
}
