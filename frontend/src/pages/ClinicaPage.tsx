import { useState } from 'react';
import { MOCK_PACIENTE_ANA, mockTramite } from '../mock';
import type { Tramite } from '../types';
import EstadoBadge from '../components/EstadoBadge';

export default function ClinicaPage() {
  const [tramites, setTramites] = useState<Tramite[]>(() => {
    const t = mockTramite();
    t.clinica = { id: 'cli-002', nombre: 'Clínica Salud Digna Tehuacán', ciudad: 'Tehuacán' };
    t.estado = 'CONFIRMADO';
    t.fechaCita = '2026-11-24T09:00:00';
    t.datosPendientes = [];
    return [t];
  });
  const [checkIn, setCheckIn] = useState<Record<string, boolean>>({});
  const [mensaje, setMensaje] = useState('');

  function handleAtendido(id: string) {
    setTramites((prev) => prev.map((t) => (t.id === id ? { ...t, estado: 'ATENDIDO' } : t)));
    setMensaje('Atención registrada. El trámite pasó a ATENDIDO.');
  }

  function handleCheckIn(id: string) {
    setCheckIn((prev) => ({ ...prev, [id]: true }));
    setMensaje('Check-in registrado en recepción. El paciente ya está en sala de espera.');
  }

  return (
    <div>
      <h1>Clínica — recepción</h1>
      <p className="muted">Citas y trámites confirmados, listos para check-in y atención.</p>
      {mensaje && <p className="notice" role="status">{mensaje}</p>}

      {tramites.length === 0 && <p className="muted">No hay citas confirmadas.</p>}

      <div className="grid-2">
        {tramites.map((t) => (
          <article key={t.id} className="card">
            <p>
              Folio: <strong>{t.folio}</strong> <EstadoBadge estado={t.estado} />
            </p>
            <h2>{t.estudio.nombre}</h2>
            <dl className="kv">
              <dt>Paciente</dt><dd>{t.paciente?.nombre ?? MOCK_PACIENTE_ANA.nombre}</dd>
              <dt>Teléfono</dt><dd>{t.paciente?.telefono ?? MOCK_PACIENTE_ANA.telefono}</dd>
              <dt>Clínica</dt><dd>{t.clinica?.nombre} ({t.clinica?.ciudad})</dd>
              <dt>Fecha de cita</dt><dd>{t.fechaCita ?? 'Sin confirmar'}</dd>
              <dt>Precio</dt><dd><strong>${t.precioCotizado}</strong></dd>
              <dt>Check-in</dt><dd>{checkIn[t.id] ? 'Registrado ✓' : 'Pendiente'}</dd>
            </dl>
            <div className="actions">
              <button type="button" className="btn btn-secondary" onClick={() => handleCheckIn(t.id)} disabled={checkIn[t.id]}>
                Check-in
              </button>
              <button
                type="button"
                className="btn btn-primary"
                onClick={() => handleAtendido(t.id)}
                disabled={t.estado === 'ATENDIDO'}
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
