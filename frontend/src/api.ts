import { MOCK_CLINICAS, MOCK_ESTUDIOS } from './mock';
import type { Clinica, Estudio, Evento, Journey, Paciente, Tramite } from './types';

export const API_BASE = 'http://localhost:8080/api';

// true cuando la última llamada usó el fallback mock (backend no disponible).
export let isMock = false;

export function isMockMode(): boolean {
  return isMock;
}

/**
 * Error con respuesta del backend (HTTP 4xx/5xx): el backend SI respondio,
 * asi que NO es un fallo de red y NO debe usarse el mock. Un error sin
 * status (TypeError de fetch, AbortError por timeout) es el backend caido.
 */
export class ApiError extends Error {
  readonly status: number;
  constructor(status: number, message?: string) {
    super(message ?? `HTTP ${status}`);
    this.status = status;
  }
}

export function isApiError(err: unknown): err is ApiError {
  return err instanceof ApiError;
}

/** El backend no respondio (red caida, timeout, backend apagado): ahi si vale el mock. */
export function isNetworkError(err: unknown): boolean {
  return !isApiError(err);
}

// --- Sesión (login / registro reales contra Postgres) --------------------
// --- Teléfonos a 10 dígitos, sin +52 --------------------------------------
// Regla momentánea: todos los teléfonos se guardan y se comparan a
// 10 dígitos. Misma regla que TelefonoUtil en el backend: se quitan
// espacios, guiones y paréntesis, y el prefijo +52 / 0052 / 52 inicial
// solo cuando sobran dígitos.
export function normalizarTelefono(telefono: string | null | undefined): string {
  if (!telefono) return '';
  let digitos = telefono.replace(/\D/g, '');
  while (digitos.length > 10) {
    if (digitos.startsWith('0052')) digitos = digitos.substring(4);
    else if (digitos.startsWith('52')) digitos = digitos.substring(2);
    else break;
  }
  return digitos;
}

export interface Sesion {
  token: string;
  usuario: string;
  rol: string;
  paciente?: Paciente | null;
}

export interface PerfilYo {
  paciente: Paciente | null;
  usuario: string;
  rol: string;
  /** Nombre del staff (p. ej. "Alejandro Ruiz" para el doctor) o del paciente. */
  nombre: string | null;
}

const TOKEN_KEY = 'saluddigna-token';

// La sesión vive en sessionStorage: dura lo que dura la pestaña.
// Al cerrar el navegador o abrir una pestaña nueva, la app arranca
// limpia, sin ningún usuario cargado. Si queda un token viejo en
// localStorage (de antes de este cambio), se borra al cargar el bundle.
if (typeof window !== 'undefined') {
  window.localStorage.removeItem(TOKEN_KEY);
}

export function getToken(): string | null {
  return window.sessionStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  window.sessionStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  window.sessionStorage.removeItem(TOKEN_KEY);
  window.localStorage.removeItem(TOKEN_KEY);
}

export function haySesion(): boolean {
  return getToken() !== null;
}

function normalizarPaciente(raw: Paciente): Paciente {
  return {
    id: String(raw.id),
    nombre: raw.nombre,
    telefono: raw.telefono,
    fechaNacimiento: raw.fechaNacimiento ?? '',
  };
}

function normalizarSesion(raw: Sesion): Sesion {
  return {
    token: raw.token,
    usuario: raw.usuario,
    rol: raw.rol,
    paciente: raw.paciente ? normalizarPaciente(raw.paciente) : null,
  };
}

/** Login de paciente (teléfono a 10 dígitos o primer nombre + contraseña) o staff. */
export async function login(usuario: string, password: string): Promise<Sesion> {
  // Solo se normaliza si parece teléfono (7+ dígitos); "ana"/"asesor" se mandan tal cual.
  const digitos = normalizarTelefono(usuario);
  const usuarioFinal = /^\d{7,}$/.test(digitos) ? digitos : usuario.trim();
  const sesion = normalizarSesion(
    await fetchJson<Sesion>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ usuario: usuarioFinal, password }),
    }),
  );
  setToken(sesion.token);
  return sesion;
}

export interface RegistroInput {
  nombre: string;
  telefono: string;
  email: string;
  fechaNacimiento: string;
  password: string;
}

