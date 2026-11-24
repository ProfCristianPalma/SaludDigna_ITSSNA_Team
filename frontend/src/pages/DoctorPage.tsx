import { useCallback, useEffect, useState } from 'react';
import {
  atenderTramite,
  checkInTramite,
  getCitasDoctor,
  isApiError,
  noPresentadoTramite,
  noRealizadoTramite,
} from '../api';
import type { CitaClinica } from '../api';
import type { EstadoTramite } from '../types';
import { formatearFecha, formatearFechaHora } from '../fechas';
import EstadoBadge from '../components/EstadoBadge';
import VistaPersonal from '../components/VistaPersonal';

// Doctor = tablero de citas del día: citas CONFIRMADAS de TODOS los
// pacientes, con check-in persistido, atención y cierres terminales
// (no se presentó / no se pudo realizar). Los datos vienen del DTO
// plano GET /api/doctor/citas, que ya trae el check-in del log.
//
// Vista de personal: el contenido solo se monta con sesión de DOCTOR
// o ADMINISTRADOR (VistaPersonal con roles). Sin sesión no se llama
// a la API.
function hoyISO(): string {
  const d = new Date();
  const dd = String(d.getDate()).padStart(2, '0');
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  return `${d.getFullYear()}-${mm}-${dd}`;
}

