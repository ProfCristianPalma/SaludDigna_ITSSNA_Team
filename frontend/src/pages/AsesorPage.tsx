import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  actualizarPaciente,
  buscarPacientePorCurp,
  buscarPacientesPorNombre,
  cancelarTramite,
  completarDato,
  confirmar,
  cotizar,
  crearTramite,
  editarTramite,
  eliminarTramite,
  esFolio,
  esTelefono,
  getClinicas,
  getEstudios,
  getPacientePorTelefono,
  getTimeline,
  getTramitePorFolio,
  getTramitesPorPacienteId,
  getTramitesPorTelefono,
  guardarNotaCallCenter,
  isApiError,
  normalizarFolio,
  parsearTramiteExistente,
  registrarPacienteAsesor,
  seleccionarClinica,
  textoFaltantes,
} from '../api';
import { esCurp, fechaDesdeCurp, normalizarCurp, validarCurp } from '../curp';
import type { Clinica, Estudio, Evento, Paciente, Tramite } from '../types';
import { esFechaLatinaValida, formatearFecha, formatearFechaHora, parsearFechaLatina } from '../fechas';
import EstadoBadge from '../components/EstadoBadge';
import ParrillaHorarios from '../components/ParrillaHorarios';
import PasswordInput from '../components/PasswordInput';
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

/** Qué le falta a un folio para completarse (mismo criterio que el backend: TramiteService.faltantes). */
function faltantesDe(t: Tramite): string[] {
  const f: string[] = [];
  if (!t.estudioId || !t.precioCotizado) f.push('cotizado');
  if (!t.clinica) f.push('clinica');
  if (!t.fechaCita) {
    f.push('fecha');
    f.push('hora');
  }
  return f;
}

