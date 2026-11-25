import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  confirmar,
  cotizar,
  crearTramite,
  getClinicas,
  getEstudios,
  getPerfilYo,
  getToken,
  getTramite,
  getTramitesPorPacienteId,
  isApiError,
  logout,
  parsearTramiteExistente,
  seleccionarClinica,
  textoFaltantes,
} from '../api';
import { esFechaLatinaValida, formatearFecha, formatearFechaHora, parsearFechaLatina } from '../fechas';
import type { Clinica, EstadoTramite, Estudio, Paciente, Tramite } from '../types';
import { siguientePaso } from '../types';
import EstadoBadge from '../components/EstadoBadge';
import ParrillaHorarios from '../components/ParrillaHorarios';

/** Estados que cuentan como cita activa o pendiente (los terminales van al Historial). */
const ESTADOS_ACTIVOS: EstadoTramite[] = [
  'INICIADO',
  'DATOS_PENDIENTES',
  'COTIZADO',
  'CLINICA_SELECCIONADA',
  'CONFIRMADO',
];

export default function AppPage() {
  const [paciente, setPaciente] = useState<Paciente | null>(null);
  const [tramites, setTramites] = useState<Tramite[]>([]);
  const [mensaje, setMensaje] = useState('');
  const [sinSesion, setSinSesion] = useState(false);
  const [cargando, setCargando] = useState(true);
  const navigate = useNavigate();

  // Citas activas/pendientes: NO se muestran al entrar; se ven solo
  // al pulsar el botón (que lleva el contador, sin revelar la lista).
  const [mostrarCitas, setMostrarCitas] = useState(false);

  // Nueva cita/estudio dentro de la App: mismo flujo que Web y
  // WhatsApp (estudio → clínica → fecha/hora con parrilla) y las
  // mismas reglas de no duplicación (409 con el folio existente).
  const [nuevaCitaAbierta, setNuevaCitaAbierta] = useState(false);
  const [estudios, setEstudios] = useState<Estudio[]>([]);
  const [clinicas, setClinicas] = useState<Clinica[]>([]);
  const [catalogosCargados, setCatalogosCargados] = useState(false);
  const [estudioId, setEstudioId] = useState('');
  const [tramite, setTramite] = useState<Tramite | null>(null);
  const [clinicaId, setClinicaId] = useState('');
  const [fechaISO, setFechaISO] = useState('');
  const [fechaLatina, setFechaLatina] = useState('');
  const [hora, setHora] = useState('');
  const [reservando, setReservando] = useState(false);

  const cargar = useCallback(async () => {
    // Sin sesión no hay paciente que mostrar: la App exige login.
    if (!getToken()) {
      setSinSesion(true);
      setCargando(false);
      return;
    }
    setCargando(true);
    setMensaje('');
    try {
      const perfil = await getPerfilYo();
      if (!perfil.paciente) {
        setMensaje('Esta vista es para pacientes. Tu sesión es de staff: usa Asesor o Clínica.');
        setCargando(false);
        return;
      }
      setPaciente(perfil.paciente);
      // Solo los folios de ESTE paciente, por su id de sesión (ya no
      // por teléfono: el paciente puede no tener teléfono).
      const lista = await getTramitesPorPacienteId(perfil.paciente.id);
      setTramites(lista);
      if (lista.length === 0) setMensaje('Aún no tienes folios. Crea tu primera cita con el botón "Nueva cita".');
    } catch (err) {
      if (isApiError(err) && err.status === 401) {
        setSinSesion(true);
      } else {
        setMensaje('No se pudo cargar tu perfil. Verifica que el backend esté arriba.');
      }
    } finally {
      setCargando(false);
    }
  }, []);

  useEffect(() => {
    void cargar();
  }, [cargar]);

  // Recarga al volver a la página: los folios creados en Web o
  // avanzados en WhatsApp aparecen sin recargar a mano.
  useEffect(() => {
    const alVolver = () => {
      if (!document.hidden) void cargar();
    };
    window.addEventListener('focus', alVolver);
    document.addEventListener('visibilitychange', alVolver);
    return () => {
      window.removeEventListener('focus', alVolver);
      document.removeEventListener('visibilitychange', alVolver);
    };
  }, [cargar]);

  function cerrarSesion() {
    logout();
    setPaciente(null);
    setTramites([]);
    navigate('/');
  }

  // Refresca solo la lista de folios (sin tocar el mensaje de la
  // reservación en curso, que cargar() sí borraría).
  async function recargarFolios() {
    if (!paciente) return;
    try {
      setTramites(await getTramitesPorPacienteId(paciente.id));
    } catch {
      // Se queda la lista anterior; el mensaje ya dice qué pasó.
    }
  }

  const primerNombre = paciente?.nombre.split(' ')[0] ?? 'Hola';
  const tramitePrincipal = tramites[0] ?? null;
  const citasActivas = tramites.filter((t) => ESTADOS_ACTIVOS.includes(t.estado));

  // --- Nueva cita: catálogo y flujo (igual que en Web) -----------------
  async function abrirNuevaCita() {
    setNuevaCitaAbierta(true);
    setMensaje('');
    if (!catalogosCargados) {
      try {
        const [listaEstudios, listaClinicas] = await Promise.all([getEstudios(), getClinicas()]);
        setEstudios(listaEstudios);
        setClinicas(listaClinicas);
        // Sin estudio pre-seleccionado: el estudio lo elige el paciente
        // siempre (como la clínica); nada viene asignado por defecto.
        setCatalogosCargados(true);
      } catch {
        setMensaje('No se pudo cargar el catálogo de estudios y clínicas. Revisa que el backend esté arriba.');
      }
    }
  }

  function fijarFechaISO(iso: string) {
    setFechaISO(iso);
    setFechaLatina(iso ? formatearFecha(iso) : '');
  }

  function fijarFechaLatina(texto: string) {
    setFechaLatina(texto);
    // Solo se sincroniza el calendario cuando la fecha manual es real.
    if (esFechaLatinaValida(texto)) setFechaISO(parsearFechaLatina(texto) ?? '');
  }

  const estudioSeleccionado = estudios.find((e) => e.id === estudioId);

  // Clínica efectiva: la del desplegable o, si el trámite ya trae una,
  // la del trámite. Así el calendario y la parrilla aparecen en cuanto
  // se elige estudio y clínica, sin obligar a pulsar "Seleccionar clínica".
  const clinicaEfectivaId = clinicaId || tramite?.clinica?.id || '';

  // Carga un trámite existente en el panel de reservación para
  // continuar con solo los pasos que le faltan (retomar).
  function retomarEnPanel(t: Tramite) {
    setTramite(t);
    setClinicaId('');
    fijarFechaISO('');
    setHora('');
  }

  async function handleReservar(e: React.FormEvent) {
    e.preventDefault();
    if (!paciente || !estudioId) return;
    // Folios que ya existían antes de este intento, para detectar en
    // la recuperación si el folio sí se creó aunque la respuesta tardó.
    let idsPrevios = new Set(tramites.map((t) => t.id));
    try {
      idsPrevios = new Set((await getTramitesPorPacienteId(paciente.id)).map((t) => t.id));
    } catch {
      // Sin red no hay lista previa: la recuperación lo intentará igual.
    }
    setReservando(true);
    setMensaje('');
    try {
      // Cada Reservación crea un folio NUEVO solo si el paciente no
      // tiene ya un trámite ACTIVO de ese estudio: si lo tiene, el
      // backend devuelve 409 con el folio existente para retomarlo
      // (ver catch). El paciente y sus datos vienen de la sesión.
      const creado = await crearTramite({
        nombre: paciente.nombre,
        telefono: paciente.telefono,
        pacienteId: paciente.id,
        ...(paciente.fechaNacimiento ? { fechaNacimiento: paciente.fechaNacimiento } : {}),
        estudioId,
        canal: 'APP',
      });
      const cotizado = await cotizar(creado.id, estudioId, 'APP');
      retomarEnPanel(cotizado);
      setMensaje(`Cotización lista. Folio ${cotizado.folio}.`);
      await recargarFolios();
    } catch (err) {
      // Retomar en vez de duplicar: el paciente ya tiene un trámite
      // ACTIVO de ese estudio (409 con el folio existente y lo que le
      // falta). Se carga ese folio y se continúa con solo los pasos
      // que le faltan, sin crear uno nuevo. Va ANTES de la
      // recuperación de falso negativo: ese folio ya existía antes
      // del intento, no es uno que se creó con este clic.
      const existente = parsearTramiteExistente(err);
      if (existente) {
        try {
          const t = await getTramite(existente.tramiteId);
          retomarEnPanel(t);
          setMensaje(
            // CONFIRMADO: la cita ya está agendada; se da el resumen
            // completo (estudio, costo, clínica, fecha y hora) y NO se
            // ofrece continuar el flujo. En los demás estados se
            // ofrece retomar con solo los pasos que faltan.
            existente.yaAgendada
              ? existente.mensaje
              : `${existente.mensaje} Te falta: ${textoFaltantes(existente.faltantes)}. Continúa abajo con ese folio, sin crear uno nuevo.`,
          );
          await recargarFolios();
          return;
        } catch {
          setMensaje(existente.mensaje);
          return;
        }
      }
      // Falso negativo: con el backend frío la respuesta de crear o de
      // cotizar puede tardar y el cliente aborta, pero el servidor
      // igual guarda el folio. Antes de decir que no se creó nada, se
      // vuelve a consultar la BD: si apareció un folio nuevo con este
      // estudio, se muestra en el panel como creado.
      try {
        const lista = await getTramitesPorPacienteId(paciente.id);
        let nuevo =
          lista.find((t) => !idsPrevios.has(t.id) && t.estudio.id === estudioId) ??
          lista.find((t) => !idsPrevios.has(t.id));
        if (nuevo && (nuevo.estado === 'INICIADO' || nuevo.estado === 'DATOS_PENDIENTES')) {
          // El folio se creó pero el paso de cotizar no llegó a
          // confirmarse en el front: se reintenta una vez (timeout largo).
          try {
            nuevo = await cotizar(nuevo.id, estudioId, 'APP');
          } catch {
            // Se queda el folio creado sin cotizar; se muestra igual.
          }
        }
        if (nuevo) {
          retomarEnPanel(nuevo);
          setMensaje(
            `La respuesta tardó, pero tu folio sí se creó: ${nuevo.folio}. Ya lo puedes ver en tus citas activas.`,
          );
          await recargarFolios();
          return;
        }
      } catch {
        // Sin red no se puede verificar si el folio se creó.
      }
      setMensaje(
        'No se pudo cotizar: el backend no respondió. Revisa tus citas activas: si el folio se creó, aparecerá ahí.',
      );
    } finally {
      setReservando(false);
    }
  }

  async function handleClinica() {
    if (!tramite || !clinicaEfectivaId) return;
    setReservando(true);
    try {
      const actualizado = await seleccionarClinica(tramite.id, clinicaEfectivaId, 'APP');
      setTramite(actualizado);
      setMensaje(`Clínica seleccionada: ${actualizado.clinica?.nombre ?? ''}.`);
      await recargarFolios();
    } catch {
      setMensaje('No se pudo seleccionar la clínica: el backend no respondió y no se guardó nada. Revisa que el backend esté arriba.');
    } finally {
      setReservando(false);
    }
  }

  async function handleConfirmar() {
    if (!tramite || !fechaISO || !hora) return;
    if (!esFechaLatinaValida(fechaLatina) && fechaLatina) {
      setMensaje('La fecha no es válida. Escríbela como DD/MM/AAAA, por ejemplo 25/11/2026.');
      return;
    }
    setReservando(true);
    try {
      // Si la clínica se eligió en el desplegable pero aún no se pulsó
      // "Seleccionar clínica", se guarda ahora: confirmar exige
      // CLINICA_SELECCIONADA y rechazaría la reservación.
      let tramiteActual = tramite;
      if (tramite.estado === 'COTIZADO' && clinicaEfectivaId) {
        tramiteActual = await seleccionarClinica(tramite.id, clinicaEfectivaId, 'APP');
        setTramite(tramiteActual);
      }
      const fechaHoraISO = `${fechaISO}T${hora}:00`;
      const confirmado = await confirmar(tramiteActual.id, fechaHoraISO, 'APP');
      setTramite(confirmado);
      setMensaje(
        `Reservación confirmada para el ${formatearFechaHora(confirmado.fechaCita)}. Folio ${confirmado.folio}.`,
      );
      await recargarFolios();
    } catch (err) {
      // 409 (hueco ocupado) o 400 (fecha/hora pasada): el mensaje del
      // backend se muestra tal cual, sin inventar otro.
      setMensaje(
        isApiError(err)
          ? err.message
          : 'No se pudo confirmar la reservación: el backend no respondió y no se guardó nada.',
      );
    } finally {
      setReservando(false);
    }
  }

  return (
    <div className="phone-wrap">
      <div className="phone">
        <div className="phone-header">
          <span className="brand-mark" aria-hidden="true">SD</span>
          <strong>Salud Digna</strong>
        </div>
        <div className="phone-body">
          {sinSesion ? (
            <>
              <h1>Hola 👋</h1>
              <p className="muted">Para ver tus citas, primero inicia sesión con tu CURP y tu contraseña.</p>
              <div className="actions">
                <Link className="btn btn-primary" to="/login">Iniciar sesión</Link>
                <Link className="btn btn-secondary" to="/registro">Crear cuenta</Link>
              </div>
              <p className="muted">Cuenta de prueba: 2220000001 / ana123</p>
            </>
          ) : (
            <>
              <h1>Hola, {primerNombre} 👋</h1>
              <p className="muted">Tu salud, en una sola conversación.</p>
              {paciente && <p className="muted">{paciente.nombre} · {paciente.curp ?? paciente.telefono ?? 'Sin teléfono'}</p>}
              <div className="actions" style={{ marginBottom: 12 }}>
                <button type="button" className="btn btn-secondary" onClick={cerrarSesion}>
                  Cerrar sesión
                </button>
              </div>

              <div className="actions">
                <button type="button" className="btn btn-secondary" onClick={() => void cargar()} disabled={cargando}>
                  {cargando ? 'Cargando…' : 'Actualizar'}
                </button>
                <Link className="btn btn-secondary" to="/historial">Ver mi historial</Link>
              </div>

              {cargando && <p className="muted">Cargando tus citas…</p>}
              {mensaje && <p className="notice" role="status">{mensaje}</p>}

              <div className="actions">
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={() => void abrirNuevaCita()}
                  disabled={cargando}
                >
                  Nueva cita
                </button>
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => setMostrarCitas((v) => !v)}
                  disabled={cargando}
                >
                  {mostrarCitas ? 'Ocultar mis citas activas' : `Ver mis citas activas (${citasActivas.length})`}
                </button>
              </div>

              {nuevaCitaAbierta && (
                <section className="card">
                  <h2>Nueva cita</h2>
                  {!tramite && (
                    <form onSubmit={handleReservar} className="form">
                      <label>
                        Estudio
                        <select value={estudioId} onChange={(e) => setEstudioId(e.target.value)}>
                          <option value="">Selecciona un estudio</option>
                          {estudios.map((est) => (
                            <option key={est.id} value={est.id}>
                              {est.nombre} — ${est.precio}
                            </option>
                          ))}
                        </select>
                      </label>
                      {estudioSeleccionado && (
                        <p className="precio">
                          Precio: <strong>${estudioSeleccionado.precio}</strong>
                        </p>
                      )}
                      <button type="submit" className="btn btn-primary" disabled={reservando || !estudioId}>
                        {reservando ? 'Reservando…' : 'Reservación'}
                      </button>
                    </form>
                  )}
                  {tramite && (
                    <div>
                      <p>
                        Folio: <strong>{tramite.folio}</strong>{' '}
                        <EstadoBadge estado={tramite.estado} clinica={tramite.clinica?.nombre} />
                      </p>
                      <p>
                        Estudio: {tramite.estudio.nombre} — <strong>${tramite.precioCotizado}</strong>
                      </p>

                      <h3>Elige clínica</h3>
                      <div className="form">
                        <select value={clinicaEfectivaId} onChange={(e) => setClinicaId(e.target.value)}>
                          <option value="">Selecciona una clínica</option>
                          {clinicas.map((c) => (
                            <option key={c.id} value={c.id}>
                              {c.nombre} ({c.ciudad})
                            </option>
                          ))}
                        </select>
                        {tramite.estado === 'COTIZADO' && (
                          <button type="button" className="btn btn-secondary" onClick={() => void handleClinica()} disabled={!clinicaEfectivaId || reservando}>
                            Seleccionar clínica
                          </button>
                        )}
                      </div>
                      {tramite.clinica && <p>Clínica: <strong>{tramite.clinica.nombre}</strong> ({tramite.clinica.ciudad})</p>}

                      {(tramite.estado === 'COTIZADO' || tramite.estado === 'CLINICA_SELECCIONADA') && clinicaEfectivaId && (
                        <>
                          <h3>Elige fecha y hora de tu cita</h3>
                          <div className="form">
                            <label>
                              Fecha (calendario)
                              <input type="date" value={fechaISO} onChange={(e) => fijarFechaISO(e.target.value)} />
                            </label>
                            <label>
                              Fecha (DD/MM/AAAA)
                              <input
                                type="text"
                                value={fechaLatina}
                                onChange={(e) => fijarFechaLatina(e.target.value)}
                                placeholder="25/11/2026"
                                inputMode="numeric"
                              />
                            </label>
                          </div>
                          <ParrillaHorarios
                            clinicaId={clinicaEfectivaId}
                            fechaISO={fechaISO}
                            estudioId={tramite.estudio.id}
                            exceptoTramiteId={tramite.id}
                            horaSeleccionada={hora}
                            onSeleccionar={setHora}
                          />
                          <div className="actions">
                            <button
                              type="button"
                              className="btn btn-primary"
                              onClick={() => void handleConfirmar()}
                              disabled={!fechaISO || !hora || reservando}
                            >
                              Confirmar reservación
                            </button>
                          </div>
                        </>
                      )}

                      {tramite.estado === 'CONFIRMADO' && tramite.fechaCita && (
                        <p>
                          Cita confirmada: <strong>{formatearFechaHora(tramite.fechaCita)}</strong>
                        </p>
                      )}

                      <div className="actions">
                        <Link className="btn btn-primary" to={`/journey/${encodeURIComponent(tramite.folio)}`}>
                          Ver mi Journey
                        </Link>
                        <button type="button" className="btn btn-ghost" onClick={() => setNuevaCitaAbierta(false)}>
                          Cerrar
                        </button>
                      </div>
                    </div>
                  )}
                </section>
              )}

              {mostrarCitas && (
                <section>
                  {citasActivas.length === 0 ? (
                    <p className="muted">No tienes citas activas o pendientes. Crea una con el botón "Nueva cita".</p>
                  ) : (
                    citasActivas.map((t) => (
                      <section key={t.id} className="card cita-card">
                        <p className="muted">Tu cita</p>
                        <h2>{t.estudio.nombre}</h2>
                        <p>
                          Folio: <strong>{t.folio}</strong>
                        </p>
                        <p>
                          <EstadoBadge estado={t.estado} clinica={t.clinica?.nombre} />
                        </p>
                        <p>
                          Precio: <strong>${t.precioCotizado}</strong>
                        </p>
                        <p>Clínica: {t.clinica ? `${t.clinica.nombre} (${t.clinica.ciudad})` : 'Por elegir'}</p>
                        <p>Fecha: {t.fechaCita ? formatearFechaHora(t.fechaCita) : 'Por confirmar'}</p>
                        <p className="muted">{siguientePaso(t)}</p>
                        <div className="actions">
                          <Link className="btn btn-secondary" to={`/journey/${encodeURIComponent(t.folio)}`}>
                            Ver mi Journey
                          </Link>
                        </div>
                      </section>
                    ))
                  )}
                </section>
              )}

              <div className="actions">
                <Link className="btn btn-primary" to="/whatsapp">
                  Abrir WhatsApp
                </Link>
                {tramitePrincipal && (
                  <Link className="btn btn-secondary" to={`/journey/${encodeURIComponent(tramitePrincipal.folio)}`}>
                    Ver mi Journey
                  </Link>
                )}
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
