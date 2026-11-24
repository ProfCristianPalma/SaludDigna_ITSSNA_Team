import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getPacientePorTelefono, getTramitesPorTelefono } from '../api';
import type { Paciente, Tramite } from '../types';
import EstadoBadge from '../components/EstadoBadge';

export default function AppPage() {
  const [telefono, setTelefono] = useState('+52 222 000 0001');
  const [paciente, setPaciente] = useState<Paciente | null>(null);
  const [tramites, setTramites] = useState<Tramite[]>([]);
  const [mensaje, setMensaje] = useState('');
  const [cargando, setCargando] = useState(false);

  async function cargar(tel: string) {
    if (!tel.trim()) return;
    setCargando(true);
    setMensaje('');
    try {
      const [p, lista] = await Promise.all([
        getPacientePorTelefono(tel),
        getTramitesPorTelefono(tel),
      ]);
      setPaciente(p);
      setTramites(lista);
      if (lista.length === 0) setMensaje('No tienes folios todavía. Cotiza un estudio en la web y aparecerá aquí.');
    } catch {
      setPaciente(null);
      setTramites([]);
      setMensaje('No encontré un paciente con ese teléfono. Verifica el número.');
    } finally {
      setCargando(false);
    }
  }

  useEffect(() => {
    void cargar('+52 222 000 0001');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const primerNombre = paciente?.nombre.split(' ')[0] ?? 'Hola';
  const tramitePrincipal = tramites[0] ?? null;

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

          <form
            className="form"
            onSubmit={(e) => {
              e.preventDefault();
              void cargar(telefono);
            }}
          >
            <label>
              Tu teléfono (WhatsApp)
              <input value={telefono} onChange={(e) => setTelefono(e.target.value)} placeholder="+52 222 000 0001" />
            </label>
            <button type="submit" className="btn btn-primary" disabled={cargando}>
              {cargando ? 'Buscando…' : 'Ver mis citas'}
            </button>
          </form>

          {mensaje && <p className="notice" role="status">{mensaje}</p>}

          {tramites.length === 0 && !mensaje && (
            <section className="card cita-card">
              <p>No hay una cita cargada todavía.</p>
              <Link className="btn btn-primary" to="/">Cotizar en la web</Link>
            </section>
          )}

          {tramites.map((t) => (
            <section key={t.id} className="card cita-card">
              <p className="muted">Tu cita</p>
              <h2>{t.estudio.nombre}</h2>
              <p>
                Folio: <strong>{t.folio}</strong>
              </p>
              <p>
                <EstadoBadge estado={t.estado} />
              </p>
              <p>
                Precio: <strong>${t.precioCotizado}</strong>
              </p>
              <p>Clínica: {t.clinica ? `${t.clinica.nombre} (${t.clinica.ciudad})` : 'Por elegir'}</p>
              <p>Fecha: {t.fechaCita ?? 'Por confirmar'}</p>
              <div className="actions">
                <Link className="btn btn-secondary" to={`/journey/${encodeURIComponent(t.folio)}`}>
                  Ver mi Journey
                </Link>
              </div>
            </section>
          ))}

          <section className="card">
            <h2>Próximos pasos</h2>
            <ol className="steps-list">
              <li>Elige tu clínica en WhatsApp o en la web.</li>
              <li>Confirma fecha y hora de tu cita.</li>
              <li>Llega 15 minutos antes con tu folio {tramitePrincipal?.folio ?? 'SD-2026-0001'}.</li>
              <li>En clínica, haz check-in con ese folio.</li>
            </ol>
          </section>

          <div className="actions">
            <Link className="btn btn-primary" to="/whatsapp">Abrir WhatsApp</Link>
            <Link className="btn btn-secondary" to={`/journey/${encodeURIComponent(tramitePrincipal?.folio ?? 'SD-2026-0001')}`}>
              Ver mi Journey
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
}