/** Estados interrumpidos: se pueden continuar (CONTINUAR PROCESO). CONFIRMADO y terminales, no. */
const ESTADOS_CONTINUABLES = ['INICIADO', 'DATOS_PENDIENTES', 'COTIZADO', 'CLINICA_SELECCIONADA'];
const ESTADOS_TERMINALES = ['ATENDIDO', 'NO_PRESENTADO', 'NO_REALIZADO', 'CANCELADO'];

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
  // Historial expandible ("Ver historial"): desplegado por defecto
  // solo cuando el folio está cerrado/eliminado (ahí la causa debe
  // verse sin un clic extra); en folios activos se despliega con el botón.
  const [mostrarHistorial, setMostrarHistorial] = useState(false);

  // Panel de acción del folio en pantalla: solo se muestra el panel de
  // la acción elegida (Ver / Modificar / Eliminar-Cancelar). CONTINUAR
  // PROCESO no es un panel: carga el folio en Nueva reservación.
  const [panelAccion, setPanelAccion] = useState<'ver' | 'modificar' | 'cierre'>('ver');

  // Editar datos del paciente del trámite en pantalla (PUT
  // /api/pacientes/{id}). Sin campo de contraseña: aquí no se toca.
  const [editNombre, setEditNombre] = useState('');
  const [editCurp, setEditCurp] = useState('');
  const [editTelefono, setEditTelefono] = useState('');
  const [editFechaLatina, setEditFechaLatina] = useState('');
  const [editEmail, setEditEmail] = useState('');
  const [editandoPaciente, setEditandoPaciente] = useState(false);

  // Editar la reservación del folio en pantalla (PUT /api/tramites/{id}):
  // estudio, clínica, fecha y hora + nota obligatoria.
  const [editEstudioId, setEditEstudioId] = useState('');
  const [editClinicaId, setEditClinicaId] = useState('');
  const [editCitaFechaISO, setEditCitaFechaISO] = useState('');
  const [editCitaFechaLatina, setEditCitaFechaLatina] = useState('');
  const [editHora, setEditHora] = useState('');
  const [editNota, setEditNota] = useState('');
  const [editandoTramite, setEditandoTramite] = useState(false);

  // Cancelar o eliminar el folio en pantalla: primero se elige la
  // acción (Cancelar o Eliminar) y se escribe la causa; ambos campos
  // son obligatorios. Eliminar es BORRADO SUAVE: el folio sale de las
  // listas pero su Journey e historial quedan intactos.
  const [accionCierre, setAccionCierre] = useState<'' | 'cancelar' | 'eliminar'>('');
  const [motivoCierre, setMotivoCierre] = useState('');
  const [cerrando, setCerrando] = useState(false);

  // Nueva reservación por Call Center: paciente por CURP, teléfono,
  // folio o nombre; estudio → clínica → fecha y hora (parrilla).
  const [estudios, setEstudios] = useState<Estudio[]>([]);
  const [clinicas, setClinicas] = useState<Clinica[]>([]);
  const [resIdentificador, setResIdentificador] = useState('');
  const [resPaciente, setResPaciente] = useState<Paciente | null>(null);
  const [resFolios, setResFolios] = useState<Tramite[]>([]);
  const [resMensaje, setResMensaje] = useState('');
  const [resEstudioId, setResEstudioId] = useState('');
  const [resClinicaId, setResClinicaId] = useState('');
  const [resTramite, setResTramite] = useState<Tramite | null>(null);
  const [resFechaISO, setResFechaISO] = useState('');
  const [resFechaLatina, setResFechaLatina] = useState('');
  const [resHora, setResHora] = useState('');
  const [resCargando, setResCargando] = useState(false);

  // Registrar un paciente nuevo desde el asesor (espejo de
  // RegistroPage, pero correo opcional y SIN auto-login: la sesión del
  // asesor no se toca). Teléfono y correo son los ÚNICOS opcionales.
  const [regNombre, setRegNombre] = useState('');
  const [regCurp, setRegCurp] = useState('');
  const [regErrorCurp, setRegErrorCurp] = useState('');
  const [regTelefono, setRegTelefono] = useState('');
  const [regEmail, setRegEmail] = useState('');
  const [regFechaLatina, setRegFechaLatina] = useState('');
  const [regErrorFecha, setRegErrorFecha] = useState('');
  const [regPassword, setRegPassword] = useState('');
  const [regMensaje, setRegMensaje] = useState('');
  const [regGuardando, setRegGuardando] = useState(false);
  const regFechaAutocompletada = useRef('');

  useEffect(() => {
    getEstudios().then((list) => {
      setEstudios(list);
      // Sin estudio pre-seleccionado: el estudio lo elige el asesor
      // siempre (como la clínica); nada viene asignado por defecto.
    });
    getClinicas().then(setClinicas);
  }, []);

  // Al cambiar de trámite, el formulario de edición se prellena con
  // sus datos (el email no viene en el trámite: se deja en blanco y
  // solo se envía si el asesor lo escribe).
  useEffect(() => {
    if (!tramite?.paciente) return;
    setEditNombre(tramite.paciente.nombre);
    setEditCurp(tramite.paciente.curp ?? '');
    setEditTelefono(tramite.paciente.telefono ?? '');
    setEditFechaLatina(
      tramite.paciente.fechaNacimiento ? formatearFecha(tramite.paciente.fechaNacimiento) : '',
    );
    setEditEmail('');
    // Edición de la reservación: estudio, clínica, fecha y hora actuales.
    setEditEstudioId(tramite.estudioId ?? '');
    setEditClinicaId(tramite.clinica?.id ?? '');
    setEditCitaFechaISO(tramite.fechaCita ? tramite.fechaCita.slice(0, 10) : '');
    setEditCitaFechaLatina(tramite.fechaCita ? formatearFecha(tramite.fechaCita) : '');
    setEditHora(tramite.fechaCita ? tramite.fechaCita.slice(11, 16) : '');
    setEditNota('');
    setAccionCierre('');
    setMotivoCierre('');
    // En folios cerrados/eliminados el historial se muestra abierto
    // (la causa debe verse sin un clic extra); en activos, plegado.
    setMostrarHistorial(tramite.eliminado || tramite.estado === 'CANCELADO');
  }, [tramite]);

  function resFijarFechaISO(iso: string) {
    setResFechaISO(iso);
    setResFechaLatina(iso ? formatearFecha(iso) : '');
  }

  function resFijarFechaLatina(texto: string) {
    setResFechaLatina(texto);
    if (esFechaLatinaValida(texto)) setResFechaISO(parsearFechaLatina(texto) ?? '');
  }

  function editCitaFijarFechaISO(iso: string) {
    setEditCitaFechaISO(iso);
    setEditCitaFechaLatina(iso ? formatearFecha(iso) : '');
  }

  function editCitaFijarFechaLatina(texto: string) {
    setEditCitaFechaLatina(texto);
    if (esFechaLatinaValida(texto)) setEditCitaFechaISO(parsearFechaLatina(texto) ?? '');
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
      } else if (esCurp(q)) {
        // CURP: se resuelve el paciente y luego todos sus folios por id.
        const p = await buscarPacientePorCurp(q);
        lista = await getTramitesPorPacienteId(p.id);
      } else if (esTelefono(q)) {
        lista = await getTramitesPorTelefono(q);
      } else {
        // Nombre: pacientes que coinciden, luego todos sus tramites.
        const pacientes = await buscarPacientesPorNombre(q);
        if (pacientes.length === 0) throw new Error('sin pacientes');
        for (const p of pacientes) {
          lista.push(...(await getTramitesPorPacienteId(p.id)));
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
      // Se abre el primer folio NO eliminado (los eliminados quedan
      // en el historial del paciente, no como folio en pantalla).
      await cargarTramite(lista.find((t) => !t.eliminado) ?? lista[0]);
      setPanelAccion('ver');
      const activos = lista.filter((t) => !t.eliminado).length;
      if (activos > 1) {
        setMensaje(`Encontré ${activos} folios. Elige uno de la lista para ver su contexto.`);
      }
    } catch (err) {
      setMensaje(
        folioBuscado && isApiError(err) && err.status === 404
          ? `No hay ningún trámite con el folio ${folioBuscado}.`
          : folioBuscado && isApiError(err)
            ? `No se pudo buscar el folio ${folioBuscado} (HTTP ${err.status}: ${err.message}).`
            : isApiError(err)
              ? 'No se encontró un trámite con esa CURP, teléfono, folio o nombre. Verifica la búsqueda.'
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
      const actualizado = await completarDato(tramite.id, dato, 'completado por asesor', 'CALL_CENTER');
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
    const curpNormalizada = normalizarCurp(editCurp);
    if (curpNormalizada) {
      const v = validarCurp(curpNormalizada);
      if (!v.valida) {
        setMensaje(`La CURP no es válida: ${v.motivo}`);
        return;
      }
    }
    if (editFechaLatina.trim() && !esFechaLatinaValida(editFechaLatina)) {
      setMensaje('La fecha de nacimiento no es válida. Escríbela como DD/MM/AAAA, por ejemplo 14/05/1990.');
      return;
    }
    setEditandoPaciente(true);
    try {
      // Solo se mandan los campos con valor: el backend conserva los
      // demás y registra el cambio en el log del trámite (tramiteId).
      // Teléfono vacío = BORRAR el teléfono (contrato PUT). CURP con
      // valor se valida y su 409 (duplicada) se muestra tal cual.
      // Sin contraseña: PUT /api/pacientes/{id} no la toca.
      const actualizado = await actualizarPaciente(
        tramite.paciente.id,
        {
          nombre: editNombre.trim() || undefined,
          curp: curpNormalizada || undefined,
          telefono: editTelefono.trim() ? editTelefono.trim() : '',
          email: editEmail.trim() || undefined,
          fechaNacimiento: editFechaLatina.trim()
            ? (parsearFechaLatina(editFechaLatina) ?? undefined)
            : undefined,
        },
        tramite.id,
      );
      setMensaje(
        `Datos del paciente actualizados: ${actualizado.nombre} · ${actualizado.curp ?? actualizado.telefono ?? 'Sin teléfono'}.`,
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

  // --- Editar la reservación del folio (PUT /api/tramites/{id}) ------
  async function handleEditarTramite(e: React.FormEvent) {
    e.preventDefault();
    if (!tramite) return;
    if (!editNota.trim()) {
      setMensaje('La nota es obligatoria: explica qué cambiaste y por qué.');
      return;
    }
    setEditandoTramite(true);
    try {
      const actualizado = await editarTramite(tramite.id, {
        estudioId: editEstudioId || null,
        clinicaId: editClinicaId || null,
        fechaCita: editCitaFechaISO && editHora ? `${editCitaFechaISO}T${editHora}:00` : null,
        nota: editNota.trim(),
      });
      setTramite(actualizado);
      setTimeline(await getTimeline(actualizado.id));
      setTramites((prev) => prev.map((t) => (t.id === actualizado.id ? actualizado : t)));
      setEditNota('');
      setPanelAccion('ver');
      setMensaje(
        `Reservación actualizada: folio ${actualizado.folio} · ${actualizado.estudio.nombre} — $${actualizado.precioCotizado}. El cambio quedó en el historial y en el Journey.`,
      );
    } catch (err) {
      setMensaje(
        isApiError(err)
          ? `No se pudo editar la reservación (HTTP ${err.status}: ${err.message}).`
          : 'No se pudo editar la reservación. Revisa que el backend esté arriba.',
      );
    } finally {
      setEditandoTramite(false);
    }
  }

  // --- Cancelar o eliminar el folio (con acción y causa obligatorias) --
  async function handleCerrarTramite() {
    if (!tramite || !accionCierre || !motivoCierre.trim()) return;
    setCerrando(true);
    try {
      const actualizado =
        accionCierre === 'cancelar'
          ? await cancelarTramite(tramite.id, motivoCierre.trim())
          : await eliminarTramite(tramite.id, motivoCierre.trim());
      setTramite(actualizado);
      setTimeline(await getTimeline(actualizado.id));
      setTramites((prev) => prev.map((t) => (t.id === actualizado.id ? actualizado : t)));
      setMotivoCierre('');
      setAccionCierre('');
      setPanelAccion('ver');
      setMensaje(
        accionCierre === 'cancelar'
          ? `Folio ${actualizado.folio} cancelado. Quedó registrado con su causa en el historial y en el Journey.`
          : `Folio ${actualizado.folio} eliminado (borrado suave). Quedó registrado qué se eliminó y por qué; su historial sigue consultable por folio.`,
      );
    } catch (err) {
      setMensaje(
        isApiError(err)
          ? `No se pudo ${accionCierre === 'cancelar' ? 'cancelar' : 'eliminar'} el folio (HTTP ${err.status}: ${err.message}).`
          : 'No se pudo cerrar el folio. Revisa que el backend esté arriba.',
      );
    } finally {
      setCerrando(false);
    }
  }

  // --- CONTINUAR PROCESO: carga el folio interrumpido en el flujo de
  // Nueva reservación (mismo camino de retomar del 409), con canal
  // CALL_CENTER, continuando solo lo que falta. Solo usa los endpoints
  // de pasos (cotizar / seleccionar clínica / confirmar): nunca el PUT
  // de edición, así que no pide la nota obligatoria. -----------------
  function cargarFolioEnReserva(t: Tramite) {
    setResPaciente(t.paciente ?? null);
    setResFolios(tramites.filter((x) => !x.eliminado));
    setResTramite(t);
    setResEstudioId(t.estudioId ?? '');
    setResClinicaId(t.clinica?.id ?? '');
    setResFechaISO('');
    setResFechaLatina('');
    setResHora('');
    setResMensaje(
      `Folio ${t.folio} cargado en Nueva reservación: continúa con lo que falta (${textoFaltantes(faltantesDe(t))}), con canal Call Center.`,
    );
    document.getElementById('nueva-reservacion')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  function handleContinuarProceso() {
    if (!tramite) return;
    cargarFolioEnReserva(tramite);
  }

  // --- Nueva reservación: buscar por CURP, teléfono, folio o nombre --
  async function handleBuscarPacienteReserva(e: React.FormEvent) {
    e.preventDefault();
    const q = resIdentificador.trim();
    if (!q) return;
    setResCargando(true);
    setResMensaje('');
    setResTramite(null);
    setResFolios([]);
    // Mismas ramas que la búsqueda superior (folio → CURP → teléfono
    // → nombre). El input NO normaliza a CURP al teclear: eso rompería
    // teléfonos y nombres.
    const folioBuscado = normalizarFolio(q) ?? (esFolio(q) ? q.trim().toUpperCase() : null);
    try {
      let paciente: Paciente;
      let aviso = '';
      if (folioBuscado) {
        const t = await getTramitePorFolio(folioBuscado);
        if (!t.paciente) throw new Error('sin paciente');
        paciente = t.paciente;
      } else if (esCurp(q)) {
        paciente = await buscarPacientePorCurp(q);
      } else if (esTelefono(q)) {
        paciente = await getPacientePorTelefono(q);
      } else {
        const pacientes = await buscarPacientesPorNombre(q);
        if (pacientes.length === 0) throw new Error('sin pacientes');
        paciente = pacientes[0];
        if (pacientes.length > 1) {
          aviso = ` Varios pacientes coinciden (${pacientes.length}): usando a ${paciente.nombre}. Afina la búsqueda si no es él.`;
        }
      }
      const folios = (await getTramitesPorPacienteId(paciente.id)).filter((t) => !t.eliminado);
      setResPaciente(paciente);
      setResFolios(folios);
      setResMensaje(
        `Paciente encontrado: ${paciente.nombre} · ${paciente.curp ?? paciente.telefono ?? 'Sin teléfono'}.${folios.length > 0 ? ` Tiene ${folios.length} folio${folios.length > 1 ? 's' : ''}.` : ' Aún no tiene folios.'}${aviso}`,
      );
    } catch (err) {
      setResPaciente(null);
      setResFolios([]);
      setResMensaje(
        folioBuscado && isApiError(err) && err.status === 404
          ? `No hay ningún trámite con el folio ${folioBuscado}.`
          : folioBuscado && isApiError(err)
            ? `No se pudo buscar el folio ${folioBuscado} (HTTP ${err.status}: ${err.message}).`
            : isApiError(err) && err.status === 404
              ? `No hay ningún paciente con esa CURP, teléfono, folio o nombre.`
              : isApiError(err)
                ? `No se pudo buscar al paciente (HTTP ${err.status}: ${err.message}).`
                : 'El backend no respondió, no se pudo buscar al paciente.',
      );
    } finally {
      setResCargando(false);
    }
  }

  /** Carga un folio del contexto acumulado en el flujo de reservación (retomar con lo que falta). */
  function usarFolioEnReserva(t: Tramite) {
    setResTramite(t);
    setResEstudioId(t.estudioId ?? '');
    setResClinicaId(t.clinica?.id ?? '');
    setResFechaISO('');
    setResFechaLatina('');
    setResHora('');
    setResMensaje(
      t.estado === 'CONFIRMADO'
        ? `Folio ${t.folio}: la cita ya está agendada para el ${t.fechaCita ? formatearFechaHora(t.fechaCita) : 'sin fecha'}. No hay nada que continuar.`
        : `Folio ${t.folio} cargado: continúa con lo que falta (${textoFaltantes(faltantesDe(t))}).`,
    );
  }

  function usarPacienteDelTramite() {
    if (!tramite?.paciente) return;
    setResPaciente(tramite.paciente);
    setResIdentificador(tramite.paciente.curp ?? '');
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
        pacienteId: resPaciente.id,
        estudioId: resEstudioId,
        canal: 'CALL_CENTER',
      });
      const cotizado = await cotizar(creado.id, resEstudioId, 'CALL_CENTER');
      setResTramite(cotizado);
      setResMensaje(`Reservación creada. Folio ${cotizado.folio}. Ahora elige la clínica.`);
    } catch (err) {
      // Retomar en vez de duplicar: el paciente ya tiene un trámite
      // ACTIVO de ese estudio (409 con el folio existente y lo que le
      // falta). Se carga ese folio y el asesor continúa con solo los
      // pasos que le faltan, sin crear uno nuevo. El botón de crear
      // queda deshabilitado (resTramite ya existe) y la clínica ya
      // elegida en ese folio se prellena para la parrilla.
      const existente = parsearTramiteExistente(err);
      if (existente) {
        try {
          const t = await getTramitePorFolio(existente.folio);
          setResTramite(t);
          setResClinicaId(t.clinica?.id ?? '');
          setResFechaISO('');
          setResFechaLatina('');
          setResHora('');
          setResMensaje(
            // CONFIRMADO: la cita ya está agendada; se da el resumen
            // completo y NO se ofrece continuar el flujo. En los demás
            // estados se ofrece retomar con solo los pasos que faltan.
            existente.yaAgendada
              ? existente.mensaje
              : `${existente.mensaje} Te falta: ${textoFaltantes(existente.faltantes)}. Continúa con ese folio, sin crear uno nuevo.`,
          );
          return;
        } catch {
          setResMensaje(existente.mensaje);
          return;
        }
      }
      setResMensaje(
        isApiError(err)
          ? `No se pudo crear la reservación (HTTP ${err.status}: ${err.message}).`
          : 'No se pudo crear la reservación: el backend no respondió.',
      );
    } finally {
      setResCargando(false);
    }
  }

  /** Cotizar el estudio de un folio retomado que aún no lo tiene (INICIADO / DATOS_PENDIENTES). */
  async function handleCotizarResTramite() {
    if (!resTramite || !resEstudioId) return;
    setResCargando(true);
    try {
      const cotizado = await cotizar(resTramite.id, resEstudioId, 'CALL_CENTER');
      setResTramite(cotizado);
      setResMensaje(`Estudio cotizado: ${cotizado.estudio.nombre} — $${cotizado.precioCotizado}. Ahora elige la clínica.`);
    } catch (err) {
      const existente = parsearTramiteExistente(err);
      if (existente) {
        try {
          const t = await getTramitePorFolio(existente.folio);
          setResTramite(t);
          setResClinicaId(t.clinica?.id ?? '');
          setResMensaje(`${existente.mensaje} Te falta: ${textoFaltantes(existente.faltantes)}. Continúa con ese folio, sin crear uno nuevo.`);
          return;
        } catch {
          setResMensaje(existente.mensaje);
          return;
        }
      }
      setResMensaje(
        isApiError(err)
          ? `No se pudo cotizar (HTTP ${err.status}: ${err.message}).`
          : 'No se pudo cotizar: el backend no respondió.',
      );
    } finally {
      setResCargando(false);
    }
  }

  async function handleSeleccionarClinicaReserva() {
    if (!resTramite || !resClinicaId) return;
    setResCargando(true);
    try {
      const actualizado = await seleccionarClinica(resTramite.id, resClinicaId, 'CALL_CENTER');
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
      // La parrilla ya bloquea las horas ocupadas en pantalla; el 409
      // del backend sigue siendo la última palabra y su mensaje (con
      // las horas libres cercanas) se muestra tal cual.
      const confirmado = await confirmar(resTramite.id, fechaHoraISO, 'CALL_CENTER');
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

  // --- Registrar un paciente nuevo (espejo de RegistroPage) -----------
  function handleRegCurpChange(valor: string) {
    const normalizada = normalizarCurp(valor);
    setRegCurp(normalizada);
    setRegMensaje('');
    if (!normalizada) {
      setRegErrorCurp('');
      return;
    }
    const v = validarCurp(normalizada);
    setRegErrorCurp(normalizada.length === 18 && !v.valida ? v.motivo : '');
    if (!v.valida) return;
    // CURP válida: autocompletar la fecha de nacimiento desde la CURP,
    // solo si está vacía o todavía tiene la fecha autocompletada antes.
    const iso = fechaDesdeCurp(normalizada);
    if (!iso) return;
    const latina = formatearFecha(iso);
    if (regFechaLatina === '' || regFechaLatina === regFechaAutocompletada.current) {
      regFechaAutocompletada.current = latina;
      setRegFechaLatina(latina);
      setRegErrorFecha('');
    } else {
      const escritaIso = parsearFechaLatina(regFechaLatina);
      setRegErrorFecha(
        escritaIso && escritaIso !== iso
          ? `La fecha de nacimiento (${regFechaLatina}) no coincide con la fecha de su CURP (${latina}). Corrige una de las dos.`
          : '',
      );
    }
  }

  function handleRegFechaChange(valor: string) {
    setRegFechaLatina(valor);
    setRegMensaje('');
    const iso = fechaDesdeCurp(regCurp);
    if (iso && validarCurp(regCurp).valida) {
      const escritaIso = parsearFechaLatina(valor);
      setRegErrorFecha(
        escritaIso && escritaIso !== iso
          ? `La fecha de nacimiento (${valor}) no coincide con la fecha de su CURP (${formatearFecha(iso)}). Corrige una de las dos.`
          : '',
      );
    } else {
      setRegErrorFecha('');
    }
  }

  async function handleRegistrarPaciente(e: React.FormEvent) {
    e.preventDefault();
    setRegMensaje('');
    if (!regNombre.trim()) {
      setRegMensaje('El nombre completo es obligatorio.');
      return;
    }
    const vCurp = validarCurp(regCurp);
    if (!vCurp.valida) {
      setRegErrorCurp(vCurp.motivo);
      setRegMensaje(vCurp.motivo);
      return;
    }
    if (regPassword.length < 6) {
      setRegMensaje('La contraseña debe tener al menos 6 caracteres.');
      return;
    }
    const fechaIso = parsearFechaLatina(regFechaLatina);
    if (!esFechaLatinaValida(regFechaLatina) || !fechaIso) {
      setRegMensaje('La fecha de nacimiento no es válida. Escríbela como DD/MM/AAAA, por ejemplo 14/05/1990.');
      return;
    }
    const fechaCurp = fechaDesdeCurp(regCurp);
    if (fechaCurp && fechaIso !== fechaCurp) {
      const msg = `La fecha de nacimiento (${regFechaLatina}) no coincide con la fecha de su CURP (${formatearFecha(fechaCurp)}). Corrige una de las dos.`;
      setRegErrorFecha(msg);
      setRegMensaje(msg);
      return;
    }
    setRegGuardando(true);
    try {
      // POST /api/pacientes (NO /api/auth/registro: ese hace auto-login
      // y sobrescribiría la sesión del asesor). El paciente queda con
      // su CURP + esta contraseña para iniciar sesión después.
      const creado = await registrarPacienteAsesor({
        nombre: regNombre.trim(),
        curp: normalizarCurp(regCurp),
        telefono: regTelefono.trim() ? regTelefono.trim() : null,
        email: regEmail.trim() ? regEmail.trim() : null,
        fechaNacimiento: fechaIso,
        password: regPassword,
      });
      setRegMensaje(
        `Paciente registrado: ${creado.nombre} · ${creado.curp ?? 'Sin CURP'}. Ya puede iniciar sesión con su CURP y la contraseña que le diste. Tu sesión de asesor sigue intacta.`,
      );
      setResPaciente(creado);
      setResFolios([]);
      setResTramite(null);
      setRegNombre('');
      setRegCurp('');
      setRegTelefono('');
      setRegEmail('');
      setRegFechaLatina('');
      setRegPassword('');
      regFechaAutocompletada.current = '';
    } catch (err) {
      setRegMensaje(
        isApiError(err) && err.status === 409
          ? err.message || 'Esa CURP o ese teléfono ya está registrado.'
          : isApiError(err)
            ? `No se pudo registrar al paciente (HTTP ${err.status}: ${err.message}).`
            : 'No se pudo registrar al paciente. Revisa que el backend esté arriba.',
      );
    } finally {
      setRegGuardando(false);
    }
  }

  const foliosActivos = tramites.filter((t) => !t.eliminado);
  const foliosEliminados = tramites.filter((t) => t.eliminado);
  const tramiteEditable =
    tramite !== null && !tramite.eliminado && !ESTADOS_TERMINALES.includes(tramite.estado);
  const tramiteContinuable =
    tramite !== null && !tramite.eliminado && ESTADOS_CONTINUABLES.includes(tramite.estado);

  return (
    <div>
      <h1>Call Center — consola del asesor</h1>
      <p className="muted">Busca por CURP, teléfono, folio o nombre y ve todo el contexto acumulado: el paciente nunca repite su historia.</p>

      <form className="card form-inline" onSubmit={handleBuscar}>
        <input
          value={busqueda}
          onChange={(e) => setBusqueda(e.target.value)}
          placeholder="CURP, teléfono, folio (ej. SD-2026-0001) o nombre"
          aria-label="CURP, teléfono, folio o nombre"
        />
        <button type="submit" className="btn btn-primary" disabled={buscando}>
          {buscando ? 'Buscando…' : 'Buscar trámite'}
        </button>
      </form>

      {mensaje && <p className="notice" role="status">{mensaje}</p>}

      {foliosActivos.length > 1 && (
        <section className="card" style={{ marginTop: 16 }}>
          <h2>Folios del paciente</h2>
          <ul>
            {foliosActivos.map((t) => {
              const modificable = !t.eliminado;
              const editable = !t.eliminado && !ESTADOS_TERMINALES.includes(t.estado);
              const continuable = !t.eliminado && ESTADOS_CONTINUABLES.includes(t.estado);
              return (
                <li key={t.id} className="dato-row">
                  <span>
                    <strong>{t.folio}</strong> · {t.estudio.nombre} — ${t.precioCotizado} <EstadoBadge estado={t.estado} clinica={t.clinica?.nombre} />
                  </span>
                  <span className="actions">
                    <button type="button" className="btn btn-small" onClick={() => { void cargarTramite(t); setPanelAccion('ver'); }}>
                      Ver
                    </button>
                    <Link className="btn btn-small btn-secondary" to={`/journey/${encodeURIComponent(t.folio)}`}>
                      Ver Journey
                    </Link>
                    {modificable && (
                      <button type="button" className="btn btn-small" onClick={() => { void cargarTramite(t); setPanelAccion('modificar'); }}>
                        Modificar
                      </button>
                    )}
                    {editable && (
                      <button type="button" className="btn btn-small" onClick={() => { void cargarTramite(t); setPanelAccion('cierre'); }}>
                        Eliminar / Cancelar
                      </button>
                    )}
                    {continuable && (
                      <button type="button" className="btn btn-small btn-primary" onClick={() => cargarFolioEnReserva(t)}>
                        CONTINUAR PROCESO
                      </button>
                    )}
                  </span>
                </li>
              );
            })}
          </ul>
        </section>
      )}

      {foliosEliminados.length > 0 && (
        <section className="card" style={{ marginTop: 16 }}>
          <h2>Historial: folios eliminados</h2>
          <p className="muted">
            Estos folios se eliminaron (borrado suave): ya no salen en la lista de folios, pero su historial
            sigue consultable por folio, solo lectura.
          </p>
          <ul>
            {foliosEliminados.map((t) => (
              <li key={t.id} className="dato-row">
                <span>
                  <strong>{t.folio}</strong> · {t.estudio.nombre} — <span className="badge badge-danger">Eliminado</span>
                  {t.eliminadoMotivo ? ` · Motivo: ${t.eliminadoMotivo}` : ''}
                </span>
                <button type="button" className="btn btn-small" onClick={() => void cargarTramite(t)}>
                  Ver historial
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      {tramite && (
        <>
          <div className="card actions" style={{ marginTop: 16 }}>
            <strong>{tramite.folio}</strong> <EstadoBadge estado={tramite.estado} clinica={tramite.clinica?.nombre} />
            <button type="button" className={panelAccion === 'ver' ? 'btn btn-primary' : 'btn btn-secondary'} onClick={() => setPanelAccion('ver')}>
              Ver
            </button>
            {!tramite.eliminado && (
              <button type="button" className={panelAccion === 'modificar' ? 'btn btn-primary' : 'btn btn-secondary'} onClick={() => setPanelAccion('modificar')}>
                Modificar
              </button>
            )}
            {tramiteEditable && (
              <button type="button" className={panelAccion === 'cierre' ? 'btn btn-primary' : 'btn btn-secondary'} onClick={() => setPanelAccion('cierre')}>
                Eliminar / Cancelar
              </button>
            )}
            {tramiteContinuable && (
              <button type="button" className="btn btn-primary" onClick={handleContinuarProceso}>
                CONTINUAR PROCESO
              </button>
            )}
            <Link className="btn btn-secondary" to={`/journey/${encodeURIComponent(tramite.folio)}`}>
              Ver Journey
            </Link>
          </div>
          <div className="grid-2" style={{ marginTop: 16 }}>
            <section className="card">
              {panelAccion === 'ver' && (
                <>
                  <h2>Contexto acumulado</h2>
            <p>
              Folio: <strong>{tramite.folio}</strong> <EstadoBadge estado={tramite.estado} clinica={tramite.clinica?.nombre} />
              {tramite.eliminado && <span className="badge badge-danger"> Eliminado</span>}
            </p>
            {tramite.eliminado && tramite.eliminadoMotivo && (
              <p className="notice" role="status">
                Este folio se eliminó. Motivo: {tramite.eliminadoMotivo}. Solo lectura: su historial sigue abajo.
              </p>
            )}
            <dl className="kv">
              <dt>Paciente</dt><dd>{tramite.paciente?.nombre ?? '—'}</dd>
              <dt>CURP</dt><dd>{tramite.paciente?.curp ?? '—'}</dd>
              <dt>Teléfono</dt><dd>{tramite.paciente?.telefono ?? 'Sin teléfono'}</dd>
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
                    <button type="button" className="btn btn-small" onClick={() => void handleCompletarDato(d)} disabled={guardando || tramite.eliminado}>
                      Completar dato
                    </button>
                  </li>
                ))}
              </ul>
            )}

            <h3>Nota del asesor</h3>
            <div className="form">
              <textarea value={nota} onChange={(e) => setNota(e.target.value)} rows={3} placeholder="Escribe una nota para el expediente…" disabled={tramite.eliminado} />
              <button type="button" className="btn btn-secondary" onClick={() => void handleGuardarNota()} disabled={guardando || tramite.eliminado}>
                Guardar nota
              </button>
            </div>
          </>
        )}
        {panelAccion === 'modificar' && !tramite.eliminado && (
          <>
            <h3>Editar datos del paciente</h3>
                <form onSubmit={handleEditarPaciente} className="form">
                  <label>
                    Nombre completo
                    <input value={editNombre} onChange={(e) => setEditNombre(e.target.value)} required />
                  </label>
                  <label>
                    CURP
                    <input
                      value={editCurp}
                      onChange={(e) => setEditCurp(normalizarCurp(e.target.value))}
                      placeholder="CURP (18 caracteres)"
                      maxLength={18}
                      style={{ textTransform: 'uppercase' }}
                    />
                  </label>
                  <label>
                    Teléfono (10 dígitos, sin +52; vacío = borrar el teléfono)
                    <input value={editTelefono} onChange={(e) => setEditTelefono(e.target.value)} />
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
                {tramiteEditable && (
                  <>
                    <h3>Editar la reservación</h3>
                <p className="muted">
                  Cambia el estudio, la clínica o la fecha y hora. Si cambias el estudio, el precio se vuelve a
                  cotizar y se verifica que no tenga otro folio activo de ese estudio. La nota es obligatoria y queda en el historial.
                </p>
                <form onSubmit={handleEditarTramite} className="form">
                  <label>
                    Estudio
                    <select value={editEstudioId} onChange={(e) => setEditEstudioId(e.target.value)}>
                      <option value="">Sin estudio</option>
                      {estudios.map((est) => (
                        <option key={est.id} value={est.id}>
                          {est.nombre} — ${est.precio}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Clínica
                    <select value={editClinicaId} onChange={(e) => setEditClinicaId(e.target.value)}>
                      <option value="">Sin clínica</option>
                      {clinicas.map((c) => (
                        <option key={c.id} value={c.id}>
                          {c.nombre} ({c.ciudad})
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Fecha (calendario)
                    <input type="date" value={editCitaFechaISO} onChange={(e) => editCitaFijarFechaISO(e.target.value)} />
                  </label>
                  <label>
                    Fecha (DD/MM/AAAA)
                    <input
                      type="text"
                      value={editCitaFechaLatina}
                      onChange={(e) => editCitaFijarFechaLatina(e.target.value)}
                      placeholder="25/11/2026"
                      inputMode="numeric"
                    />
                  </label>
                  {editClinicaId && editCitaFechaISO && (
                    <ParrillaHorarios
                      clinicaId={editClinicaId}
                      fechaISO={editCitaFechaISO}
                      estudioId={editEstudioId}
                      exceptoTramiteId={tramite.id}
                      horaSeleccionada={editHora}
                      onSeleccionar={setEditHora}
                    />
                  )}
                  <label>
                    Nota (obligatoria): qué cambiaste y por qué
                    <textarea
                      value={editNota}
                      onChange={(e) => setEditNota(e.target.value)}
                      rows={2}
                      placeholder="Ej. El paciente pidió cambiar la cita al viernes por trabajo"
                      required
                    />
                  </label>
                  <button type="submit" className="btn btn-secondary" disabled={editandoTramite || !editNota.trim()}>
                    {editandoTramite ? 'Guardando…' : 'Guardar cambios de la reservación'}
                  </button>
                </form>
                  </>
                )}
              </>
            )}
            {panelAccion === 'cierre' && tramiteEditable && (
              <>
                <h3>Cancelar o eliminar el folio</h3>
                <p className="muted">
                  Primero elige la acción y escribe la causa (ambos obligatorios). Cancelar deja el folio marcado
                  como cancelado; eliminar lo saca de las listas (borrado suave) pero su historial se conserva.
                </p>
                <div className="form">
                  <label>
                    Acción
                    <select value={accionCierre} onChange={(e) => setAccionCierre(e.target.value as '' | 'cancelar' | 'eliminar')}>
                      <option value="">Elige una acción…</option>
                      <option value="cancelar">Cancelar el folio</option>
                      <option value="eliminar">Eliminar el folio (borrado suave)</option>
                    </select>
                  </label>
                  <label>
                    Causa (obligatoria)
                    <textarea
                      value={motivoCierre}
                      onChange={(e) => setMotivoCierre(e.target.value)}
                      rows={2}
                      placeholder="Ej. El paciente pidió cancelar porque ya se hizo el estudio en otro lugar"
                    />
                  </label>
                  {accionCierre === 'eliminar' && (
                    <p className="notice" role="alert">
                      Eliminar es borrado suave: el folio sale de las listas, pero su Journey e historial quedan
                      guardados con esta causa y se pueden consultar por folio.
                    </p>
                  )}
                  <button
                    type="button"
                    className="btn btn-secondary"
                    onClick={() => void handleCerrarTramite()}
                    disabled={cerrando || !accionCierre || !motivoCierre.trim()}
                  >
                    {cerrando
                      ? 'Guardando…'
                      : accionCierre === 'cancelar'
                        ? 'Cancelar folio'
                        : accionCierre === 'eliminar'
                          ? 'Eliminar folio'
                          : 'Elige la acción y escribe la causa'}
                  </button>
                </div>
              </>
            )}
          </section>

          <section className="card">
            <h2>Historial del folio</h2>
            <button type="button" className="btn btn-secondary" onClick={() => setMostrarHistorial((v) => !v)}>
              {mostrarHistorial ? 'Ocultar historial' : 'Ver historial'}
            </button>
            {mostrarHistorial && <Timeline eventos={timeline} />}
          </section>
        </div>
        </>
      )}

      <section className="card" id="nueva-reservacion" style={{ marginTop: 16 }}>
        <h2>Nueva reservación</h2>
        <p className="muted">
          Busca al paciente por CURP, teléfono, folio o nombre (sin pedir teléfono al crear la reservación):
          elige al paciente, luego el estudio, la clínica y la fecha y hora de la cita.
        </p>

        <h3>Paciente</h3>
        <form className="form-inline" onSubmit={handleBuscarPacienteReserva}>
          <input
            value={resIdentificador}
            onChange={(e) => setResIdentificador(e.target.value)}
            placeholder="CURP, teléfono, folio o nombre del paciente"
            aria-label="CURP, teléfono, folio o nombre del paciente"
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
          <div>
            <p>
              Paciente: <strong>{resPaciente.nombre}</strong> · {resPaciente.curp ?? resPaciente.telefono ?? 'Sin teléfono'}
              {resPaciente.fechaNacimiento ? ` · Nació el ${formatearFecha(resPaciente.fechaNacimiento)}` : ''}
            </p>
            {resFolios.length > 0 && (
              <>
                <h3>Contexto acumulado: sus folios</h3>
                <ul>
                  {resFolios.map((t) => (
                    <li key={t.id} className="dato-row">
                      <span>
                        <strong>{t.folio}</strong> · {t.estudio.nombre} — ${t.precioCotizado}{' '}
                        <EstadoBadge estado={t.estado} clinica={t.clinica?.nombre} />
                        {ESTADOS_TERMINALES.includes(t.estado)
                          ? ''
                          : ` · Falta: ${textoFaltantes(faltantesDe(t))}`}
                      </span>
                      <button type="button" className="btn btn-small" onClick={() => usarFolioEnReserva(t)}>
                        Usar este folio
                      </button>
                    </li>
                  ))}
                </ul>
              </>
            )}
          </div>
        )}
        {resMensaje && <p className="notice" role="status">{resMensaje}</p>}

        {resPaciente && (
          <>
            <h3>1 · Estudio</h3>
            <div className="form">
              <label>
                Estudio
                <select value={resEstudioId} onChange={(e) => setResEstudioId(e.target.value)}>
                  <option value="">Selecciona un estudio</option>
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
                disabled={resCargando || !!resTramite || !resEstudioId}
              >
                Crear reservación
              </button>
              {resTramite && (resTramite.estado === 'INICIADO' || resTramite.estado === 'DATOS_PENDIENTES') && (
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => void handleCotizarResTramite()}
                  disabled={resCargando || !resEstudioId}
                >
                  Cotizar estudio de este folio
                </button>
              )}
            </div>

            {resTramite && (
              <>
                <p>
                  Folio: <strong>{resTramite.folio}</strong> <EstadoBadge estado={resTramite.estado} clinica={resTramite.clinica?.nombre} /> ·{' '}
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
                    </div>
                    <ParrillaHorarios
                      clinicaId={resClinicaId}
                      fechaISO={resFechaISO}
                      estudioId={resEstudioId}
                      exceptoTramiteId={resTramite.id}
                      horaSeleccionada={resHora}
                      onSeleccionar={setResHora}
                    />
                    <div className="actions">
                      <button
                        type="button"
                        className="btn btn-primary"
                        onClick={() => void handleConfirmarReserva()}
                        disabled={
                          !resFechaISO || !resHora || resCargando
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

      <section className="card" style={{ marginTop: 16 }}>
        <h2>Registrar nuevo paciente</h2>
        <p className="muted">
          Registra al paciente para que pueda iniciar sesión con su CURP y la contraseña que le des.
          Solo el teléfono y el correo son opcionales; el resto es obligatorio. Tu sesión de asesor no se toca.
        </p>
        <form onSubmit={handleRegistrarPaciente} className="form" style={{ maxWidth: 480 }}>
          <label>
            Nombre completo
            <input value={regNombre} onChange={(e) => setRegNombre(e.target.value)} placeholder="Ej. Ana García López" autoComplete="name" required />
          </label>
          <label>
            CURP (obligatoria, 18 caracteres)
            <input
              value={regCurp}
              onChange={(e) => handleRegCurpChange(e.target.value)}
              placeholder="Ej. GALA900514MDFRPN01"
              autoComplete="off"
              maxLength={18}
              style={{ textTransform: 'uppercase' }}
              required
            />
          </label>
          {regErrorCurp && (
            <p className="notice" role="alert">{regErrorCurp}</p>
          )}
          <label>
            Teléfono (WhatsApp) — opcional
            <input value={regTelefono} onChange={(e) => setRegTelefono(e.target.value)} placeholder="2220000001 (opcional)" autoComplete="tel" />
          </label>
          <label>
            Correo electrónico — opcional
            <input type="email" value={regEmail} onChange={(e) => setRegEmail(e.target.value)} placeholder="tu@correo.com" autoComplete="email" />
          </label>
          <label>
            Fecha de nacimiento
            <input
              type="text"
              value={regFechaLatina}
              onChange={(e) => handleRegFechaChange(e.target.value)}
              placeholder="DD/MM/AAAA"
              inputMode="numeric"
              required
            />
          </label>
          <p className="muted" style={{ marginTop: -6 }}>
            Se autocompleta desde la CURP; puedes cambiarla, pero debe coincidir con la fecha de la CURP.
          </p>
          {regErrorFecha && (
            <p className="notice" role="alert">{regErrorFecha}</p>
          )}
          <label>
            Contraseña (mínimo 6 caracteres)
            <PasswordInput
              value={regPassword}
              onChange={setRegPassword}
              placeholder="Mínimo 6 caracteres"
              autoComplete="new-password"
              minLength={6}
              required
            />
          </label>
          {regMensaje && <p className="notice" role="status">{regMensaje}</p>}
          <div className="actions">
            <button type="submit" className="btn btn-primary" disabled={regGuardando}>
              {regGuardando ? 'Registrando…' : 'Registrar paciente'}
            </button>
            <a
              className="btn btn-secondary"
              href="https://www.gob.mx/curp/"
              target="_blank"
              rel="noopener noreferrer"
            >
              CONSULTAR CURP
            </a>
          </div>
        </form>
      </section>
    </div>
  );
}
