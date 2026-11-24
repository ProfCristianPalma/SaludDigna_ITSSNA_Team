import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { esTelefono, getJourney, getTramitesPorTelefono } from '../api';
import type { EstadoTramite, Evento, Journey, Tramite } from '../types';
import EstadoBadge from '../components/EstadoBadge';
import Timeline from '../components/Timeline';

const PASOS: EstadoTramite[] = [
  'INICIADO',
  'DATOS_PENDIENTES',
  'COTIZADO',
  'CLINICA_SELECCIONADA',
  'CONFIRMADO',
  'ATENDIDO',
];

const PASO_LABEL: Record<EstadoTramite, string> = {
  INICIADO: 'Iniciado',
  DATOS_PENDIENTES: 'Datos pendientes',
  COTIZADO: 'Cotizado',
  CLINICA_SELECCIONADA: 'Clínica seleccionada',
  CONFIRMADO: 'Confirmado',
  ATENDIDO: 'Atendido',
  CANCELADO: 'Cancelado',
};

const CANAL_ICON: Record<string, string> = {
  WEB: '🌐',
  WHATSAPP: '💬',
  CALL_CENTER: '📞',
  APP: '📱',
  CLINICA: '🏥',
};

export default function JourneyPage() {
  const { id } = useParams<{ id?: string }>();
  const [busqueda, setBusqueda] = useState(id ?? 'SD-2026-0001');
  const [journey, setJourney] = useState<Journey | null>(null);
  const [folios, setFolios] = useState<Tramite[]>([]);
  const [cargando, setCargando] = useState(false);
  const [ultimaActualizacion, setUltimaActualizacion] = useState('');

  const cargarJourney = useCallback(async (folio: string) => {
    setCargando(true);
    try {
      const j = await getJourney(folio);
      setJourney(j);
      setFolios([]);
      setUltimaActualizacion(new Date().toLocaleTimeString('es-MX'));
    } catch {
      setJourney(null);
    } finally {
      setCargando(false);
    }
  }, []);

  /** Acepta folio, telefono o UUID. Con telefono, muestra sus folios para elegir uno. */
  const cargar = useCallback(async (q: string) => {
    const query = q.trim();
    if (!query) return;
    if (esTelefono(query)) {
      setCargando(true);
      try {
        const lista = await getTramitesPorTelefono(query);
        if (lista.length === 0) {
          setJourney(null);
          setFolios([]);
          return;
        }
        if (lista.length === 1) {
          await cargarJourney(lista[0].folio);
          return;
        }
        setJourney(null);
        setFolios(lista);
      } catch {
        setJourney(null);
        setFolios([]);
      } finally {
        setCargando(false);
      }
      return;
    }
    await cargarJourney(query);
  }, [cargarJourney]);

  useEffect(() => {
    void cargar(busqueda);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Poll cada 5 s, pero solo del folio ya cargado (no del telefono).
  const folioCargado = journey?.tramite.folio;
  useEffect(() => {
    if (!folioCargado) return;
    const timer = window.setInterval(() => void cargarJourney(folioCargado), 5000);
    return () => window.clearInterval(timer);
  }, [folioCargado, cargarJourney]);

  const tramite: Tramite | null = journey?.tramite ?? null;
  const timeline: Evento[] = journey?.timeline ?? [];
  const indiceActual = tramite ? PASOS.indexOf(tramite.estado) : -1;
  const canales = Array.from(new Set(timeline.map((e) => e.canal)));

  return (
    <div>
      <h1>Patient Journey — en tiempo real</h1>
      <p className="muted">Un paciente, una sola conversación: todos los canales en un solo tablero. Se actualiza cada 5 segundos.</p>

      <form
        className="card form-inline"
        onSubmit={(e) => {
          e.preventDefault();
          void cargar(busqueda);
        }}
      >
        <input value={busqueda} onChange={(e) => setBusqueda(e.target.value)} placeholder="Folio (ej. SD-2026-0001), teléfono o UUID" aria-label="Folio, teléfono o UUID" />
        <button type="submit" className="btn btn-primary" disabled={cargando}>
          Cargar trámite
        </button>
        <button type="button" className="btn btn-secondary" onClick={() => void cargar(busqueda)}>
          Actualizar
        </button>
        {ultimaActualizacion && <span className="muted">Última actualización: {ultimaActualizacion}</span>}
      </form>

      {folios.length > 1 && (
        <section className="card" style={{ marginTop: 16 }}>
          <h2>Folios de ese teléfono</h2>
          <p className="muted">Ese paciente tiene varios folios, uno por estudio. Elige uno para ver su Journey.</p>
          <ul>
            {folios.map((t) => (
              <li key={t.id} className="dato-row">
                <span>
                  <strong>{t.folio}</strong> · {t.estudio.nombre} — ${t.precioCotizado} <EstadoBadge estado={t.estado} />
                </span>
                <button type="button" className="btn btn-small" onClick={() => void cargarJourney(t.folio)}>
                  Ver Journey
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      {!tramite && !cargando && folios.length === 0 && <p className="notice">No se encontró el trámite. Verifica el folio, teléfono o UUID.</p>}
      {cargando && !tramite && folios.length === 0 && <p className="muted">Cargando journey…</p>}

      {tramite && (
        <div style={{ marginTop: 16 }}>
          <section className="card">
            <p>
              Folio: <strong>{tramite.folio}</strong> <EstadoBadge estado={tramite.estado} />
            </p>
            <p>
              {tramite.paciente?.nombre} · {tramite.estudio.nombre} — <strong>${tramite.precioCotizado}</strong>
              {tramite.clinica ? ` · ${tramite.clinica.nombre} (${tramite.clinica.ciudad})` : ''}
              {tramite.fechaCita ? ` · Cita: ${tramite.fechaCita}` : ''}
            </p>

            <ol className="stepper" aria-label="Progreso del trámite">
              {PASOS.map((paso, i) => (
                <li key={paso} className={i <= indiceActual ? 'step step-done' : 'step'}>
                  <span className="step-dot" aria-hidden="true">{i < indiceActual ? '✓' : i + 1}</span>
                  <span>{PASO_LABEL[paso]}</span>
                </li>
              ))}
            </ol>
          </section>

          <section className="card" style={{ marginTop: 16 }}>
            <h2>Canales recorridos</h2>
            {canales.length === 0 ? (
              <p className="muted">Sin canales registrados todavía.</p>
            ) : (
              <div className="canales">
                {canales.map((c) => (
                  <span key={c} className="chip chip-grande">
                    {CANAL_ICON[c] ?? '•'} {c}
                  </span>
                ))}
              </div>
            )}
          </section>

          <section className="card" style={{ marginTop: 16 }}>
            <h2>Timeline unificada</h2>
            <Timeline eventos={timeline} />
          </section>
        </div>
      )}
    </div>
  );
}