/** Registro de paciente nuevo en la BD; hace auto-login al crear la cuenta. */
export async function registro(input: RegistroInput): Promise<Sesion> {
  const sesion = normalizarSesion(
    await fetchJson<Sesion>('/auth/registro', {
      method: 'POST',
      body: JSON.stringify({ ...input, telefono: normalizarTelefono(input.telefono) }),
    }),
  );
  setToken(sesion.token);
  return sesion;
}

/** Perfil de la sesión actual (GET /api/auth/yo). 401 si el token no vale. */
export async function getPerfilYo(): Promise<PerfilYo> {
  const raw = await fetchJson<PerfilYo>('/auth/yo');
  return {
    ...raw,
    nombre: raw.nombre ?? null,
    paciente: raw.paciente ? normalizarPaciente(raw.paciente) : null,
  };
}

/** Cierra la sesión en este navegador. */
export function logout(): void {
  clearToken();
}

/** ¿El backend está vivo? Usa /api/health, sin marcar mock si falla. */
export async function checkBackend(): Promise<boolean> {
  try {
    await fetchJson<{ status: string }>('/health', undefined, 3000);
    markLive();
    return true;
  } catch {
    markMock();
    return false;
  }
}

function markMock(): void {
  isMock = true;
}

function markLive(): void {
  isMock = false;
}

// Timeout para las escrituras (crear trámite, cotizar, elegir clínica,
// confirmar): 15 s en vez de 4 s. Tras arrancar Docker el backend está
// frío y una respuesta puede tardar más de 4 s: el cliente abortaba,
// el servidor igual guardaba el folio, y el front decía que no se creó
// nada (falso negativo). Las lecturas siguen a 4 s.
export const ESCRITURA_TIMEOUT_MS = 15000;

async function fetchJson<T>(path: string, init?: RequestInit, timeoutMs = 4000): Promise<T> {  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), timeoutMs);
  try {
    const token = getToken();
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (token) headers.Authorization = `Bearer ${token}`;
    for (const [k, v] of Object.entries(init?.headers ?? {})) headers[k] = v as string;
    const res = await fetch(`${API_BASE}${path}`, { ...init, headers, signal: controller.signal });
    if (!res.ok) throw new ApiError(res.status, await mensajeDeError(res, path));
    markLive();
    return (await res.json()) as T;
  } finally {
    window.clearTimeout(timer);
  }
}

/** Mensaje del backend (Spring devuelve {"message": "..."}), para mostrarlo en pantalla. */
async function mensajeDeError(res: Response, path: string): Promise<string> {
  const texto = await res.text().catch(() => '');
  if (texto) {
    try {
      const json = JSON.parse(texto) as { message?: string; error?: string };
      if (json.message) return json.message;
      if (json.error) return json.error;
    } catch {
      return texto;
    }
  }
  return `HTTP ${res.status} en ${path}`;
}

// --- Resolucion de identificadores ---------------------------------------
// Un tramite se puede pedir por UUID (id interno), por folio SD-2026-XXXX
// o por telefono (devuelve el tramite activo mas reciente de ese paciente).
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const FOLIO_RE = /^SD-\d{4}-\d{4}$/i;

export function esFolio(q: string): boolean {
  return FOLIO_RE.test(q.trim());
}

/**
 * Normaliza una consulta a folio canónico "SD-AAAA-NNNN" (año 2026 por
 * defecto cuando solo viene el número). Acepta "sd-2026-0012",
 * "sd 2026 0012", "sd20260012", "2026-0012", "20260012" y "0012".
 * Devuelve null si no parece un folio.
 */
export function normalizarFolio(q: string): string | null {
  const t = q.trim().toUpperCase();
  let m = /^SD[\s-]?(\d{4})[\s-]?(\d{4})$/.exec(t);
  if (m) return `SD-${m[1]}-${m[2]}`;
  m = /^(\d{4})[\s-](\d{4})$/.exec(t);
  if (m) return `SD-${m[1]}-${m[2]}`;
  m = /^(\d{4})(\d{4})$/.exec(t);
  if (m) return `SD-${m[1]}-${m[2]}`;
  m = /^(\d{4})$/.exec(t);
  if (m) return `SD-2026-${m[1]}`;
  return null;
}

export function esTelefono(q: string): boolean {
  const digitos = q.replace(/\D/g, '');
  return digitos.length >= 7 && !UUID_RE.test(q.trim()) && !FOLIO_RE.test(q.trim());
}

