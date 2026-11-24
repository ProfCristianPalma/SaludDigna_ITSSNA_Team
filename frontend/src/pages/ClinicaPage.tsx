import { useCallback, useEffect, useState } from 'react';
import { atenderTramite, getTramitesPorEstado, registrarEventoCheckIn } from '../api';
import type { Tramite } from '../types';
import EstadoBadge from '../components/EstadoBadge';

export default function ClinicaPage() {
  const [tramites, setTramites] = useState<Tramite[]>([]);
  const [checkIn, setCheckIn] = useState<Record<string, boolean>>({});
  const [mensaje, setMensaje] = useState('');
  const [cargando, setCargando] = useState(false);

  // Citas confirmadas, desde la BD (GET /tramites?estado=CONFIRMADO).
  const cargar = useCallback(async () => {
    setCargando(true);
    try {
      setTramites(await getTramitesPorEstado('CONFIRMADO'));
    } catch {
      setMensaje('No se pudieron cargar las citas confirmadas. Revisa que el backend esté arriba.');
    } finally {
      setCargando(false);
    }
  }, []);

  useEffect(() => {
    void cargar();
  }, [cargar]);

  async function handleAtendido(id: string) {
    setCargando(true);
    try {
      // POST /tramites/{id}/atender: CONFIRMADO -> ATENDIDO en la BD.
      await atenderTramite(id);
      setMensaje('Atención registrada en la base de datos. El trámite pasó a ATENDIDO.');
      await cargar(); // El atendido ya no aparece como pendiente.
    } catch {
      setMensaje('No se pudo registrar la atención. Revisa que el backend esté arriba.');
    } finally {
      setCargando(false);
    }
  }

  async function handleCheckIn(id: string) {
    try {
      // Check-in queda en el log de eventos (POST /tramites/{id}/eventos).
      await registrarEventoCheckIn(id);
      setCheckIn((prev) => ({ ...prev, [id]: true }));
      setMensaje('Check-in registrado en la base de datos. El paciente ya está en sala de espera.');
    } catch {
      setMensaje('No se pudo registrar el check-in. Revisa que el backend esté arriba.');
    }
  }

  return (
    <div>
      <h1>Clínica — recepción</h1>
      <p className="muted">Citas confirmadas en la base de datos, listas para check-in y atención.</p>
      {mensaje && <p className="notice" role="status">{mensaje}</p>}
      {cargando && tramites.length === 0 && <p className="muted">Cargando citas confirmadas…</p>}

      {tramites.length === 0 && !cargando && <p className="muted">No hay citas confirmadas.</p>}

      <div className="grid-2">
        {tramites.map((t) => (
          <article key={t.id} className="card">
            <p>
              Folio: <strong>{t.folio}</strong> <EstadoBadge estado={t.estado} />
            </p>
            <h2>{t.estudio.nombre}</h2>
            <dl className="kv">
              <dt>Paciente</dt><dd>{t.paciente?.nombre ?? '—'}</dd>
              <dt>Teléfono</dt><dd>{t.paciente?.telefono ?? '—'}</dd>
              <dt>Clínica</dt><dd>{t.clinica?.nombre} ({t.clinica?.ciudad})</dd>
              <dt>Fecha de cita</dt><dd>{t.fechaCita ?? 'Sin confirmar'}</dd>
              <dt>Precio</dt><dd><strong>${t.precioCotizado}</strong></dd>
              <dt>Check-in</dt><dd>{checkIn[t.id] ? 'Registrado ✓' : 'Pendiente'}</dd>
            </dl>
            <div className="actions">
              <button type="button" className="btn btn-secondary" onClick={() => void handleCheckIn(t.id)} disabled={checkIn[t.id]}>
                Check-in
              </button>
              <button
                type="button"
                className="btn btn-primary"
                onClick={() => void handleAtendido(t.id)}
                disabled={t.estado === 'ATENDIDO' || cargando}
              >
                Registrar atención
              </button>
            </div>
          </article>
        ))}
      </div>
    </div>
  );
}
