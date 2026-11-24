import { useCallback, useEffect, useState } from 'react';
import { atenderTramite, getCitasClinica, isApiError, registrarEventoCheckIn } from '../api';
import type { CitaClinica } from '../api';
import EstadoBadge from '../components/EstadoBadge';
import VistaPersonal from '../components/VistaPersonal';

// Clínica = recepción: citas de TODOS los pacientes (no el historial
// de un solo paciente; eso es /historial). Los datos vienen del DTO
// plano GET /api/clinica/citas, no de entidades JPA.
//
// Vista de personal: el contenido solo se monta con sesión de asesor
// o admin (VistaPersonal). Sin sesión no se llama a la API.
type Filtro = 'CONFIRMADO' | 'ATENDIDO' | 'TODOS';

function ContenidoClinica() {
  const [citas, setCitas] = useState<CitaClinica[]>([]);
  const [filtro, setFiltro] = useState<Filtro>('CONFIRMADO');
  const [busqueda, setBusqueda] = useState('');
  const [checkIn, setCheckIn] = useState<Record<string, boolean>>({});
  const [mensaje, setMensaje] = useState('');
  const [cargando, setCargando] = useState(false);

  const cargar = useCallback(async (f: Filtro) => {
    setCargando(true);
    setMensaje('');
    try {
      setCitas(await getCitasClinica(f));
    } catch (err) {
      // Error REAL (status HTTP y mensaje del backend), no el genérico.
      setCitas([]);
      setMensaje(
        isApiError(err)
          ? `No se pudieron cargar las citas (HTTP ${err.status}: ${err.message}). Revisa que el backend esté arriba y que hayas hecho git pull + docker compose up --build con el último cambio.`
          : 'No se pudieron cargar las citas. Revisa que el backend esté arriba.',
      );
    } finally {
      setCargando(false);
    }
  }, []);

  useEffect(() => {
    void cargar(filtro);
  }, [cargar, filtro]);

  async function handleAtendido(tramiteId: string) {
    setCargando(true);
    try {
      // POST /tramites/{id}/atender: CONFIRMADO -> ATENDIDO en la BD.
      await atenderTramite(tramiteId);
      setMensaje('Atención registrada en la base de datos. El trámite pasó a ATENDIDO.');
      await cargar(filtro); // El atendido ya no aparece como pendiente.
    } catch (err) {
      setMensaje(
        isApiError(err)
          ? `No se pudo registrar la atención (HTTP ${err.status}: ${err.message}).`
          : 'No se pudo registrar la atención. Revisa que el backend esté arriba.',
      );
    } finally {
      setCargando(false);
    }
  }

  async function handleCheckIn(tramiteId: string) {
    try {
      // Check-in queda en el log de eventos (POST /tramites/{id}/eventos).
      await registrarEventoCheckIn(tramiteId);
      setCheckIn((prev) => ({ ...prev, [tramiteId]: true }));
      setMensaje('Check-in registrado en la base de datos. El paciente ya está en sala de espera.');
    } catch (err) {
      setMensaje(
        isApiError(err)
          ? `No se pudo registrar el check-in (HTTP ${err.status}: ${err.message}).`
          : 'No se pudo registrar el check-in. Revisa que el backend esté arriba.',
      );
    }
  }

  // Buscador por folio, teléfono o nombre sobre la lista ya cargada.
  const q = busqueda.trim().toLowerCase();
  const visibles = q
    ? citas.filter(
        (c) =>
          c.folio.toLowerCase().includes(q) ||
          c.pacienteTelefono.replace(/\D/g, '').includes(q.replace(/\D/g, '')) ||
          c.pacienteNombre.toLowerCase().includes(q),
      )
    : citas;

  return (
    <div>
      <h1>Clínica — recepción</h1>
      <p className="muted">
        Citas de todos los pacientes, listas para check-in y atención. Esto no es el historial de un paciente:
        para ver tus folios entra a <a href="/historial">Historial</a>.
      </p>
      {mensaje && <p className="notice" role="status">{mensaje}</p>}

      <div className="card form-inline" style={{ marginBottom: 16 }}>
        <button
          type="button"
          className={filtro === 'CONFIRMADO' ? 'btn btn-primary' : 'btn btn-secondary'}
          onClick={() => setFiltro('CONFIRMADO')}
        >
          Confirmados
        </button>
        <button
          type="button"
          className={filtro === 'ATENDIDO' ? 'btn btn-primary' : 'btn btn-secondary'}
          onClick={() => setFiltro('ATENDIDO')}
        >
          Atendidos
        </button>
        <button
          type="button"
          className={filtro === 'TODOS' ? 'btn btn-primary' : 'btn btn-secondary'}
          onClick={() => setFiltro('TODOS')}
        >
          Todos
        </button>
        <input
          value={busqueda}
          onChange={(e) => setBusqueda(e.target.value)}
          placeholder="Buscar por folio, teléfono o nombre"
          aria-label="Buscar cita"
        />
        <button type="button" className="btn btn-secondary" onClick={() => void cargar(filtro)} disabled={cargando}>
          Actualizar
        </button>
      </div>

      {cargando && citas.length === 0 && <p className="muted">Cargando citas…</p>}
      {visibles.length === 0 && !cargando && <p className="muted">No hay citas en este filtro.</p>}

      <div className="grid-2">
        {visibles.map((c) => (
          <article key={c.tramiteId} className="card">
            <p>
              Folio: <strong>{c.folio}</strong> <EstadoBadge estado={c.estado as 'CONFIRMADO' | 'ATENDIDO'} />
            </p>
            <h2>{c.estudioNombre}</h2>
            <dl className="kv">
              <dt>Paciente</dt><dd>{c.pacienteNombre}</dd>
              <dt>Teléfono</dt><dd>{c.pacienteTelefono}</dd>
              <dt>Clínica</dt><dd>{c.clinicaNombre ? `${c.clinicaNombre} (${c.clinicaCiudad ?? ''})` : 'Sin seleccionar'}</dd>
              <dt>Fecha de cita</dt><dd>{c.fechaCita ?? 'Sin confirmar'}</dd>
              <dt>Precio</dt><dd><strong>${c.precio ?? '—'}</strong></dd>
              <dt>Check-in</dt><dd>{checkIn[c.tramiteId] ? 'Registrado ✓' : 'Pendiente'}</dd>
            </dl>
            <div className="actions">
              <button
                type="button"
                className="btn btn-secondary"
                onClick={() => void handleCheckIn(c.tramiteId)}
                disabled={checkIn[c.tramiteId] || c.estado !== 'CONFIRMADO'}
              >
                Check-in
              </button>
              <button
                type="button"
                className="btn btn-primary"
                onClick={() => void handleAtendido(c.tramiteId)}
                disabled={c.estado !== 'CONFIRMADO' || cargando}
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

export default function ClinicaPage() {
  return (
    <VistaPersonal titulo="Clínica — recepción">
      <ContenidoClinica />
    </VistaPersonal>
  );
}