// --- Normalización backend -> frontend ---------------------------------
// El backend devuelve Tramite con estudioId/clinicaId numéricos (Long),
// paciente anidado, precioCotizado y datosPendientes como JSON string.
// El front trabaja con estudio/clinica anidados y datosPendientes como array.
function toNumberId(id: string | number | undefined | null): number {
  if (id == null) return NaN;
  if (typeof id === 'number') return id;
  const m = String(id).match(/(\d+)\s*$/);
  return m ? parseInt(m[1], 10) : NaN;
}

export function normalizeTramite(raw: Record<string, unknown>): Tramite {
  const estudioId = toNumberId(raw.estudioId as string | number);
  const clinicaId = toNumberId(raw.clinicaId as string | number);
  const estudioObj = raw.estudio as Estudio | undefined;
  const clinicaObj = raw.clinica as Clinica | null | undefined;
  const estudio: Estudio =
    estudioObj && estudioObj.nombre
      ? { id: String(estudioObj.id), nombre: estudioObj.nombre, precio: Number(estudioObj.precio), descripcion: estudioObj.descripcion }
      : MOCK_ESTUDIOS[estudioId >= 1 && estudioId <= MOCK_ESTUDIOS.length ? estudioId - 1 : 0];
  const clinica: Clinica | null =
    clinicaObj && clinicaObj.nombre
      ? { id: String(clinicaObj.id), nombre: clinicaObj.nombre, ciudad: clinicaObj.ciudad, direccion: clinicaObj.direccion }
      : clinicaId >= 1 && clinicaId <= MOCK_CLINICAS.length
        ? MOCK_CLINICAS[clinicaId - 1]
        : null;
  const pacienteRaw = raw.paciente as Paciente | undefined;
  const paciente: Paciente | undefined = pacienteRaw
    ? {
        id: String(pacienteRaw.id),
        nombre: pacienteRaw.nombre,
        telefono: pacienteRaw.telefono,
        fechaNacimiento: pacienteRaw.fechaNacimiento ?? '',
      }
    : undefined;
  let datosPendientes: string[] = [];
  if (Array.isArray(raw.datosPendientes)) datosPendientes = raw.datosPendientes as string[];
  else if (typeof raw.datosPendientes === 'string' && raw.datosPendientes) {
    try {
      const parsed = JSON.parse(raw.datosPendientes) as Record<string, string>;
      datosPendientes = Object.keys(parsed);
    } catch {
      datosPendientes = [];
    }
  }
  return {
    id: String(raw.id ?? ''),
    folio: String(raw.folio ?? ''),
    estado: raw.estado as Tramite['estado'],
    paciente,
    estudio,
    clinica,
    precioCotizado: Number(raw.precioCotizado ?? estudio.precio),
    fechaCita: (raw.fechaCita as string | null) ?? null,
    canalOrigen: (raw.canalOrigen as Tramite['canalOrigen']) ?? 'WEB',
    datosPendientes,
  };
}

// --- Catálogo ------------------------------------------------------------
export async function getEstudios(): Promise<Estudio[]> {
  try {
    const list = await fetchJson<Estudio[]>('/estudios');
    return list.map((e) => ({ ...e, id: String(e.id) }));
  } catch {
    markMock();
    return MOCK_ESTUDIOS;
  }
}

export async function getClinicas(): Promise<Clinica[]> {
  try {
    const list = await fetchJson<Clinica[]>('/clinicas');
    return list.map((c) => ({ ...c, id: String(c.id) }));
  } catch {
    markMock();
    return MOCK_CLINICAS;
  }
}

/** Trámite por folio legible (GET /api/tramites/folio/{folio}). Sin fallback mock: 404 o backend caído se propagan. Timeout largo (15 s): el backend frío puede tardar. */
export async function getTramitePorFolio(folio: string): Promise<Tramite> {
  const canonico = normalizarFolio(folio) ?? folio.trim();
  const raw = await fetchJson<Record<string, unknown>>(
    `/tramites/folio/${encodeURIComponent(canonico)}`,
    undefined,
    ESCRITURA_TIMEOUT_MS,
  );
  return normalizeTramite(raw);
}

