import { useState } from 'react';
import {
  buscarPacientesPorNombre,
  completarDato,
  esFolio,
  esTelefono,
  getTimeline,
  getTramitePorFolio,
  getTramitesPorTelefono,
  guardarNotaCallCenter,
} from '../api';
import type { Evento, Tramite } from '../types';
import EstadoBadge from '../components/EstadoBadge';
import Timeline from '../components/Timeline';

export default function AsesorPage() {
  const [busqueda, setBusqueda] = useState('2220000001');
  const [tramites, setTramites] = useState<Tramite[]>([]);
  const [tramite, setTramite] = useState<Tramite | null>(null);
  const [timeline, setTimeline] = useState<Evento[]>([]);
  const [nota, setNota] = useState('');
  const [mensaje, setMensaje] = useState('');
  const [buscando, setBuscando] = useState(false);
  const [guardando, setGuardando] = useState(false);

  async function cargarTramite(t: Tramite) {
    setTramite(t);
    try {
      setTimeline(await getTimeline(t.id));
    } catch {
      setTimeline([]);
    }
  }

  async function handleBuscar(e: React.FormEvent) {
    e.preventDefault();
    const q = busqueda.trim();
    if (!q) return;
    setBuscando(true);
    setMensaje('');
    setTramite(null);
    setTramites([]);
    setTimeline([]);
    try {
      let lista: Tramite[] = [];
      if (esFolio(q)) {
        lista = [await getTramitePorFolio(q)];
      } else if (esTelefono(q)) {
        lista = await getTramitesPorTelefono(q);
      } else {
        // Nombre: pacientes que coinciden, luego todos sus tramites.
        const pacientes = await buscarPacientesPorNombre(q);
        if (pacientes.length === 0) throw new Error('sin pacientes');
        for (const p of pacientes) {
          lista.push(...(await getTramitesPorTelefono(p.telefono)));
        }
      }
      if (lista.length === 0) {
        setMensaje('No se encontró ningún trámite con esa búsqueda.');
        return;
      }
      setTramites(lista);
      await cargarTramite(lista[0]);
      if (lista.length > 1) {
        setMensaje(`Encontré ${lista.length} folios. Elige uno de la lista para ver su contexto.`);
      }
    } catch {
      setMensaje('No se encontró un trámite con ese teléfono, folio o nombre. Verifica la búsqueda.');
    } finally {
      setBuscando(false);
    }
  }

  async function handleCompletarDato(dato: string) {
    if (!tramite) return;
    setGuardando(true);
    try {
      // Se guarda en la BD (POST /tramites/{id}/completar-dato) y se recarga.
      const actualizado = await completarDato(tramite.id, dato, 'completado por asesor');
      setTramite(actualizado);
      setTimeline(await getTimeline(actualizado.id));
      setTramites((prev) => prev.map((t) => (t.id === actualizado.id ? actualizado : t)));
      setMensaje(`Dato completado y guardado en la base de datos: ${dato}.`);
    } catch {
      setMensaje('No se pudo completar el dato. Revisa que el backend esté arriba.');
    } finally {
      setGuardando(false);
    }
  }

  async function handleGuardarNota() {
    const n = nota.trim();
    if (!n || !tramite) return;
    setGuardando(true);
    try {
      // Queda en el log de eventos (POST /canales/call-center/nota) y se recarga el timeline.
      await guardarNotaCallCenter(tramite.id, n);
      setNota('');
      setTimeline(await getTimeline(tramite.id));
      setMensaje('Nota del asesor guardada en el expediente del trámite.');
    } catch {
      setMensaje('No se pudo guardar la nota. Revisa que el backend esté arriba.');
    } finally {
      setGuardando(false);
    }
  }

  return (
    <div>
      <h1>Call Center — consola del asesor</h1>
      <p className="muted">Busca por teléfono, folio o nombre y ve todo el contexto acumulado: el paciente nunca repite su historia.</p>

      <form className="card form-inline" onSubmit={handleBuscar}>
        <input
          value={busqueda}
          onChange={(e) => setBusqueda(e.target.value)}
          placeholder="Teléfono, folio (ej. SD-2026-0001) o nombre"
          aria-label="Teléfono, folio o nombre"
        />
        <button type="submit" className="btn btn-primary" disabled={buscando}>
          {buscando ? 'Buscando…' : 'Buscar trámite'}
        </button>
      </form>

      {mensaje && <p className="notice" role="status">{mensaje}</p>}

      {tramites.length > 1 && (
        <section className="card" style={{ marginTop: 16 }}>
          <h2>Folios del paciente</h2>
          <ul>
            {tramites.map((t) => (
              <li key={t.id} className="dato-row">
                <span>
                  <strong>{t.folio}</strong> · {t.estudio.nombre} — ${t.precioCotizado} <EstadoBadge estado={t.estado} />
                </span>
                <button type="button" className="btn btn-small" onClick={() => void cargarTramite(t)}>
                  Ver
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      {tramite && (
        <div className="grid-2" style={{ marginTop: 16 }}>
          <section className="card">
            <h2>Contexto acumulado</h2>
            <p>
              Folio: <strong>{tramite.folio}</strong> <EstadoBadge estado={tramite.estado} />
            </p>
            <dl className="kv">
              <dt>Paciente</dt><dd>{tramite.paciente?.nombre ?? '—'}</dd>
              <dt>Teléfono</dt><dd>{tramite.paciente?.telefono ?? '—'}</dd>
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
                    <button type="button" className="btn btn-small" onClick={() => void handleCompletarDato(d)} disabled={guardando}>
                      Completar dato
                    </button>
                  </li>
                ))}
              </ul>
            )}

            <h3>Nota del asesor</h3>
            <div className="form">
              <textarea value={nota} onChange={(e) => setNota(e.target.value)} rows={3} placeholder="Escribe una nota para el expediente…" />
              <button type="button" className="btn btn-secondary" onClick={() => void handleGuardarNota()} disabled={guardando}>
                Guardar nota
              </button>
            </div>
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
