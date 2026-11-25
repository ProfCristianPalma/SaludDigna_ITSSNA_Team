import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { buscarPacientePorCurp, confirmar, cotizar, crearTramite, getClinicas, getEstudios, getPerfilYo, getToken, getTramite, getTramitesPorPacienteId, isApiError, parsearTramiteExistente, seleccionarClinica, textoFaltantes } from '../api';
import { esCurp, normalizarCurp, validarCurp } from '../curp';
import { esFechaLatinaValida, formatearFecha, formatearFechaHora, parsearFechaLatina } from '../fechas';
import type { Clinica, Estudio, Paciente, Tramite } from '../types';
import EstadoBadge from '../components/EstadoBadge';
import ParrillaHorarios from '../components/ParrillaHorarios';

const FOLIO_KEY = 'saluddigna-folio';

export default function WebPage() {
  // Sin sesión, el formulario arranca VACÍO: ya no hay ningún usuario
  // de ejemplo cargado. Con sesión de paciente, los datos vienen de la
  // sesión (nombre, CURP y teléfono) y NO se muestran como editables.
  // Sin sesión se cotiza con nombre y CURP obligatoria (el teléfono es
  // opcional); el backend resuelve o crea al paciente solo por CURP.
  const [sesionPaciente, setSesionPaciente] = useState<Paciente | null>(null);
  const [nombre, setNombre] = useState('');
  const [curp, setCurp] = useState('');
  const [telefono, setTelefono] = useState('');
  const [estudios, setEstudios] = useState<Estudio[]>([]);
  const [clinicas, setClinicas] = useState<Clinica[]>([]);
  const [estudioId, setEstudioId] = useState('');
  const [tramite, setTramite] = useState<Tramite | null>(null);
  const [misFolios, setMisFolios] = useState<Tramite[]>([]);
  const [clinicaId, setClinicaId] = useState('');
  const [mensaje, setMensaje] = useState('');
  const [cargando, setCargando] = useState(false);

  // Reservación (trámite en CLINICA_SELECCIONADA): fecha y hora de la
  // cita. La fecha se escribe en el calendario o a mano en DD/MM/AAAA;
  // ambos campos están sincronizados. La hora se elige en la parrilla
  // (slots de 30 min, 07:00–16:30, con las ocupadas deshabilitadas).
  const [fechaISO, setFechaISO] = useState('');
  const [fechaLatina, setFechaLatina] = useState('');
  const [hora, setHora] = useState('');

  // Datos efectivos del paciente: de la sesión si la hay, del
  // formulario si no. Con sesión el paciente puede no tener teléfono:
  // entonces se cotiza por su pacienteId y sus folios se listan por id.
  const curpEfectiva = sesionPaciente?.curp ?? curp;
  const telefonoEfectivo = sesionPaciente?.telefono ?? (telefono.trim() || null);

  // Todos los folios de ese paciente, desde la BD: un folio por estudio.
  // Con sesión se cargan por id; sin sesión, se resuelve el paciente
  // por su CURP y luego sus folios por id.
  async function cargarFolios(pacienteId?: string, curpPaciente?: string) {
    if (pacienteId) {
      try {
        setMisFolios(await getTramitesPorPacienteId(pacienteId));
      } catch {
        setMisFolios([]);
      }
      return;
    }
    if (!curpPaciente?.trim() || !esCurp(curpPaciente)) {
      setMisFolios([]);
      return;
    }
    try {
      const p = await buscarPacientePorCurp(curpPaciente);
      setMisFolios(await getTramitesPorPacienteId(p.id));
    } catch {
      setMisFolios([]);
    }
  }

  useEffect(() => {
    getEstudios().then((list) => {
      setEstudios(list);
      // Sin estudio pre-seleccionado: el estudio lo elige el paciente
      // siempre (como la clínica); nada viene asignado por defecto.
    });
    getClinicas().then(setClinicas);
    const guardado = window.sessionStorage.getItem(FOLIO_KEY);
    if (guardado) setMensaje(`Tienes un trámite guardado en esta pestaña: ${guardado}. Puedes retomarlo en WhatsApp o en Journey.`);
    // Si hay sesión de paciente, los datos de cotización son LOS DE LA
    // SESIÓN. Sin sesión (o sesión de staff), el formulario se queda
    // vacío y se cotiza con nombre y CURP.
    async function prellenar() {
      if (!getToken()) return;
      try {
        const perfil = await getPerfilYo();
        if (perfil.paciente) {
          setSesionPaciente(perfil.paciente);
          setNombre(perfil.paciente.nombre);
          setTelefono(perfil.paciente.telefono ?? '');
          setCurp(perfil.paciente.curp ?? '');
          void cargarFolios(perfil.paciente.id);
          return;
        }
      } catch {
        // Sin sesión válida: el formulario se queda vacío.
      }
    }
    void prellenar();
  }, []);

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

  async function handleCotizar(e: React.FormEvent) {
    e.preventDefault();
    if (!estudioId) return;
    // Sin sesión la CURP es obligatoria y debe ser válida.
    if (!sesionPaciente) {
      const v = validarCurp(curpEfectiva);
      if (!v.valida) {
        setMensaje(`La CURP no es válida: ${v.motivo}`);
        return;
      }
    }
    // Nombre y CURP efectivos: de la sesión si la hay, del formulario si no.
    const nombreEfectivo = sesionPaciente?.nombre ?? nombre;
    const fechaSesion = sesionPaciente?.fechaNacimiento || undefined;
    const listarFoliosActuales = async () => {
      if (sesionPaciente) return getTramitesPorPacienteId(sesionPaciente.id);
      try {
        const p = await buscarPacientePorCurp(curpEfectiva);
        return await getTramitesPorPacienteId(p.id);
      } catch {
        return [];
      }
    };
    const recargarFolios = () => cargarFolios(sesionPaciente?.id, curpEfectiva);
    setCargando(true);
    setMensaje('');
    // Folios que ya existían antes de este intento, para detectar en
    // la recuperación si el folio sí se creó aunque la respuesta tardó.
    let idsPrevios = new Set(misFolios.map((t) => t.id));
    try {
      const actuales = await listarFoliosActuales();
      idsPrevios = new Set(actuales.map((t) => t.id));
    } catch {
      // Sin red no hay lista previa: la recuperación lo intentará igual.
    }
    try {
      // Cada Cotizar crea un folio NUEVO solo si el paciente no tiene
      // ya un trámite ACTIVO de ese estudio: si lo tiene, el backend
      // devuelve 409 con el folio existente para retomarlo (ver catch).
      const creado = await crearTramite({
        nombre: nombreEfectivo,
        telefono: telefonoEfectivo,
        curp: sesionPaciente ? undefined : curpEfectiva,
        pacienteId: sesionPaciente?.id,
        ...(fechaSesion ? { fechaNacimiento: fechaSesion } : {}),
        estudioId,
        canal: 'WEB',
      });
      const cotizado = await cotizar(creado.id, estudioId, 'WEB');
      setTramite(cotizado);
      // Reservación nueva: la clínica, la fecha y la hora se eligen de
      // cero (no arrastrar las de la reservación anterior).
      setClinicaId('');
      fijarFechaISO('');
      setHora('');
      window.sessionStorage.setItem(FOLIO_KEY, cotizado.folio);
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
          setTramite(t);
          // Reservación retomada: la clínica, la fecha y la hora se
          // eligen de cero (no arrastrar las de la anterior).
          setClinicaId('');
          fijarFechaISO('');
          setHora('');
          window.sessionStorage.setItem(FOLIO_KEY, t.folio);
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
        const lista = await listarFoliosActuales();
        let nuevo =
          lista.find((t) => !idsPrevios.has(t.id) && t.estudio.id === estudioId) ??
          lista.find((t) => !idsPrevios.has(t.id));
        if (nuevo && (nuevo.estado === 'INICIADO' || nuevo.estado === 'DATOS_PENDIENTES')) {
          // El folio se creó pero el paso de cotizar no llegó a
          // confirmarse en el front: se reintenta una vez (timeout largo).
          try {
            nuevo = await cotizar(nuevo.id, estudioId, 'WEB');
          } catch {
            // Se queda el folio creado sin cotizar; se muestra igual.
          }
        }
        if (nuevo) {
          setTramite(nuevo);
          // Reservación nueva: la clínica, la fecha y la hora se eligen de cero.
          setClinicaId('');
          fijarFechaISO('');
          setHora('');
          window.sessionStorage.setItem(FOLIO_KEY, nuevo.folio);
          setMensaje(
            `La respuesta tardó, pero tu folio sí se creó: ${nuevo.folio}. Ya lo puedes ver en tu Historial.`,
          );
          await recargarFolios();
          return;
        }
      } catch {
        // Sin red no se puede verificar si el folio se creó.
      }
      setMensaje(
        'No se pudo cotizar: el backend no respondió. Revisa tu Historial: si el folio se creó, aparecerá ahí.',
      );
    } finally {
      setCargando(false);
    }
  }

  async function handleClinica() {
    if (!tramite || !clinicaEfectivaId) return;
    setCargando(true);
    try {
      const actualizado = await seleccionarClinica(tramite.id, clinicaEfectivaId, 'WEB');
      setTramite(actualizado);
      setMensaje(`Clínica seleccionada: ${actualizado.clinica?.nombre ?? ''}.`);
      await cargarFolios(sesionPaciente?.id, curpEfectiva);
    } catch {
      setMensaje('No se pudo seleccionar la clínica: el backend no respondió y no se guardó nada. Revisa que el backend esté arriba.');
    } finally {
      setCargando(false);
    }
  }

  async function handleConfirmar() {
    if (!tramite || !fechaISO || !hora) return;
    if (!esFechaLatinaValida(fechaLatina) && fechaLatina) {
      setMensaje('La fecha no es válida. Escríbela como DD/MM/AAAA, por ejemplo 25/11/2026.');
      return;
    }
    setCargando(true);
    try {
      // Si la clínica se eligió en el desplegable pero aún no se pulsó
      // "Seleccionar clínica", se guarda ahora: confirmar exige
      // CLINICA_SELECCIONADA y rechazaría la reservación.
      let tramiteActual = tramite;
      if (tramite.estado === 'COTIZADO' && clinicaEfectivaId) {
        tramiteActual = await seleccionarClinica(tramite.id, clinicaEfectivaId, 'WEB');
        setTramite(tramiteActual);
      }
      const fechaHoraISO = `${fechaISO}T${hora}:00`;
      const confirmado = await confirmar(tramiteActual.id, fechaHoraISO, 'WEB');
      setTramite(confirmado);
      setMensaje(
        `Reservación confirmada para el ${formatearFechaHora(confirmado.fechaCita)}. Folio ${confirmado.folio}.`,
      );
      await cargarFolios(sesionPaciente?.id, curpEfectiva);
    } catch (err) {
      // 409 (hueco ocupado) o 400 (fecha/hora pasada): el mensaje del
      // backend se muestra tal cual, sin inventar otro.
      setMensaje(
        isApiError(err)
          ? err.message
          : 'No se pudo confirmar la reservación: el backend no respondió y no se guardó nada.',
      );
    } finally {
      setCargando(false);
    }
  }

  function handleInterrumpir() {
    if (!tramite) return;
    window.sessionStorage.setItem(FOLIO_KEY, tramite.folio);
    setMensaje(
      `Simulación de interrupción: cerraste sin confirmar. Tu folio ${tramite.folio} quedó guardado y puedes retomarlo por WhatsApp, con el asesor o en la App — sin repetir tus datos.`,
    );
  }

  return (
    <div>
      <h1>Reserva tu estudio en línea</h1>
      <p className="muted">Un paciente, una sola conversación: empieza aquí y continúa en cualquier canal.</p>

      <div className="grid-2">
        <section className="card">
          <h2>1 · Tus datos y tu estudio</h2>
          {sesionPaciente ? (
            <p className="muted" style={{ marginBottom: 12 }}>
              Cotizando como <strong>{sesionPaciente.nombre}</strong> · {sesionPaciente.curp ?? sesionPaciente.telefono ?? 'Sin teléfono'}. Tus datos
              vienen de tu sesión y no se pueden modificar aquí.
            </p>
          ) : (
            <p className="muted" style={{ marginBottom: 12 }}>
              Sin sesión, cotiza con tu nombre y tu CURP (el teléfono es opcional). Si{' '}
              <Link to="/login">inicias sesión</Link>, tus datos se toman de tu cuenta.
            </p>
          )}
          <form onSubmit={handleCotizar} className="form">
            {!sesionPaciente && (
              <>
                <label>
                  Nombre completo
                  <input value={nombre} onChange={(e) => setNombre(e.target.value)} required />
                </label>
                <label>
                  CURP (obligatoria, 18 caracteres)
                  <input
                    value={curp}
                    onChange={(e) => setCurp(normalizarCurp(e.target.value))}
                    placeholder="Ej. GALA900514MDFRPN00"
                    maxLength={18}
                    style={{ textTransform: 'uppercase' }}
                    required
                  />
                </label>
                <label>
                  Teléfono (WhatsApp) — opcional
                  <input value={telefono} onChange={(e) => setTelefono(e.target.value)} placeholder="2220000001 (opcional)" />
                </label>
              </>
            )}
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
            <button type="submit" className="btn btn-primary" disabled={cargando || !estudioId}>
              {cargando ? 'Reservando…' : 'Reservación'}
            </button>
          </form>
        </section>

        <section className="card">
          <h2>DATOS DE LA RESERVACIÓN</h2>
          {!tramite && <p className="muted">Aún no hay reservación. Completa el formulario y presiona Reservación.</p>}
          {tramite && (
            <div>
              <p>
                Folio: <strong>{tramite.folio}</strong> <EstadoBadge estado={tramite.estado} clinica={tramite.clinica?.nombre} />
              </p>
              <p>
                Estudio: {tramite.estudio.nombre} — <strong>${tramite.precioCotizado}</strong>
              </p>
              <p className="muted">Canal de origen: {tramite.canalOrigen}</p>

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
                  <button type="button" className="btn btn-secondary" onClick={handleClinica} disabled={!clinicaEfectivaId || cargando}>
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
                      disabled={!fechaISO || !hora || cargando}
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
                <button type="button" className="btn btn-ghost" onClick={handleInterrumpir}>
                  Cerrar sin confirmar (simular interrupción)
                </button>
                <Link className="btn btn-primary" to={`/journey/${encodeURIComponent(tramite.folio)}`}>
                  Ver mi Journey
                </Link>
              </div>
            </div>
          )}
        </section>
      </div>

      {mensaje && <p className="notice" role="status">{mensaje}</p>}

      <section className="card" style={{ marginTop: 16 }}>
        <h2>Mis folios</h2>
        <p className="muted">
          Un folio por estudio que hayas elegido, todos guardados en la base de datos
          con tu cuenta. Cada uno sigue su propio flujo: estudio, precio, clínica, cita y estado.
          También los puedes ver todos juntos en tu <Link to="/historial">Historial de folios</Link>.
        </p>
        {misFolios.length === 0 ? (
          <p className="muted">Aún no tienes folios con esta cuenta. Cotiza un estudio y aparecerá aquí.</p>
        ) : (
          <ul>
            {misFolios.map((t) => (
              <li key={t.id} className="dato-row">
                <span>
                  <strong>{t.folio}</strong> · {t.estudio.nombre} — ${t.precioCotizado}{' '}
                  <EstadoBadge estado={t.estado} clinica={t.clinica?.nombre} />
                  {t.clinica ? ` · ${t.clinica.nombre}` : ''}
                  {t.fechaCita ? ` · Cita: ${formatearFechaHora(t.fechaCita)}` : ''}
                </span>
                <Link className="btn btn-small" to={`/journey/${encodeURIComponent(t.folio)}`}>
                  Ver Journey
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