/** TODOS los trámites (folios) de un paciente por teléfono, del más reciente al más viejo. */
export async function getTramitesPorTelefono(telefono: string): Promise<Tramite[]> {
  try {
    const list = await fetchJson<Record<string, unknown>[]>(
      `/tramites?telefono=${encodeURIComponent(normalizarTelefono(telefono))}`,
    );
    return list.map(normalizeTramite);
  } catch (err) {
    // 404 real (paciente no existe en la BD): lista vacia.
    // Error de red: se propaga, la pagina muestra que el backend no responde.
    if (isApiError(err)) return [];
    throw err;
  }
}

/** Trámites en un estado dado (ej. CONFIRMADO, para la recepción de clínica). */
/** Trámites en un estado dado. Sin fallback mock: si el backend no responde, el error se propaga. */
export async function getTramitesPorEstado(estado: string): Promise<Tramite[]> {
  const list = await fetchJson<Record<string, unknown>[]>(
    `/tramites?estado=${encodeURIComponent(estado)}`,
  );
  return list.map(normalizeTramite);
}

/** Paciente por teléfono (GET /api/pacientes?telefono=). Sin fallback mock. */
export async function getPacientePorTelefono(telefono: string): Promise<Paciente> {
  return fetchJson<Paciente>(`/pacientes?telefono=${encodeURIComponent(normalizarTelefono(telefono))}`);
}

export interface ActualizarPacienteInput {
  nombre?: string;
  telefono?: string;
  email?: string;
  /** Fecha de nacimiento en ISO AAAA-MM-DD. */
  fechaNacimiento?: string;
}

/**
 * Edita un paciente (PUT /api/pacientes/{id}). 409 si el teléfono choca
 * con otro paciente. Con tramiteId el cambio queda en el log de eventos
 * de ese trámite. La contraseña NO se toca desde aquí. Sin fallback mock.
 */