function ContenidoDoctor() {
  const [fecha, setFecha] = useState(hoyISO());
  const [citas, setCitas] = useState<CitaClinica[]>([]);
  const [mensaje, setMensaje] = useState('');
  const [cargando, setCargando] = useState(false);
  const [accionando, setAccionando] = useState<string | null>(null);
  // Motivo de "no realizado", uno por cita (solo se muestra el
  // textarea de la cita que lo pidió).
  const [motivoAbierto, setMotivoAbierto] = useState<string | null>(null);
  const [motivo, setMotivo] = useState('');

  const cargar = useCallback(async (f: string) => {
    setCargando(true);
    setMensaje('');
    try {
      setCitas(await getCitasDoctor(f));
    } catch (err) {
      setCitas([]);
      setMensaje(
        isApiError(err)
          ? `No se pudieron cargar las citas (HTTP ${err.status}: ${err.message}).`
          : 'No se pudieron cargar las citas. Revisa que el backend esté arriba.',
      );
    } finally {
      setCargando(false);
    }
  }, []);

  useEffect(() => {
    void cargar(fecha);
  }, [cargar, fecha]);

  // Tras cada acción se recarga la lista: la cita sale de CONFIRMADO
  // (o muestra su check-in) sin tener que recargar a mano.
  async function ejecutar(tramiteId: string, accion: () => Promise<unknown>, ok: string) {
    setAccionando(tramiteId);
    try {
      await accion();
      setMensaje(ok);
      setMotivoAbierto(null);
      setMotivo('');
      await cargar(fecha);
    } catch (err) {
      setMensaje(
        isApiError(err)
          ? err.message
          : 'No se pudo registrar la acción. Revisa que el backend esté arriba.',
      );
    } finally {
      setAccionando(null);
    }
  }

  function handleCheckIn(c: CitaClinica) {
    void ejecutar(c.tramiteId, () => checkInTramite(c.tramiteId), `Check-in registrado para el folio ${c.folio}.`);
  }

  function handleAtendido(c: CitaClinica) {
    void ejecutar(
      c.tramiteId,
      () => atenderTramite(c.tramiteId),
      `Atención registrada para el folio ${c.folio}. El trámite pasó a ATENDIDO.`,
    );
  }

  function handleNoPresentado(c: CitaClinica) {
    void ejecutar(
      c.tramiteId,
      () => noPresentadoTramite(c.tramiteId),
      `Folio ${c.folio} marcado como "No se presentó".`,
    );
  }

  function handleNoRealizado(c: CitaClinica) {
    const m = motivo.trim();
    if (!m) {
      setMensaje('Escribe el motivo por el que no se pudo realizar el estudio.');
      return;
    }
    void ejecutar(
      c.tramiteId,
      () => noRealizadoTramite(c.tramiteId, m),
      `Folio ${c.folio} marcado como "No realizado".`,
    );
  }

  return (
    <div>
      <h1>Doctor — citas del día</h1>
      <p className="muted">
        Citas confirmadas de todos los pacientes, listas para check-in y atención.
        El check-in queda guardado en la base de datos (no es memoria local).
      </p>
      {mensaje && <p className="notice" role="status">{mensaje}</p>}

      <div className="card form-inline" style={{ marginBottom: 16 }}>
        <label>
          Fecha
          <input type="date" value={fecha} onChange={(e) => e.target.value && setFecha(e.target.value)} />
        </label>
        <span className="muted">{fecha ? formatearFecha(fecha) : ''}</span>
        <button type="button" className="btn btn-secondary" onClick={() => void cargar(fecha)} disabled={cargando}>
          Actualizar
        </button>
      </div>

      {cargando && citas.length === 0 && <p className="muted">Cargando citas…</p>}
      {citas.length === 0 && !cargando && <p className="muted">No hay citas confirmadas en este día.</p>}

      <div className="grid-2">
        {citas.map((c) => {
          const esConfirmado = c.estado === 'CONFIRMADO';
          const ocupado = accionando === c.tramiteId;
          return (
            <article key={c.tramiteId} className="card">
              <p>
                Folio: <strong>{c.folio}</strong>{' '}
                <EstadoBadge estado={c.estado as EstadoTramite} />
              </p>
              <h2>{c.estudioNombre}</h2>
              <dl className="kv">
                <dt>Paciente</dt><dd>{c.pacienteNombre}</dd>
                <dt>Teléfono</dt><dd>{c.pacienteTelefono}</dd>
                <dt>Clínica</dt><dd>{c.clinicaNombre ? `${c.clinicaNombre} (${c.clinicaCiudad ?? ''})` : 'Sin seleccionar'}</dd>
                <dt>Fecha de cita</dt><dd>{c.fechaCita ? formatearFechaHora(c.fechaCita) : 'Sin confirmar'}</dd>
                <dt>Precio</dt><dd><strong>${c.precio ?? '—'}</strong></dd>
                <dt>Check-in</dt><dd>{c.checkIn ? 'Registrado ✓' : 'Pendiente'}</dd>
              </dl>
              <div className="actions">
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => handleCheckIn(c)}
                  disabled={!esConfirmado || c.checkIn === true || ocupado}
                >
                  Check-in
                </button>
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={() => handleAtendido(c)}
                  disabled={!esConfirmado || ocupado}
                >
                  Atendido
                </button>
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => handleNoPresentado(c)}
                  disabled={!esConfirmado || ocupado}
                >
                  No se presentó
                </button>
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => setMotivoAbierto(motivoAbierto === c.tramiteId ? null : c.tramiteId)}
                  disabled={!esConfirmado || ocupado}
                >
                  No se pudo realizar
                </button>
              </div>
              {motivoAbierto === c.tramiteId && esConfirmado && (
                <div className="form" style={{ marginTop: 12 }}>
                  <label>
                    Motivo por el que no se pudo realizar el estudio
                    <textarea
                      value={motivo}
                      onChange={(e) => setMotivo(e.target.value)}
                      rows={3}
                      placeholder="Ej. equipo fuera de servicio, paciente sin ayuno…"
                    />
                  </label>
                  <div className="actions">
                    <button
                      type="button"
                      className="btn btn-primary"
                      onClick={() => handleNoRealizado(c)}
                      disabled={ocupado}
                    >
                      Confirmar no realizado
                    </button>
                    <button
                      type="button"
                      className="btn btn-secondary"
                      onClick={() => {
                        setMotivoAbierto(null);
                        setMotivo('');
                      }}
                    >
                      Cancelar
                    </button>
                  </div>
                </div>
              )}
            </article>
          );
        })}
      </div>
    </div>
  );
}

export default function DoctorPage() {
  return (
    <VistaPersonal
      titulo="Doctor — citas del día"
      roles={['DOCTOR', 'ADMINISTRADOR']}
      etiqueta="el doctor o el administrador de Salud Digna"
    >
      <ContenidoDoctor />
    </VistaPersonal>
  );
}
