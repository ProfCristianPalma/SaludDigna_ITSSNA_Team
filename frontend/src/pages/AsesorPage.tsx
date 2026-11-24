import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  actualizarPaciente,
  buscarPacientesPorNombre,
  completarDato,
  confirmar,
  cotizar,
  crearTramite,
  esFolio,
  esTelefono,
  getClinicas,
  getEstudios,
  getHorasOcupadas,
  getPacientePorTelefono,
  getTimeline,
  getTramitePorFolio,
  getTramitesPorTelefono,
  guardarNotaCallCenter,
  isApiError,
  normalizarFolio,
  seleccionarClinica,
  verificarDisponibilidad,
} from '../api';
import type { Clinica, Estudio, Evento, Paciente, Tramite } from '../types';
import { esFechaLatinaValida, formatearFecha, formatearFechaHora, parsearFechaLatina } from '../fechas';
import EstadoBadge from '../components/EstadoBadge';
import Timeline from '../components/Timeline';
import VistaPersonal from '../components/VistaPersonal';

// Vista de personal: el contenido solo se monta con sesión de asesor
// o admin (VistaPersonal). Sin sesión no se busca ni se muestra nada.
export default function AsesorPage() {
  return (
    <VistaPersonal titulo="Call Center — consola del asesor">
      <ContenidoAsesor />
    </VistaPersonal>
  );
}