export async function actualizarPaciente(
  id: string,
  input: ActualizarPacienteInput,
  tramiteId?: string,
): Promise<Paciente> {
  const body: Record<string, unknown> = { ...input };
  if (input.telefono) body.telefono = normalizarTelefono(input.telefono);
  if (tramiteId) body.tramiteId = tramiteId;
  const raw = await fetchJson<Paciente>(`/pacientes/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
  return normalizarPaciente(raw);
}

/** Horas ocupadas ("HH:mm") de una clínica en un día (GET /api/disponibilidad/dia). */
export async function getHorasOcupadas(clinicaId: string, fechaISO: string): Promise<string[]> {
  const raw = await fetchJson<{ horasOcupadas?: string[] }>(
    `/disponibilidad/dia?clinicaId=${encodeURIComponent(clinicaId)}&fecha=${encodeURIComponent(fechaISO)}`,
  );
  return Array.isArray(raw.horasOcupadas) ? raw.horasOcupadas : [];
}

/**
 * ¿El hueco (clínica, fecha, hora) ya está ocupado por otra cita
 * CONFIRMADA? GET /api/disponibilidad. `exceptoTramiteId` excluye la
 * cita propia al reconfirmar.
 */
export async function verificarDisponibilidad(
  clinicaId: string,
  fechaHoraISO: string,
  exceptoTramiteId?: string,
): Promise<boolean> {
  const params = new URLSearchParams({
    clinicaId,
    fechaHora: fechaHoraISO,
    ...(exceptoTramiteId ? { exceptoTramiteId } : {}),
  });
  const raw = await fetchJson<{ ocupado?: boolean }>(`/disponibilidad?${params.toString()}`);
  return raw.ocupado === true;
}

/**
 * Citas CONFIRMADAS del doctor en un día (GET /api/doctor/citas).
 * Sin fecha = hoy (lo decide el backend).
 */
export async function getCitasDoctor(fechaISO?: string): Promise<CitaClinica[]> {
  const query = fechaISO ? `?fecha=${encodeURIComponent(fechaISO)}` : '';
  const list = await fetchJson<CitaClinica[]>(`/doctor/citas${query}`);
  return list.map((c) => ({ ...c, precio: c.precio == null ? null : Number(c.precio) }));
}

/** Doctor: registra el check-in del paciente (CONFIRMADO, sin cambiar el estado). 409 si ya hay check-in. */
export async function checkInTramite(tramiteId: string): Promise<Tramite> {
  const raw = await fetchJson<Record<string, unknown>>(`/tramites/${encodeURIComponent(tramiteId)}/check-in`, {
    method: 'POST',
  });
  return normalizeTramite(raw);
}

/** Doctor: el paciente no se presentó a su cita (CONFIRMADO → NO_PRESENTADO, terminal). */
export async function noPresentadoTramite(tramiteId: string): Promise<Tramite> {
  const raw = await fetchJson<Record<string, unknown>>(
    `/tramites/${encodeURIComponent(tramiteId)}/no-presentado`,
    { method: 'POST' },
  );
  return normalizeTramite(raw);
}

/** Doctor: el estudio no se pudo realizar (CONFIRMADO → NO_REALIZADO, terminal). 400 si el motivo está vacío. */
export async function noRealizadoTramite(tramiteId: string, motivo: string): Promise<Tramite> {
  const raw = await fetchJson<Record<string, unknown>>(
    `/tramites/${encodeURIComponent(tramiteId)}/no-realizado`,
    { method: 'POST', body: JSON.stringify({ motivo }) },
  );
  return normalizeTramite(raw);
}

/** Cita de clínica (DTO plano) para el tablero de recepción. */
export interface CitaClinica {
  folio: string;
  tramiteId: string;
  pacienteNombre: string;
  pacienteTelefono: string;
  estudioNombre: string;
  precio: number | null;
  clinicaNombre: string | null;
  clinicaCiudad: string | null;
  fechaCita: string | null;
  estado: string;
  /** ¿Ya se registró el check-in de este trámite? (del log de eventos). */
  checkIn?: boolean;
}

/**
 * Citas de Clínica desde GET /api/clinica/citas (DTOs planos, no entidades JPA).
 * estado: 'CONFIRMADO' por defecto; 'ATENDIDO', 'TODOS' o '' traen todos los estados.
 */
export async function getCitasClinica(estado: string = 'CONFIRMADO'): Promise<CitaClinica[]> {
  const query = estado === 'TODOS' || !estado ? '' : `?estado=${encodeURIComponent(estado)}`;
  const list = await fetchJson<CitaClinica[]>(`/clinica/citas${query}`);
  return list.map((c) => ({ ...c, precio: c.precio == null ? null : Number(c.precio) }));
}

/** Pacientes cuyo nombre contiene el texto (GET /api/pacientes/buscar?nombre=). */
export async function buscarPacientesPorNombre(nombre: string): Promise<Paciente[]> {
  try {
    return await fetchJson<Paciente[]>(`/pacientes/buscar?nombre=${encodeURIComponent(nombre.trim())}`);
  } catch (err) {
    // Error HTTP: lista vacia. Error de red: se propaga.
    if (isApiError(err)) return [];
    throw err;
  }
}

/** Todos los pacientes (GET /api/pacientes, para el asesor). Sin fallback mock. */
export async function getPacientes(): Promise<Paciente[]> {
  return fetchJson<Paciente[]>('/pacientes');
}

export async function getTramite(id: string): Promise<Tramite> {
  const q = id.trim();
  if (UUID_RE.test(q)) {
    const raw = await fetchJson<Record<string, unknown>>(`/tramites/${encodeURIComponent(q)}`);
    return normalizeTramite(raw);
  }
  if (esFolio(q)) return getTramitePorFolio(q);
  // Telefono: el tramite activo mas reciente de ese paciente.
  const lista = await getTramitesPorTelefono(q);
  const activo = lista.find((t) => t.estado !== 'ATENDIDO') ?? lista[0];
  if (!activo) throw new ApiError(404, `No hay tramites con el telefono ${q}`);
  return activo;
}

export async function getTimeline(id: string): Promise<Evento[]> {
  return fetchJson<Evento[]>(`/tramites/${encodeURIComponent(id)}/timeline`);
}

export async function getJourney(id: string): Promise<Journey> {
  const q = id.trim();
  let raw: Record<string, unknown>;
  try {
    if (UUID_RE.test(q)) {
      raw = await fetchJson<Record<string, unknown>>(`/journey/${encodeURIComponent(q)}`);
    } else if (esFolio(q)) {
      raw = await fetchJson<Record<string, unknown>>(`/journey/folio/${encodeURIComponent(q)}`);
    } else {
      // Telefono: resolver a UUID antes de llamar al journey.
      const tramite = await getTramite(q);
      raw = await fetchJson<Record<string, unknown>>(`/journey/${encodeURIComponent(tramite.id)}`);
    }
  } catch (err) {
    // Si el endpoint de journey falla (HTTP 4xx/5xx) pero el tramite
    // existe, se compone el journey con los dos endpoints que si
    // funcionan: el tramite por folio/UUID y su timeline. No es un
    // fallback mock: son datos reales de la BD. Un error de red (sin
    // respuesta del backend) se propaga tal cual.
    if (!isApiError(err)) throw err;
    const tramite = await getTramite(q);
    const timeline = await getTimeline(tramite.id);
    return { tramite, timeline };
  }
  const tramiteRaw = raw.tramite as Record<string, unknown> | undefined;
  return {
    tramite: tramiteRaw ? normalizeTramite(tramiteRaw) : await getTramite(q),
    timeline: (raw.timeline as Evento[]) ?? [],
  };
}

/** Marca la atencion como concluida: CONFIRMADO -> ATENDIDO en la BD. */
export async function atenderTramite(tramiteId: string): Promise<Tramite> {
  const raw = await fetchJson<Record<string, unknown>>(`/tramites/${encodeURIComponent(tramiteId)}/atender`, {
    method: 'POST',
  });
  return normalizeTramite(raw);
}

/** Completa un dato pendiente en la BD (POST /tramites/{id}/completar-dato). */
export async function completarDato(tramiteId: string, clave: string, valor: string): Promise<Tramite> {
  const raw = await fetchJson<Record<string, unknown>>(
    `/tramites/${encodeURIComponent(tramiteId)}/completar-dato`,
    { method: 'POST', body: JSON.stringify({ clave, valor }) },
  );
  return normalizeTramite(raw);
}

/** Nota del asesor: queda en el log de eventos (POST /canales/call-center/nota). */
export async function guardarNotaCallCenter(tramiteId: string, nota: string): Promise<void> {
  await fetchJson<Record<string, unknown>>('/canales/call-center/nota', {
    method: 'POST',
    body: JSON.stringify({ tramiteId, nota }),
  });
}

/** Check-in en clinica: registra un evento en la BD (POST /tramites/{id}/eventos). */
export async function registrarEventoCheckIn(tramiteId: string): Promise<Evento> {
  return fetchJson<Evento>(`/tramites/${encodeURIComponent(tramiteId)}/eventos`, {
    method: 'POST',
    body: JSON.stringify({
      tipo: 'canal.cambiado',
      canal: 'CLINICA',
      actor: 'CLINICA',
      payload: JSON.stringify({ accion: 'check-in' }),
    }),
  });
}

export interface CrearTramiteInput {
  nombre: string;
  telefono: string;
  /** Opcional: el formulario de cotizar ya no la pide. Sin sesión se
      crea el paciente solo con nombre y teléfono (la fecha es nullable
      en la BD); con sesión se usa la fecha de la sesión si la tiene. */
  fechaNacimiento?: string;
  estudioId: string;
  canal?: string;
}

// Sin fallback mock: si el backend no responde, el error se propaga y
// la página avisa que no se creó ningún folio (nada volátil).
export async function crearTramite(input: CrearTramiteInput): Promise<Tramite> {
  const telefono = normalizarTelefono(input.telefono);
  {
    // El backend crea trámites por pacienteId (UUID), no por nombre/teléfono.
    // 1) Busca al paciente solo por teléfono (es la clave única; la fecha
    // de nacimiento ya no se pide en cotizar y tampoco se usa para
    // verificar identidad aquí), 2) si no existe lo registra,
    // 3) crea el trámite con ese pacienteId y el estudioId numérico.
    // Timeout largo (15 s): el backend frío puede tardar y un aborto
    // aquí dejaba el folio creado sin que el front lo supiera.
    let paciente: Paciente;
    try {
      paciente = await fetchJson<Paciente>(
        `/pacientes?telefono=${encodeURIComponent(telefono)}`,
        undefined,
        ESCRITURA_TIMEOUT_MS,
      );
    } catch (err) {
      // Red caída u otro error HTTP: se propaga, no se intenta registrar.
      if (!isApiError(err) || err.status !== 404) throw err;
      // No existe (404): se registra con nombre y teléfono; la fecha
      // solo se manda si venía en el input (sesión con fecha).
      try {
        paciente = await fetchJson<Paciente>(
          '/pacientes',
          {
            method: 'POST',
            body: JSON.stringify({
              nombre: input.nombre,
              telefono,
              email: '',
              ...(input.fechaNacimiento ? { fechaNacimiento: input.fechaNacimiento } : {}),
            }),
          },
          ESCRITURA_TIMEOUT_MS,
        );
      } catch (err2) {
        if (isApiError(err2) && err2.status === 409) {
          paciente = await fetchJson<Paciente>(
            `/pacientes?telefono=${encodeURIComponent(telefono)}`,
            undefined,
            ESCRITURA_TIMEOUT_MS,
          );
        } else {
          throw err2;
        }
      }
    }
    const raw = await fetchJson<Record<string, unknown>>(
      '/tramites',
      {
        method: 'POST',
        body: JSON.stringify({
          pacienteId: paciente.id,
          estudioId: toNumberId(input.estudioId),
          canalOrigen: input.canal ?? 'WEB',
        }),
      },
      ESCRITURA_TIMEOUT_MS,
    );
    return normalizeTramite(raw);
  }
}

export async function cotizar(tramiteId: string, estudioId: string): Promise<Tramite> {
  const raw = await fetchJson<Record<string, unknown>>(
    `/tramites/${encodeURIComponent(tramiteId)}/cotizar`,
    {
      method: 'POST',
      body: JSON.stringify({ estudioId: toNumberId(estudioId) }),
    },
    ESCRITURA_TIMEOUT_MS,
  );
  return normalizeTramite(raw);
}

export async function seleccionarClinica(tramiteId: string, clinicaId: string): Promise<Tramite> {
  // Backend: POST /tramites/{id}/seleccionar-clinica (no /clinica)
  const raw = await fetchJson<Record<string, unknown>>(
    `/tramites/${encodeURIComponent(tramiteId)}/seleccionar-clinica`,
    {
      method: 'POST',
      body: JSON.stringify({ clinicaId: toNumberId(clinicaId) }),
    },
    ESCRITURA_TIMEOUT_MS,
  );
  return normalizeTramite(raw);
}

export async function confirmar(tramiteId: string, fechaCita: string): Promise<Tramite> {
  const raw = await fetchJson<Record<string, unknown>>(
    `/tramites/${encodeURIComponent(tramiteId)}/confirmar`,
    {
      method: 'POST',
      body: JSON.stringify({ fechaCita }),
    },
    ESCRITURA_TIMEOUT_MS,
  );
  return normalizeTramite(raw);
}

export async function enviarWhatsapp(
  telefono: string,
  mensaje: string,
  /** Folio en contexto: el trámite que el usuario venía siguiendo. El backend lo prefiere para no adivinar entre varios folios. */
  folio?: string,
): Promise<{
  respuesta: string;
  tramite: Tramite | null;
  tramites?: Tramite[];
  /** Respuestas sugeridas por el bot para el estado actual (botones). */
  opciones?: string[];
  /** Si el bot entendió la intención (false = respondió "No entendí"). */
  entendido?: boolean;
}> {
  const tel = normalizarTelefono(telefono);
  // Backend: POST /api/canales/whatsapp/mensaje (no /api/whatsapp/mensaje).
  // Devuelve { mensaje, respuesta, tramite, estado, siguientePaso, opciones, entendido } y,
  // si el paciente tiene varios folios activos y no dijo cual, { tramites }.
  // Sin fallback mock: si el backend no responde, el error se propaga y
  // la página avisa que el mensaje no se envió ni se guardó.
  const raw = await fetchJson<Record<string, unknown>>('/canales/whatsapp/mensaje', {
    method: 'POST',
    body: JSON.stringify({ telefono: tel, mensaje, ...(folio ? { folio } : {}) }),
  });
  const tramiteRaw = raw.tramite as Record<string, unknown> | null | undefined;
  const tramitesRaw = raw.tramites as Record<string, unknown>[] | undefined;
  return {
    respuesta: String(raw.mensaje ?? raw.respuesta ?? ''),
    tramite: tramiteRaw ? normalizeTramite(tramiteRaw) : null,
    tramites: tramitesRaw ? tramitesRaw.map(normalizeTramite) : undefined,
    opciones: Array.isArray(raw.opciones) ? (raw.opciones as string[]) : undefined,
    entendido: typeof raw.entendido === 'boolean' ? raw.entendido : undefined,
  };
}


// --- Administración de usuarios (solo por URL: /admin/usuarios) --------
// CRUD real contra Postgres (tabla pacientes). Vista de personal: el
// front solo la muestra con sesión de asesor o admin. Es una
// herramienta demo, solo se entra escribiendo la URL; no hay botón ni
// enlace hacia ella.
export interface UsuarioAdmin {
  id: string;
  nombre: string;
  telefono: string;
  email: string | null;
  fechaNacimiento: string | null;
  createdAt: string | null;
  cantidadFolios: number;
  tienePassword: boolean;
}

export interface UsuarioAdminInput {
  nombre: string;
  telefono: string;
  email?: string;
  fechaNacimiento?: string;
  /** Obligatoria al crear; al editar, solo se cambia si se envía una nueva. */
  password?: string;
}

/** Todos los usuarios con su cantidad de folios (GET /api/admin/usuarios). */
export async function getUsuariosAdmin(): Promise<UsuarioAdmin[]> {
  return fetchJson<UsuarioAdmin[]>('/admin/usuarios');
}

/** Crea un usuario en la BD (POST /api/admin/usuarios). 409 si el teléfono ya existe. */
export async function crearUsuarioAdmin(input: UsuarioAdminInput): Promise<UsuarioAdmin> {
  return fetchJson<UsuarioAdmin>('/admin/usuarios', {
    method: 'POST',
    body: JSON.stringify({ ...input, telefono: normalizarTelefono(input.telefono) }),
  });
}

/** Edita un usuario (PUT /api/admin/usuarios/{id}). 409 si el teléfono choca con otro usuario. */
export async function actualizarUsuarioAdmin(id: string, input: UsuarioAdminInput): Promise<UsuarioAdmin> {
  return fetchJson<UsuarioAdmin>(`/admin/usuarios/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify({ ...input, telefono: normalizarTelefono(input.telefono) }),
  });
}

