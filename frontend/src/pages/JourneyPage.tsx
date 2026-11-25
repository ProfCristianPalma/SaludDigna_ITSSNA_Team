import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { buscarPacientePorCurp, esTelefono, getJourney, getTramitesPorPacienteId, getTramitesPorTelefono, isApiError } from '../api';
import { esCurp } from '../curp';
import { formatearFechaHora } from '../fechas';
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
  NO_PRESENTADO: 'No se presentó',
  NO_REALIZADO: 'No realizado',
  CANCELADO: 'Cancelado',
};

const CANAL_ICON: Record<string, string> = {
  WEB: '🌐',
  WHATSAPP: '💬',
  CALL_CENTER: '📞',
  APP: '📱',
  CLINICA: '🏥',
};

/**
 * Canal del paso: el evento que marca ese paso hecho (no el canal de
 * origen del trámite). Cada paso muestra el canal donde REALMENTE se
 * hizo: un trámite iniciado en WhatsApp y terminado en Web muestra
 * WHATSAPP en los primeros pasos y WEB en los últimos (2026-11-24:
 * antes todos los eventos llevaban el canal de origen y el Journey
 * solo decía WHATSAPP).
 */
const CANAL_POR_PASO: Record<EstadoTramite, string> = {
  INICIADO: 'tramite.iniciado',
  DATOS_PENDIENTES: 'dato.pendiente.completado',
  COTIZADO: 'cotizacion.creada',
  CLINICA_SELECCIONADA: 'clinica.seleccionada',
  CONFIRMADO: 'cita.confirmada',
  ATENDIDO: 'atencion.concluida',
  NO_PRESENTADO: 'cita.no_presentado',
  NO_REALIZADO: 'cita.no_realizado',
  CANCELADO: 'tramite.cancelado',
};

