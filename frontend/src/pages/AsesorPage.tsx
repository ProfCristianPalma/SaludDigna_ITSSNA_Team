import { useState } from 'react';
import { getTimeline, getTramite } from '../api';
import { MOCK_PACIENTE_ANA } from '../mock';
import type { Evento, Tramite } from '../types';
import EstadoBadge from '../components/EstadoBadge';
import Timeline from '../components/Timeline';

export default function AsesorPage() {
  const [busqueda, setBusqueda] = useState(MOCK_PACIENTE_ANA.telefono);
  const [tramite, setTramite] = useState<Tramite | null>(null);
  const [timeline, setTimeline] = useState<Evento[]>([]);
  const [nota, setNota] = useState('');
  const [notas, setNotas] = useState<string[]>([]);
  const [mensaje, setMensaje] = useState('');
  const [buscando, setBuscando] = useState(false);

  async function handleBuscar(e: React.FormEvent) {
    e.preventDefault();
    const q = busqueda.trim();
    if (!q) return;
    setBuscando(true);
    setMensaje('');
    try {
      const t = await getTramite(q);
      setTramite(t);
      setTimeline(await getTimeline(t.id));
    } catch {
      setMensaje('No se encontró un trámite con ese teléfono o folio.');
    } finally {
      setBuscando(false);
    }
  }

  function handleCompletarDato(dato: string) {
    if (!tramite) return;
    setTramite({ ...tramite, datosPendientes: tramite.datosPendientes.filter((d) => d !== dato) });
    setNotas((prev) => [...prev, `Dato completado por asesor: ${dato}`]);
    setMensaje(`Dato completado: ${dato}.`);
  }

  function handleGuardarNota() {
    const n = nota.trim();
    if (!n) return;
    setNotas((prev) => [...prev, n]);
    setNota('');
    setMensaje('Nota del asesor guardada en el expediente del trámite.');
  }

  return (
    <div>
      <h1>Call Center — consola del asesor</h1>
      <p className="muted">Busca por teléfono o folio y ve todo el contexto acumulado: el paciente nunca repite su historia.</p>

      <form className="card form-inline" onSubmit={handleBuscar}>
        <input
          value={busqueda}
          onChange={(e) => setBusqueda(e.target.value)}
          placeholder="Teléfono o folio (ej. SD-2026-0001)"
          aria-label="Teléfono o folio"
        />
        <button type="submit" className="btn btn-primary" disabled={buscando}>
          {buscando ? 'Buscando…' : 'Buscar trámite'}
        </button>
      </form>

      {mensaje && <p className="notice" role="status">{mensaje}</p>}

      {tramite && (
        <div className="grid-2" style={{ marginTop: 16 }}>
          <section className="card">
            <h2>Contexto acumulado</h2>
            <p>
              Folio: <strong>{tramite.folio}</strong> <EstadoBadge estado={tramite.estado} />
            </p>
            <dl className="kv">
              <dt>Paciente</dt><dd>{tramite.paciente?.nombre ?? MOCK_PACIENTE_ANA.nombre}</dd>
              <dt>Teléfono</dt><dd>{tramite.paciente?.telefono ?? MOCK_PACIENTE_ANA.telefono}</dd>
              <dt>Estudio</dt><dd>{tramite.estudio.nombre}</dd>
              <dt>Precio cotizado</dt><dd><strong>${tramite.precioCotizado}</strong></dd>
              <dt>Clínica</dt><dd>{tramite.clinica ? `${tramite.clinica.nombre} (${tramite.clinica.ciudad})` : 'Sin seleccionar'}</dd>
              <dt>Fecha de cita</dt><dd>{tramite.fechaCita ?? 'Sin confirmar'}</dd>
              <dt>Canal de origen</dt><dd>{tramite.canalOrigen}</dd>
            </dl>

            <h3>Datos pendientes</h3>
            {tramite.datosPendientes.length === 0 ? (
              <p className="muted">Sin datos pendientes.</p>
            ) : (
              <ul>
                {tramite.datosPendientes.map((d) => (
                  <li key={d} className="dato-row">
                    <span>{d}</span>
                    <button type="button" className="btn btn-small" onClick={() => handleCompletarDato(d)}>
                      Completar dato
                    </button>
                  </li>
                ))}
              </ul>
            )}

            <h3>Nota del asesor</h3>
            <div className="form">
              <textarea value={nota} onChange={(e) => setNota(e.target.value)} rows={3} placeholder="Escribe una nota para el expediente…" />
              <button type="button" className="btn btn-secondary" onClick={handleGuardarNota}>
                Guardar nota
              </button>
            </div>
            {notas.length > 0 && (
              <ul>
                {notas.map((n, i) => (
                  <li key={i}>{n}</li>
                ))}
              </ul>
            )}
          </section>

          <section className="card">
            <h2>Timeline completa</h2>
            <Timeline eventos={timeline} />
          </section>
        </div>
      )}
    </div>
  );
}