/**
 * Borra un usuario y, en la misma transacción, sus folios (trámites),
 * sus citas y sus eventos. Devuelve cuántos folios se borraron.
 */
export async function eliminarUsuarioAdmin(id: string): Promise<{ foliosEliminados: number }> {
  return fetchJson<{ foliosEliminados: number }>(`/admin/usuarios/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}

// --- Personal: cuentas de acceso (solo por URL: /admin/usuarios) --------
// CRUD real contra Postgres (tabla usuarios_staff, V6). La contraseña
// NUNCA se muestra ni se devuelve por la API: no viene en el DTO y
// solo se envía al crear o al cambiarla.
export interface StaffAdmin {
  id: string;
  nombre: string;
  usuario: string;
  /** "ASESOR" o "ADMINISTRADOR". */
  rol: string;
  createdAt: string | null;
}

export interface StaffAdminInput {
  nombre: string;
  usuario: string;
  rol: string;
  /** Obligatoria al crear; al editar, solo se cambia si se envía una nueva. */
  password?: string;
}

/** Todas las cuentas del personal (GET /api/admin/staff). */
export async function getStaffAdmin(): Promise<StaffAdmin[]> {
  return fetchJson<StaffAdmin[]>('/admin/staff');
}

/** Crea una cuenta de personal (POST /api/admin/staff). 409 si el usuario ya existe. */
export async function crearStaffAdmin(input: StaffAdminInput): Promise<StaffAdmin> {
  return fetchJson<StaffAdmin>('/admin/staff', {
    method: 'POST',
    body: JSON.stringify({ ...input, usuario: input.usuario.trim().toLowerCase() }),
  });
}

/** Edita una cuenta de personal (PUT /api/admin/staff/{id}). 409 si el usuario choca con otra cuenta. */
export async function actualizarStaffAdmin(id: string, input: StaffAdminInput): Promise<StaffAdmin> {
  return fetchJson<StaffAdmin>(`/admin/staff/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify({ ...input, usuario: input.usuario.trim().toLowerCase() }),
  });
}

/** Borra una cuenta de personal. 409 si es el último admin o tu propia cuenta. */
export async function eliminarStaffAdmin(id: string): Promise<void> {
  await fetchJson<unknown>(`/admin/staff/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}
