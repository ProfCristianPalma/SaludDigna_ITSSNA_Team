import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { confirmar, cotizar, crearTramite, getClinicas, getEstudios, getHorasOcupadas, getPerfilYo, getToken, getTramitesPorTelefono, isApiError, seleccionarClinica } from '../api';
import { esFechaLatinaValida, formatearFecha, formatearFechaHora, parsearFechaLatina } from '../fechas';
import type { Clinica, Estudio, Paciente, Tramite } from '../types';
import EstadoBadge from '../components/EstadoBadge';

const FOLIO_KEY = 'saluddigna-folio';

export default function WebPage() {
  // Sin sesión, el formulario arranca VACÍO: ya no hay ningún usuario
  // de ejemplo cargado. Con sesión de paciente, los datos vienen de la
  // sesión (nombre, teléfono y fecha de nacimiento) y NO se muestran
  // como editables: el formulario de cotizar ya no tiene fecha de
  // nacimiento y, con sesión, tampoco nombre ni teléfono editables.
  // Sin sesión se cotiza solo con nombre y teléfono (la fecha es
  // nullable en la BD y el backend resuelve por teléfono).
  const [sesionPaciente, setSesionPaciente] = useState<Paciente | null>(null);
  const [nombre, setNombre] = useState('');
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
  // ambos campos están sincronizados. La hora es un <input type="time">.
  const [fechaISO, setFechaISO] = useState('');
  const [fechaLatina, setFechaLatina] = useState('');
  const [hora, setHora] = useState('');
  const [horasOcupadas, setHorasOcupadas] = useState<string[]>([]);
  const [cargandoHoras, setCargandoHoras] = useState(false);

  // Todos los folios de ese telefono, desde la BD: un folio por estudio.
  async function cargarFolios(tel: string) {
    if (!tel.trim()) return;
    try {
      setMisFolios(await getTramitesPorTelefono(tel));
    } catch {
      setMisFolios([]);
    }
  }

  useEffect(() => {
    getEstudios().then((list) => {
      setEstudios(list);
      if (list.length > 0) setEstudioId(list[0].id);
    });
    getClinicas().then(setClinicas);
    const guardado = window.sessionStorage.getItem(FOLIO_KEY);
    if (guardado) setMensaje(`Tienes un trámite guardado en esta pestaña: ${guardado}. Puedes retomarlo en WhatsApp o en Journey.`);
    // Si hay sesión de paciente, los datos de cotización son LOS DE LA
    // SESIÓN. Sin sesión (o sesión de staff), el formulario se queda
    // vacío y se cotiza solo con nombre y teléfono.
    async function prellenar() {
      if (!getToken()) return;
      try {
        const perfil = await getPerfilYo();
        if (perfil.paciente) {
          setSesionPaciente(perfil.paciente);
          setNombre(perfil.paciente.nombre);
          setTelefono(perfil.paciente.telefono);
          void cargarFolios(perfil.paciente.telefono);
          return;
        }
      } catch {
        // Sin sesión válida: el formulario se queda vacío.
      }
    }
    void prellenar();
  }, []);

  // Horas ocupadas de la clínica elegida en el día elegido: se
  // consultan al cambiar la fecha o la clínica, para bloquear la hora.
  useEffect(() => {
    async function cargarHoras() {
      if (tramite?.estado !== 'CLINICA_SELECCIONADA' || !tramite.clinica || !fechaISO) {
        setHorasOcupadas([]);
        return;
      }
      setCargandoHoras(true);
      try {
        setHorasOcupadas(await getHorasOcupadas(tramite.clinica.id, fechaISO));
      } catch {
        setHorasOcupadas([]);
      } finally {
        setCargandoHoras(false);
      }
    }
    void cargarHoras();
  }, [tramite?.estado, tramite?.clinica?.id, fechaISO]);

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

  async function handleCotizar(e: React.FormEvent) {
    e.preventDefault();
    if (!estudioId) return;
    // Teléfono y nombre efectivos: de la sesión si la hay, del formulario si no.
    const telefonoEfectivo = sesionPaciente?.telefono ?? telefono;
    const nombreEfectivo = sesionPaciente?.nombre ?? nombre;
    const fechaSesion = sesionPaciente?.fechaNacimiento || undefined;
    setCargando(true);
    setMensaje('');
    // Folios que ya existían antes de este intento, para detectar en
    // la recuperación si el folio sí se creó aunque la respuesta tardó.
    let idsPrevios = new Set(misFolios.map((t) => t.id));
    try {
      const actuales = await getTramitesPorTelefono(telefonoEfectivo);
      idsPrevios = new Set(actuales.map((t) => t.id));
    } catch {
      // Sin red no hay lista previa: la recuperación lo intentará igual.
    }
    try {
      // Cada Cotizar crea un folio NUEVO: un folio por estudio elegido.
      const creado = await crearTramite({
        nombre: nombreEfectivo,
        telefono: telefonoEfectivo,
        ...(fechaSesion ? { fechaNacimiento: fechaSesion } : {}),
        estudioId,
        canal: 'WEB',
      });
      const cotizado = await cotizar(creado.id, estudioId);
      setTramite(cotizado);
      window.sessionStorage.setItem(FOLIO_KEY, cotizado.folio);
      setMensaje(`Cotización lista. Folio ${cotizado.folio}.`);
      await cargarFolios(telefonoEfectivo);
    } catch {
      // Falso negativo: con el backend frío la respuesta de crear o de
      // cotizar puede tardar y el cliente aborta, pero el servidor
      // igual guarda el folio. Antes de decir que no se creó nada, se
      // vuelve a consultar la BD: si apareció un folio nuevo con este
      // estudio, se muestra en el panel como creado.
      try {
        const lista = await getTramitesPorTelefono(telefonoEfectivo);
        let nuevo =
          lista.find((t) => !idsPrevios.has(t.id) && t.estudio.id === estudioId) ??
          lista.find((t) => !idsPrevios.has(t.id));
        if (nuevo && (nuevo.estado === 'INICIADO' || nuevo.estado === 'DATOS_PENDIENTES')) {
          // El folio se creó pero el paso de cotizar no llegó a
          // confirmarse en el front: se reintenta una vez (timeout largo).
          try {
            nuevo = await cotizar(nuevo.id, estudioId);
          } catch {
            // Se queda el folio creado sin cotizar; se muestra igual.
          }
        }
        if (nuevo) {
          setTramite(nuevo);
          window.sessionStorage.setItem(FOLIO_KEY, nuevo.folio);
          setMensaje(
            `La respuesta tardó, pero tu folio sí se creó: ${nuevo.folio}. Ya lo puedes ver en tu Historial.`,
          );
          await cargarFolios(telefonoEfectivo);
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
    if (!tramite || !clinicaId) return;
    setCargando(true);
    try {
      const actualizado = await seleccionarClinica(tramite.id, clinicaId);
      setTramite(actualizado);
      setMensaje(`Clínica seleccionada: ${actualizado.clinica?.nombre ?? ''}.`);
      await cargarFolios(sesionPaciente?.telefono ?? telefono);
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
    if (horasOcupadas.includes(hora)) {
      setMensaje(`La hora ${hora} ya está ocupada en esa clínica ese día. Elige otra hora.`);
      return;
    }
    setCargando(true);
    try {
      const fechaHoraISO = `${fechaISO}T${hora}:00`;
      const confirmado = await confirmar(tramite.id, fechaHoraISO);
      setTramite(confirmado);
      setMensaje(
        `Reservación confirmada para el ${formatearFechaHora(confirmado.fechaCita)}. Folio ${confirmado.folio}.`,
      );
      await cargarFolios(sesionPaciente?.telefono ?? telefono);
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
              Cotizando como <strong>{sesionPaciente.nombre}</strong> · {sesionPaciente.telefono}. Tus datos
              vienen de tu sesión y no se pueden modificar aquí.
            </p>
          ) : (
            <p className="muted" style={{ marginBottom: 12 }}>
              Sin sesión, cotiza solo con tu nombre y tu teléfono. Si{' '}
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
                  Teléfono (WhatsApp)
                  <input value={telefono} onChange={(e) => setTelefono(e.target.value)} required />
                </label>
              </>
            )}
            <label>
              Estudio
              <select value={estudioId} onChange={(e) => setEstudioId(e.target.value)}>
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
            <button type="submit" className="btn btn-primary" disabled={cargando}>
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
                Folio: <strong>{tramite.folio}</strong> <EstadoBadge estado={tramite.estado} />
              </p>
              <p>
                Estudio: {tramite.estudio.nombre} — <strong>${tramite.precioCotizado}</strong>
              </p>
              <p className="muted">Canal de origen: {tramite.canalOrigen}</p>

              <h3>Elige clínica</h3>
              <div className="form">
                <select value={clinicaId} onChange={(e) => setClinicaId(e.target.value)}>
                  <option value="">Selecciona una clínica</option>
                  {clinicas.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.nombre} ({c.ciudad})
                    </option>
                  ))}
                </select>
                <button type="button" className="btn btn-secondary" onClick={handleClinica} disabled={!clinicaId || cargando}>
                  Seleccionar clínica
                </button>
              </div>
              {tramite.clinica && <p>Clínica: <strong>{tramite.clinica.nombre}</strong> ({tramite.clinica.ciudad})</p>}

              {tramite.estado === 'CLINICA_SELECCIONADA' && (
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
                    <label>
                      Hora
                      <input type="time" value={hora} onChange={(e) => setHora(e.target.value)} />
                    </label>
                  </div>
                  {fechaISO && tramite.clinica && (
                    <p className="muted">
                      {cargandoHoras
                        ? 'Consultando horas ocupadas…'
                        : horasOcupadas.length > 0
                          ? `Horas ocupadas: ${horasOcupadas.join(', ')}`
                          : 'Horas ocupadas: ninguna, el día está libre.'}
                    </p>
                  )}
                  {hora && horasOcupadas.includes(hora) && (
                    <p className="notice" role="alert">
                      La hora {hora} ya está ocupada en esa clínica ese día. Elige otra hora.
                    </p>
                  )}
                  <div className="actions">
                    <button
                      type="button"
                      className="btn btn-primary"
                      onClick={() => void handleConfirmar()}
                      disabled={!fechaISO || !hora || horasOcupadas.includes(hora) || cargando}
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
          con tu teléfono. Cada uno sigue su propio flujo: estudio, precio, clínica, cita y estado.
          También los puedes ver todos juntos en tu <Link to="/historial">Historial de folios</Link>.
        </p>
        {misFolios.length === 0 ? (
          <p className="muted">Aún no tienes folios con este teléfono. Cotiza un estudio y aparecerá aquí.</p>
        ) : (
          <ul>
            {misFolios.map((t) => (
              <li key={t.id} className="dato-row">
                <span>
                  <strong>{t.folio}</strong> · {t.estudio.nombre} — ${t.precioCotizado}{' '}
                  <EstadoBadge estado={t.estado} />
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