export default function JourneyPage() {
  const { id } = useParams<{ id?: string }>();
  // Sin folio de ejemplo cargado: la búsqueda arranca vacía (o con el
  // folio de la URL, si se llegó desde otra página).
  const [busqueda, setBusqueda] = useState(id ?? '');
  const [journey, setJourney] = useState<Journey | null>(null);
  const [folios, setFolios] = useState<Tramite[]>([]);
  const [cargando, setCargando] = useState(false);
  const [ultimaActualizacion, setUltimaActualizacion] = useState('');
  const [error, setError] = useState('');

  const cargarJourney = useCallback(async (folio: string) => {
    setCargando(true);
    setError('');
    try {
      const j = await getJourney(folio);
      setJourney(j);
      setFolios([]);
      setUltimaActualizacion(new Date().toLocaleTimeString('es-MX'));
    } catch (err) {
      setJourney(null);
      // Error REAL con su status: un 404 es "folio no existe", pero un
      // 500 (o cualquier otro) es un fallo del backend y debe decirlo,
      // no disfrazarse de "no encontrado".
      setError(
        isApiError(err)
          ? err.status === 404
            ? 'No se encontró el trámite. Verifica el folio, teléfono o UUID.'
            : `No se pudo cargar el Journey (HTTP ${err.status}: ${err.message}). Revisa que el backend esté arriba y que hayas hecho git pull + docker compose up --build con el último cambio.`
          : 'El backend no respondió, no se pudo cargar el Journey. Revisa que el backend esté arriba.',
      );
    } finally {
      setCargando(false);
    }
  }, []);

  /** Acepta folio, CURP, telefono o UUID. Con CURP o teléfono, muestra sus folios para elegir uno. */
  const cargar = useCallback(async (q: string) => {
    const query = q.trim();
    if (!query) return;
    setError('');
    if (esCurp(query)) {
      setCargando(true);
      try {
        const p = await buscarPacientePorCurp(query);
        // Los folios eliminados (borrado suave) no salen de la lista:
        // siguen abriéndose por folio directo, solo lectura.
        const lista = (await getTramitesPorPacienteId(p.id)).filter((t) => !t.eliminado);
        if (lista.length === 0) {
          setJourney(null);
          setFolios([]);
          setError('Ese paciente aún no tiene folios.');
          return;
        }
        if (lista.length === 1) {
          await cargarJourney(lista[0].folio);
          return;
        }
        setJourney(null);
        setFolios(lista);
      } catch (err) {
        setJourney(null);
        setFolios([]);
        setError(
          isApiError(err)
            ? 'No se encontró ningún folio con esa CURP.'
            : 'El backend no respondió, no se pudo buscar. Revisa que el backend esté arriba.',
        );
      } finally {
        setCargando(false);
      }
      return;
    }
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
      } catch (err) {
        setJourney(null);
        setFolios([]);
        setError(
          isApiError(err)
            ? 'No se encontró ningún folio con ese teléfono.'
            : 'El backend no respondió, no se pudo buscar. Revisa que el backend esté arriba.',
        );
      } finally {
        setCargando(false);
      }
      return;
    }
    await cargarJourney(query);
  }, [cargarJourney]);

  useEffect(() => {
    if (busqueda.trim()) void cargar(busqueda);
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
  /** Canal de cada paso, del evento que lo marcó (último evento de ese tipo). */
  const canalPorPaso = (paso: EstadoTramite): string | null => {
    const tipo = CANAL_POR_PASO[paso];
    const ev = [...timeline].reverse().find((e) => e.tipo === tipo);
    return ev ? ev.canal : null;
  };

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
        <input value={busqueda} onChange={(e) => setBusqueda(e.target.value)} placeholder="Folio (ej. SD-2026-0001), CURP, teléfono o UUID" aria-label="Folio, CURP, teléfono o UUID" />
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
          <h2>Folios de ese paciente</h2>
          <p className="muted">Ese paciente tiene varios folios, uno por estudio. Elige uno para ver su Journey.</p>
          <ul>
            {folios.map((t) => (
              <li key={t.id} className="dato-row">
                <span>
                  <strong>{t.folio}</strong> · {t.estudio.nombre} — ${t.precioCotizado} <EstadoBadge estado={t.estado} clinica={t.clinica?.nombre} />
                </span>
                <button type="button" className="btn btn-small" onClick={() => void cargarJourney(t.folio)}>
                  Ver Journey
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      {!tramite && !cargando && folios.length === 0 && (
        <p className="notice">{error || 'Escribe un folio, una CURP, un teléfono o un UUID y pulsa "Cargar trámite".'}</p>
      )}
      {cargando && !tramite && folios.length === 0 && <p className="muted">Cargando journey…</p>}

      {tramite && (
        <div style={{ marginTop: 16 }}>
          <section className="card">
            <p>
              Folio: <strong>{tramite.folio}</strong> <EstadoBadge estado={tramite.estado} clinica={tramite.clinica?.nombre} />
            </p>
            <p>
              {tramite.paciente?.nombre} · {tramite.estudio.nombre} — <strong>${tramite.precioCotizado}</strong>
              {tramite.clinica ? ` · ${tramite.clinica.nombre} (${tramite.clinica.ciudad})` : ''}
              {tramite.fechaCita ? ` · Cita: ${formatearFechaHora(tramite.fechaCita)}` : ''}
            </p>

            <ol className="stepper" aria-label="Progreso del trámite">
              {PASOS.map((paso, i) => (
                <li key={paso} className={i <= indiceActual ? 'step step-done' : 'step'}>
                  <span className="step-dot" aria-hidden="true">{i < indiceActual ? '✓' : i + 1}</span>
                  <span>{paso === 'CLINICA_SELECCIONADA' && tramite?.clinica ? tramite.clinica.nombre : PASO_LABEL[paso]}</span>
                  {i <= indiceActual && canalPorPaso(paso) && (
                    <span className="chip" title={`Este paso se hizo por ${canalPorPaso(paso)}`}>
                      {CANAL_ICON[canalPorPaso(paso) as string] ?? '•'} {canalPorPaso(paso)}
                    </span>
                  )}
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
