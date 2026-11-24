import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getTramite } from '../api';
import { MOCK_PACIENTE_ANA } from '../mock';
import type { Tramite } from '../types';
import EstadoBadge from '../components/EstadoBadge';

export default function AppPage() {
  const [tramite, setTramite] = useState<Tramite | null>(null);

  useEffect(() => {
    const folio = window.localStorage.getItem('saluddigna-folio') ?? 'SD-2026-0001';
    getTramite(folio).then(setTramite).catch(() => setTramite(null));
  }, []);

  const primerNombre = MOCK_PACIENTE_ANA.nombre.split(' ')[0];

  return (
    <div className="phone-wrap">
      <div className="phone">
        <div className="phone-header">
          <span className="brand-mark" aria-hidden="true">SD</span>
          <strong>Salud Digna</strong>
        </div>
        <div className="phone-body">
          <h1>Hola, {primerNombre} 👋</h1>
          <p className="muted">Tu salud, en una sola conversación.</p>

          {tramite ? (
            <section className="card cita-card">
              <p className="muted">Tu cita</p>
              <h2>{tramite.estudio.nombre}</h2>
              <p>
                Folio: <strong>{tramite.folio}</strong>
              </p>
              <p>
                <EstadoBadge estado={tramite.estado} />
              </p>
              <p>
                Precio: <strong>${tramite.precioCotizado}</strong>
              </p>
              <p>Clínica: {tramite.clinica ? `${tramite.clinica.nombre} (${tramite.clinica.ciudad})` : 'Por elegir'}</p>
              <p>Fecha: {tramite.fechaCita ?? 'Por confirmar'}</p>
            </section>
          ) : (
            <section className="card cita-card">
              <p>No hay una cita cargada todavía.</p>
              <Link className="btn btn-primary" to="/">Cotizar en la web</Link>
            </section>
          )}

          <section className="card">
            <h2>Próximos pasos</h2>
            <ol className="steps-list">
              <li>Elige tu clínica en WhatsApp o en la web.</li>
              <li>Confirma fecha y hora de tu cita.</li>
              <li>Llega 15 minutos antes con tu folio {tramite?.folio ?? 'SD-2026-0001'}.</li>
              <li>En clínica, haz check-in con ese folio.</li>
            </ol>
          </section>

          <div className="actions">
            <Link className="btn btn-primary" to="/whatsapp">Abrir WhatsApp</Link>
            <Link className="btn btn-secondary" to={`/journey/${encodeURIComponent(tramite?.folio ?? 'SD-2026-0001')}`}>
              Ver mi Journey
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
}