function ContenidoAsesor() {
  // La búsqueda arranca vacía: ningún teléfono de ejemplo cargado.
  const [busqueda, setBusqueda] = useState('');
  const [tramites, setTramites] = useState<Tramite[]>([]);
  const [tramite, setTramite] = useState<Tramite | null>(null);
  const [timeline, setTimeline] = useState<Evento[]>([]);
  const [nota, setNota] = useState('');
  const [mensaje, setMensaje] = useState('');
  const [buscando, setBuscando] = useState(false);
  const [guardando, setGuardando] = useState(false);

  // Editar datos del paciente del trámite en pantalla (PUT
  // /api/pacientes/{id}). Sin campo de contraseña: aquí no se toca.
  const [editNombre, setEditNombre] = useState('');
  const [editTelefono, setEditTelefono] = useState('');
  const [editFechaLatina, setEditFechaLatina] = useState('');
  const [editEmail, setEditEmail] = useState('');
  const [editandoPaciente, setEditandoPaciente] = useState(false);

  // Nueva reservación por Call Center: paciente por teléfono o el del
  // trámite buscado; estudio → clínica → fecha y hora.
  const [estudios, setEstudios] = useState<Estudio[]>([]);
  const [clinicas, setClinicas] = useState<Clinica[]>([]);
  const [resTelefono, setResTelefono] = useState('');
  const [resPaciente, setResPaciente] = useState<Paciente | null>(null);
  const [resMensaje, setResMensaje] = useState('');
  const [resEstudioId, setResEstudioId] = useState('');
  const [resClinicaId, setResClinicaId] = useState('');
  const [resTramite, setResTramite] = useState<Tramite | null>(null);
  const [resFechaISO, setResFechaISO] = useState('');
  const [resFechaLatina, setResFechaLatina] = useState('');
  const [resHora, setResHora] = useState('');
  const [resHorasOcupadas, setResHorasOcupadas] = useState<string[]>([]);
  const [resCargando, setResCargando] = useState(false);

  useEffect(() => {
    getEstudios().then((list) => {
      setEstudios(list);
      if (list.length > 0) setResEstudioId(list[0].id);
    });
    getClinicas().then(setClinicas);
  }, []);

  // Al cambiar de trámite, el formulario de edición se prellena con
  // sus datos (el email no viene en el trámite: se deja en blanco y
  // solo se envía si el asesor lo escribe).
  useEffect(() => {
    if (!tramite?.paciente) return;
    setEditNombre(tramite.paciente.nombre);
    setEditTelefono(tramite.paciente.telefono);
    setEditFechaLatina(
      tramite.paciente.fechaNacimiento ? formatearFecha(tramite.paciente.fechaNacimiento) : '',
    );
    setEditEmail('');
  }, [tramite]);

  // Horas ocupadas de la clínica elegida en el día elegido (reservación nueva).
  useEffect(() => {
    async function cargarHoras() {
      if (resTramite?.estado !== 'CLINICA_SELECCIONADA' || !resClinicaId || !resFechaISO) {
        setResHorasOcupadas([]);
        return;
      }
      try {
        setResHorasOcupadas(await getHorasOcupadas(resClinicaId, resFechaISO));
      } catch {
        setResHorasOcupadas([]);
      }
    }
    void cargarHoras();
  }, [resTramite?.estado, resClinicaId, resFechaISO]);

  function resFijarFechaISO(iso: string) {
    setResFechaISO(iso);
    setResFechaLatina(iso ? formatearFecha(iso) : '');
  }

  function resFijarFechaLatina(texto: string) {
    setResFechaLatina(texto);
    if (esFechaLatinaValida(texto)) setResFechaISO(parsearFechaLatina(texto) ?? '');
  }

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
    // La consulta se normaliza a folio canónico ANTES de decidir la
    // rama: "sd 2026 0012", "2026-0012" o "0012" también son el folio
    // SD-2026-0012. Sin esto, solo el folio exacto entraba por la rama
    // de folio y el resto caía en un mensaje genérico.
    const folioBuscado = normalizarFolio(q) ?? (esFolio(q) ? q.trim().toUpperCase() : null);
    try {
      let lista: Tramite[] = [];
      if (folioBuscado) {
        lista = [await getTramitePorFolio(folioBuscado)];
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
        setMensaje(
          folioBuscado
            ? `No hay ningún trámite con el folio ${folioBuscado}.`
            : 'No se encontró ningún trámite con esa búsqueda.',
        );
        return;
      }
      setTramites(lista);
      await cargarTramite(lista[0]);
      if (lista.length > 1) {
        setMensaje(`Encontré ${lista.length} folios. Elige uno de la lista para ver su contexto.`);
      }
    } catch (err) {
      setMensaje(
        folioBuscado && isApiError(err) && err.status === 404
          ? `No hay ningún trámite con el folio ${folioBuscado}.`
          : folioBuscado && isApiError(err)
            ? `No se pudo buscar el folio ${folioBuscado} (HTTP ${err.status}: ${err.message}).`
            : isApiError(err)
              ? 'No se encontró un trámite con ese teléfono, folio o nombre. Verifica la búsqueda.'
              : 'El backend no respondió, no se pudo buscar. Revisa que el backend esté arriba.',
      );
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

  async function handleEditarPaciente(e: React.FormEvent) {
    e.preventDefault();
    if (!tramite?.paciente) return;
    if (editFechaLatina.trim() && !esFechaLatinaValida(editFechaLatina)) {
      setMensaje('La fecha de nacimiento no es válida. Escríbela como DD/MM/AAAA, por ejemplo 14/05/1990.');
      return;
    }
    setEditandoPaciente(true);
    try {
      // Solo se mandan los campos con valor: el backend conserva los
      // demás y registra el cambio en el log del trámite (tramiteId).
      // Sin contraseña: PUT /api/pacientes/{id} no la toca.
      const actualizado = await actualizarPaciente(
        tramite.paciente.id,
        {
          nombre: editNombre.trim() || undefined,
          telefono: editTelefono.trim() || undefined,
          email: editEmail.trim() || undefined,
          fechaNacimiento: editFechaLatina.trim()
            ? (parsearFechaLatina(editFechaLatina) ?? undefined)
            : undefined,
        },
        tramite.id,
      );
      setMensaje(
        `Datos del paciente actualizados: ${actualizado.nombre} · ${actualizado.telefono}.`,
      );
      setTramite((prev) => (prev ? { ...prev, paciente: actualizado } : prev));
      setTramites((prev) =>
        prev.map((t) => (t.id === tramite.id ? { ...t, paciente: actualizado } : t)),
      );
    } catch (err) {
      setMensaje(
        isApiError(err)
          ? `No se pudo actualizar al paciente (HTTP ${err.status}: ${err.message}).`
          : 'No se pudo actualizar al paciente. Revisa que el backend esté arriba.',
      );
    } finally {
      setEditandoPaciente(false);
    }
  }

  async function handleBuscarPacienteReserva(e: React.FormEvent) {
    e.preventDefault();
    const tel = resTelefono.trim();
    if (!tel) return;
    setResCargando(true);
    setResMensaje('');
    try {
      const p = await getPacientePorTelefono(tel);
      setResPaciente(p);
      setResMensaje(`Paciente encontrado: ${p.nombre} · ${p.telefono}.`);
    } catch (err) {
      setResPaciente(null);
      setResMensaje(
        isApiError(err) && err.status === 404
          ? `No hay ningún paciente con el teléfono ${tel}.`
          : isApiError(err)
            ? `No se pudo buscar al paciente (HTTP ${err.status}: ${err.message}).`
            : 'El backend no respondió, no se pudo buscar al paciente.',
      );
    } finally {
      setResCargando(false);
    }
  }

  function usarPacienteDelTramite() {
    if (!tramite?.paciente) return;
    setResPaciente(tramite.paciente);
    setResTelefono(tramite.paciente.telefono);
    setResMensaje(`Usando al paciente del trámite ${tramite.folio}: ${tramite.paciente.nombre}.`);
  }

  async function handleCrearReservacion() {
    if (!resPaciente || !resEstudioId) return;
    setResCargando(true);
    setResMensaje('');
    try {
      const creado = await crearTramite({
        nombre: resPaciente.nombre,
        telefono: resPaciente.telefono,
        estudioId: resEstudioId,
        canal: 'CALL_CENTER',
      });
      const cotizado = await cotizar(creado.id, resEstudioId);
      setResTramite(cotizado);
      setResMensaje(`Reservación creada. Folio ${cotizado.folio}. Ahora elige la clínica.`);
    } catch (err) {
      setResMensaje(
        isApiError(err)
          ? `No se pudo crear la reservación (HTTP ${err.status}: ${err.message}).`
          : 'No se pudo crear la reservación: el backend no respondió.',
      );
    } finally {
      setResCargando(false);
    }
  }

  async function handleSeleccionarClinicaReserva() {
    if (!resTramite || !resClinicaId) return;
    setResCargando(true);
    try {
      const actualizado = await seleccionarClinica(resTramite.id, resClinicaId);
      setResTramite(actualizado);
      setResMensaje(`Clínica seleccionada: ${actualizado.clinica?.nombre ?? ''}. Ahora elige fecha y hora.`);
    } catch (err) {
      setResMensaje(
        isApiError(err)
          ? `No se pudo seleccionar la clínica (HTTP ${err.status}: ${err.message}).`
          : 'No se pudo seleccionar la clínica: el backend no respondió.',
      );
    } finally {
      setResCargando(false);
    }
  }

  async function handleConfirmarReserva() {
    if (!resTramite || !resFechaISO || !resHora) return;
    const fechaHoraISO = `${resFechaISO}T${resHora}:00`;
    setResCargando(true);
    try {
      // Doble guarda: la lista del día bloquea la hora en pantalla y
      // esta verificación consulta el hueco exacto antes de confirmar.
      // El 409 del backend sigue siendo la última palabra y su mensaje
      // se muestra tal cual.
      if (resHorasOcupadas.includes(resHora)) {
        setResMensaje(`La hora ${resHora} ya está ocupada en esa clínica ese día. Elige otra hora.`);
        return;
      }
      const ocupado = await verificarDisponibilidad(resClinicaId, fechaHoraISO, resTramite.id);
      if (ocupado) {
        setResMensaje(`La hora ${resHora} ya está ocupada en esa clínica ese día. Elige otra hora.`);
        setResHorasOcupadas(await getHorasOcupadas(resClinicaId, resFechaISO));
        return;
      }
      const confirmado = await confirmar(resTramite.id, fechaHoraISO);
      setResTramite(confirmado);
      setResMensaje(
        `Reservación confirmada para el ${formatearFechaHora(confirmado.fechaCita)}. Folio ${confirmado.folio}.`,
      );
    } catch (err) {
      setResMensaje(
        isApiError(err)
          ? err.message
          : 'No se pudo confirmar la reservación: el backend no respondió y no se guardó nada.',
      );
    } finally {
      setResCargando(false);
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
              <dt>Fecha de cita</dt><dd>{tramite.fechaCita ? formatearFechaHora(tramite.fechaCita) : 'Sin confirmar'}</dd>
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

            <h3>Editar datos del paciente</h3>
            <form onSubmit={handleEditarPaciente} className="form">
              <label>
                Nombre completo
                <input value={editNombre} onChange={(e) => setEditNombre(e.target.value)} required />
              </label>
              <label>
                Teléfono (10 dígitos, sin +52)
                <input value={editTelefono} onChange={(e) => setEditTelefono(e.target.value)} required />
              </label>
              <label>
                Fecha de nacimiento (DD/MM/AAAA)
                <input
                  type="text"
                  value={editFechaLatina}
                  onChange={(e) => setEditFechaLatina(e.target.value)}
                  placeholder="DD/MM/AAAA"
                  inputMode="numeric"
                />
              </label>
              <label>
                Correo electrónico
                <input
                  type="email"
                  value={editEmail}
                  onChange={(e) => setEditEmail(e.target.value)}
                  placeholder="tu@correo.com"
                />
              </label>
              <button type="submit" className="btn btn-secondary" disabled={editandoPaciente}>
                {editandoPaciente ? 'Guardando…' : 'Guardar datos del paciente'}
              </button>
            </form>

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

      <section className="card" style={{ marginTop: 16 }}>
        <h2>Nueva reservación</h2>
        <p className="muted">
          Crea una reservación por teléfono, como Call Center: elige al paciente, luego el estudio,
          la clínica y la fecha y hora de la cita.
        </p>

        <h3>Paciente</h3>
        <form className="form-inline" onSubmit={handleBuscarPacienteReserva}>
          <input
            value={resTelefono}
            onChange={(e) => setResTelefono(e.target.value)}
            placeholder="Teléfono del paciente (10 dígitos)"
            aria-label="Teléfono del paciente"
          />
          <button type="submit" className="btn btn-secondary" disabled={resCargando}>
            Buscar paciente
          </button>
          {tramite?.paciente && (
            <button type="button" className="btn btn-secondary" onClick={usarPacienteDelTramite}>
              Usar paciente del trámite buscado
            </button>
          )}
        </form>
        {resPaciente && (
          <p>
            Paciente: <strong>{resPaciente.nombre}</strong> · {resPaciente.telefono}
          </p>
        )}
        {resMensaje && <p className="notice" role="status">{resMensaje}</p>}

        {resPaciente && (
          <>
            <h3>1 · Estudio</h3>
            <div className="form">
              <label>
                Estudio
                <select value={resEstudioId} onChange={(e) => setResEstudioId(e.target.value)}>
                  {estudios.map((est) => (
                    <option key={est.id} value={est.id}>
                      {est.nombre} — ${est.precio}
                    </option>
                  ))}
                </select>
              </label>
              <button
                type="button"
                className="btn btn-primary"
                onClick={() => void handleCrearReservacion()}
                disabled={resCargando || !!resTramite}
              >
                Crear reservación
              </button>
            </div>

            {resTramite && (
              <>
                <p>
                  Folio: <strong>{resTramite.folio}</strong> <EstadoBadge estado={resTramite.estado} /> ·{' '}
                  {resTramite.estudio.nombre} — <strong>${resTramite.precioCotizado}</strong>
                </p>
                <h3>2 · Clínica</h3>
                <div className="form">
                  <select value={resClinicaId} onChange={(e) => setResClinicaId(e.target.value)}>
                    <option value="">Selecciona una clínica</option>
                    {clinicas.map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.nombre} ({c.ciudad})
                      </option>
                    ))}
                  </select>
                  <button
                    type="button"
                    className="btn btn-secondary"
                    onClick={() => void handleSeleccionarClinicaReserva()}
                    disabled={!resClinicaId || resCargando || resTramite.estado !== 'COTIZADO'}
                  >
                    Seleccionar clínica
                  </button>
                </div>
                {resTramite.clinica && (
                  <p>
                    Clínica: <strong>{resTramite.clinica.nombre}</strong> ({resTramite.clinica.ciudad})
                  </p>
                )}

                {resTramite.estado === 'CLINICA_SELECCIONADA' && (
                  <>
                    <h3>3 · Fecha y hora de la cita</h3>
                    <div className="form">
                      <label>
                        Fecha (calendario)
                        <input type="date" value={resFechaISO} onChange={(e) => resFijarFechaISO(e.target.value)} />
                      </label>
                      <label>
                        Fecha (DD/MM/AAAA)
                        <input
                          type="text"
                          value={resFechaLatina}
                          onChange={(e) => resFijarFechaLatina(e.target.value)}
                          placeholder="25/11/2026"
                          inputMode="numeric"
                        />
                      </label>
                      <label>
                        Hora
                        <input type="time" value={resHora} onChange={(e) => setResHora(e.target.value)} />
                      </label>
                    </div>
                    {resFechaISO && resClinicaId && (
                      <p className="muted">
                        {resHorasOcupadas.length > 0
                          ? `Horas ocupadas: ${resHorasOcupadas.join(', ')}`
                          : 'Horas ocupadas: ninguna, el día está libre.'}
                      </p>
                    )}
                    {resHora && resHorasOcupadas.includes(resHora) && (
                      <p className="notice" role="alert">
                        La hora {resHora} ya está ocupada en esa clínica ese día. Elige otra hora.
                      </p>
                    )}
                    <div className="actions">
                      <button
                        type="button"
                        className="btn btn-primary"
                        onClick={() => void handleConfirmarReserva()}
                        disabled={
                          !resFechaISO || !resHora || resHorasOcupadas.includes(resHora) || resCargando
                        }
                      >
                        Confirmar reservación
                      </button>
                      <Link className="btn btn-secondary" to={`/journey/${encodeURIComponent(resTramite.folio)}`}>
                        Ver Journey
                      </Link>
                    </div>
                  </>
                )}

                {resTramite.estado === 'CONFIRMADO' && resTramite.fechaCita && (
                  <p>
                    Cita confirmada: <strong>{formatearFechaHora(resTramite.fechaCita)}</strong>{' '}
                    <Link className="btn btn-secondary" to={`/journey/${encodeURIComponent(resTramite.folio)}`}>
                      Ver Journey
                    </Link>
                  </p>
                )}
              </>
            )}
          </>
        )}
      </section>
    </div>
  );
}
