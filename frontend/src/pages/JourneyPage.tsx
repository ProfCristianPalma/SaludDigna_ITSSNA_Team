import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { getJourney } from '../api';
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
  const [folio, setFolio] = useState(id ?? 'SD-2026-0001');
  const [journey, setJourney] = useState<Journey | null>(null);
  const [cargando, setCargando] = useState(false);
  const [ultimaActualizacion, setUltimaActualizacion] = useState('');

  const cargar = useCallback(async (folioACargar: string) => {
    setCargando(true);
    try {
      const j = await getJourney(folioACargar);
      setJourney(j);
      setUltimaActualizacion(new Date().toLocaleTimeString('es-MX'));
    } catch {
      setJourney(null);
    } finally {
      setCargando(false);
    }
  }, []);

  useEffect(() => {
    void cargar(folio);
    const timer = window.setInterval(() => void cargar(folio), 5000);
    return () => window.clearInterval(timer);
  }, [cargar, folio, id]);

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
          void cargar(folio);
        }}
      >
        <input value={folio} onChange={(e) => setFolio(e.target.value)} placeholder="Folio (ej. SD-2026-0001)" aria-label="Folio" />
        <button type="submit" className="btn btn-primary" disabled={cargando}>
          Cargar trámite
        </button>
        <button type="button" className="btn btn-secondary" onClick={() => void cargar(folio)}>
          Actualizar
        </button>
        {ultimaActualizacion && <span className="muted">Última actualización: {ultimaActualizacion}</span>}
      </form>

      {!tramite && !cargando && <p className="notice">No se encontró el trámite. Verifica el folio.</p>}
      {cargando && !tramite && <p className="muted">Cargando journey…</p>}

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
