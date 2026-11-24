import { MOCK_CLINICAS, MOCK_ESTUDIOS, MOCK_PACIENTES, mockTimeline, mockTramite, mockTramiteParaTelefono } from './mock';
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
}

const TOKEN_KEY = 'saluddigna-token';

export function getToken(): string | null {
  return window.localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  window.localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
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

/** Login de paciente (teléfono o primer nombre + contraseña) o staff. */
export async function login(usuario: string, password: string): Promise<Sesion> {
  const sesion = normalizarSesion(
    await fetchJson<Sesion>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ usuario: usuario.trim(), password }),
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
      body: JSON.stringify(input),
    }),
  );
  setToken(sesion.token);
  return sesion;
}

/** Perfil de la sesión actual (GET /api/auth/yo). 401 si el token no vale. */
export async function getPerfilYo(): Promise<PerfilYo> {
  const raw = await fetchJson<PerfilYo>('/auth/yo');
  return { ...raw, paciente: raw.paciente ? normalizarPaciente(raw.paciente) : null };
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

async function fetchJson<T>(path: string, init?: RequestInit, timeoutMs = 4000): Promise<T> {
  const controller = new AbortController();
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

export function esTelefono(q: string): boolean {
  const digitos = q.replace(/\D/g, '');
  return digitos.length >= 7 && !UUID_RE.test(q.trim()) && !FOLIO_RE.test(q.trim());
}

/** Mock del tramite con ese folio (solo para cuando el backend no responde). */
function mockPorFolio(folio: string): Tramite | null {
  const n = parseInt(folio.slice(-4), 10);
  if (Number.isNaN(n) || n < 1 || n > MOCK_PACIENTES.length) return null;
  return mockTramiteParaTelefono(MOCK_PACIENTES[n - 1].telefono);
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

function normalizarTexto(s: string): string {
  return s
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .trim();
}

// --- Mock de WhatsApp con estado ---------------------------------------
// Antes el mock devolvía siempre el mismo mensaje, sin importar lo que
// escribiera el usuario: ese era el ciclo. Ahora guarda el trámite en
// memoria y avanza de estado igual que el backend.
let mockWaTramite: Tramite | null = null;

function getMockWaTramite(telefono?: string): Tramite {
  if (telefono) {
    const paraTelefono = mockTramiteParaTelefono(telefono);
    if (paraTelefono && (!mockWaTramite || mockWaTramite.paciente?.telefono !== telefono)) {
      mockWaTramite = { ...paraTelefono, estudio: { ...paraTelefono.estudio }, datosPendientes: [...paraTelefono.datosPendientes] };
    }
  }
  if (!mockWaTramite) {
    const t = mockTramite();
    mockWaTramite = { ...t, estudio: { ...t.estudio }, clinica: null, datosPendientes: [...t.datosPendientes] };
  }
  return mockWaTramite;
}

function mockWhatsappRespuesta(mensaje: string, telefono?: string): { respuesta: string; tramite: Tramite } {
  const t = getMockWaTramite(telefono);
  const nombre = t.paciente?.nombre.split(' ')[0] ?? 'Hola';
  const norm = normalizarTexto(mensaje);

  if (t.estado === 'COTIZADO') {
    if (norm === '1' || norm.includes('puebla') || norm.includes('centro')) {
      t.clinica = MOCK_CLINICAS[0];
      t.estado = 'CLINICA_SELECCIONADA';
      return {
        tramite: t,
        respuesta: `Listo ${nombre}, elegiste ${t.clinica.nombre} para tu trámite ${t.folio}. Ahora dime qué día prefieres para tu cita, en formato AAAA-MM-DD (por ejemplo 2026-11-24).`,
      };
    }
    if (norm === '2' || norm.includes('tehuacan')) {
      t.clinica = MOCK_CLINICAS[1];
      t.estado = 'CLINICA_SELECCIONADA';
      return {
        tramite: t,
        respuesta: `Listo ${nombre}, elegiste ${t.clinica.nombre} para tu trámite ${t.folio}. Ahora dime qué día prefieres para tu cita, en formato AAAA-MM-DD (por ejemplo 2026-11-24).`,
      };
    }
    if (norm.includes('si') || norm.includes('quiero') || norm.includes('elegir') || norm.includes('clinica') || norm.includes('continuar')) {
      return {
        tramite: t,
        respuesta: `Perfecto ${nombre}, continuamos tu trámite ${t.folio}. Elige una clínica respondiendo con el número:\n1) Clínica Puebla Centro (Puebla)\n2) Clínica Salud Digna Tehuacán (Tehuacán)\nResponde 1 o 2.`,
      };
    }
    return {
      tramite: t,
      respuesta: `Hola ${nombre} 👋. Encontré tu trámite pendiente ${t.folio}: ${t.estudio.nombre} por $${t.precioCotizado} (estado: ${t.estado}). ¿Quieres continuar y elegir clínica?`,
    };
  }

  if (t.estado === 'CLINICA_SELECCIONADA') {
    const m = mensaje.match(/(\d{4})-(\d{2})-(\d{2})/);
    if (m) {
      t.fechaCita = `${m[1]}-${m[2]}-${m[3]}T10:00:00`;
      t.estado = 'CONFIRMADO';
      return {
        tramite: t,
        respuesta: `Confirmado ${nombre}. Tu cita del trámite ${t.folio} quedó para el ${m[1]}-${m[2]}-${m[3]} a las 10:00 en ${t.clinica?.nombre ?? ''}. Te esperamos.`,
      };
    }
    return {
      tramite: t,
      respuesta: `Ya tienes clínica (${t.clinica?.nombre ?? ''}) en tu trámite ${t.folio}. Dime qué día prefieres, en formato AAAA-MM-DD (por ejemplo 2026-11-24).`,
    };
  }

  if (t.estado === 'CONFIRMADO') {
    return { tramite: t, respuesta: `Hola ${nombre}, tu cita del trámite ${t.folio} está confirmada para el ${t.fechaCita}. Te esperamos.` };
  }

  return {
    tramite: t,
    respuesta: `Hola ${nombre} 👋. Tu trámite ${t.folio} está en estado ${t.estado}.`,
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

/** Trámite por folio legible (GET /api/tramites/folio/{folio}). */
export async function getTramitePorFolio(folio: string): Promise<Tramite> {
  try {
    const raw = await fetchJson<Record<string, unknown>>(`/tramites/folio/${encodeURIComponent(folio.trim())}`);
    return normalizeTramite(raw);
  } catch (err) {
    if (isApiError(err)) throw err; // 404 real de la BD: no hay que inventar a Ana.
    markMock();
    const t = mockPorFolio(folio);
    if (!t) throw err;
    return t;
  }
}

/** TODOS los trámites (folios) de un paciente por teléfono, del más reciente al más viejo. */
export async function getTramitesPorTelefono(telefono: string): Promise<Tramite[]> {
  try {
    const list = await fetchJson<Record<string, unknown>[]>(
      `/tramites?telefono=${encodeURIComponent(telefono.trim())}`,
    );
    return list.map(normalizeTramite);
  } catch (err) {
    if (isApiError(err)) return []; // Paciente no existe en la BD: lista vacia, no mock.
    markMock();
    const t = mockTramiteParaTelefono(telefono);
    return t ? [t] : [];
  }
}

/** Trámites en un estado dado (ej. CONFIRMADO, para la recepción de clínica). */
export async function getTramitesPorEstado(estado: string): Promise<Tramite[]> {
  try {
    const list = await fetchJson<Record<string, unknown>[]>(
      `/tramites?estado=${encodeURIComponent(estado)}`,
    );
    return list.map(normalizeTramite);
  } catch (err) {
    if (isApiError(err)) throw err;
    markMock();
    // Sin backend: los 5 usuarios ejemplo en ese estado, como en la BD.
    return MOCK_PACIENTES.map((p) => mockTramiteParaTelefono(p.telefono)).filter(
      (t): t is Tramite => t !== null && t.estado === estado,
    );
  }
}

/** Paciente por teléfono (GET /api/pacientes?telefono=). */
export async function getPacientePorTelefono(telefono: string): Promise<Paciente> {
  try {
    return await fetchJson<Paciente>(`/pacientes?telefono=${encodeURIComponent(telefono.trim())}`);
  } catch (err) {
    if (isApiError(err)) throw err;
    markMock();
    const t = mockTramiteParaTelefono(telefono);
    if (!t?.paciente) throw err;
    return t.paciente;
  }
}

/** Pacientes cuyo nombre contiene el texto (GET /api/pacientes/buscar?nombre=). */
export async function buscarPacientesPorNombre(nombre: string): Promise<Paciente[]> {
  try {
    return await fetchJson<Paciente[]>(`/pacientes/buscar?nombre=${encodeURIComponent(nombre.trim())}`);
  } catch (err) {
    if (isApiError(err)) return [];
    markMock();
    const norm = normalizarTexto(nombre);
    return MOCK_PACIENTES.filter((p) => normalizarTexto(p.nombre).includes(norm));
  }
}

/** Todos los pacientes (GET /api/pacientes, para el asesor). */
export async function getPacientes(): Promise<Paciente[]> {
  try {
    return await fetchJson<Paciente[]>('/pacientes');
  } catch (err) {
    if (isApiError(err)) throw err;
    markMock();
    return MOCK_PACIENTES;
  }
}

export async function getTramite(id: string): Promise<Tramite> {
  const q = id.trim();
  if (UUID_RE.test(q)) {
    try {
      const raw = await fetchJson<Record<string, unknown>>(`/tramites/${encodeURIComponent(q)}`);
      return normalizeTramite(raw);
    } catch (err) {
      if (isApiError(err)) throw err;
      markMock();
      return mockTramite();
    }
  }
  if (esFolio(q)) return getTramitePorFolio(q);
  // Telefono: el tramite activo mas reciente de ese paciente.
  const lista = await getTramitesPorTelefono(q);
  const activo = lista.find((t) => t.estado !== 'ATENDIDO') ?? lista[0];
  if (!activo) throw new ApiError(404, `No hay tramites con el telefono ${q}`);
  return activo;
}

export async function getTimeline(id: string): Promise<Evento[]> {
  try {
    return await fetchJson<Evento[]>(`/tramites/${encodeURIComponent(id)}/timeline`);
  } catch (err) {
    if (isApiError(err)) throw err;
    markMock();
    return mockTimeline();
  }
}

export async function getJourney(id: string): Promise<Journey> {
  const q = id.trim();
  try {
    let raw: Record<string, unknown>;
    if (UUID_RE.test(q)) {
      raw = await fetchJson<Record<string, unknown>>(`/journey/${encodeURIComponent(q)}`);
    } else if (esFolio(q)) {
      raw = await fetchJson<Record<string, unknown>>(`/journey/folio/${encodeURIComponent(q)}`);
    } else {
      // Telefono: resolver a UUID antes de llamar al journey.
      const tramite = await getTramite(q);
      raw = await fetchJson<Record<string, unknown>>(`/journey/${encodeURIComponent(tramite.id)}`);
    }
    const tramiteRaw = raw.tramite as Record<string, unknown> | undefined;
    return {
      tramite: tramiteRaw ? normalizeTramite(tramiteRaw) : await getTramite(q),
      timeline: (raw.timeline as Evento[]) ?? mockTimeline(),
    };
  } catch (err) {
    if (isApiError(err)) throw err; // 404 real: "no encontrado", nunca a Ana.
    markMock();
    return { tramite: await getTramite(q), timeline: mockTimeline() };
  }
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
  fechaNacimiento: string;
  estudioId: string;
  canal?: string;
}

export async function crearTramite(input: CrearTramiteInput): Promise<Tramite> {
  try {
    // El backend crea trámites por pacienteId (UUID), no por nombre/teléfono.
    // 1) Busca al paciente por teléfono, 2) si no existe lo registra,
    // 3) crea el trámite con ese pacienteId y el estudioId numérico.
    let paciente: Paciente;
    try {
      paciente = await fetchJson<Paciente>(
        `/pacientes?telefono=${encodeURIComponent(input.telefono)}&fechaNacimiento=${encodeURIComponent(input.fechaNacimiento)}`,
      );
    } catch (err) {
      // Si el telefono ya existe pero la fecha no coincidio, el POST daria 409:
      // entonces se resuelve solo por telefono y se usa ese paciente.
      try {
        paciente = await fetchJson<Paciente>('/pacientes', {
          method: 'POST',
          body: JSON.stringify({
            nombre: input.nombre,
            telefono: input.telefono,
            email: '',
            fechaNacimiento: input.fechaNacimiento,
          }),
        });
      } catch (err2) {
        if (isApiError(err2) && err2.status === 409) {
          paciente = await fetchJson<Paciente>(
            `/pacientes?telefono=${encodeURIComponent(input.telefono)}`,
          );
        } else {
          throw err2;
        }
      }
    }
    const raw = await fetchJson<Record<string, unknown>>('/tramites', {
      method: 'POST',
      body: JSON.stringify({
        pacienteId: paciente.id,
        estudioId: toNumberId(input.estudioId),
        canalOrigen: input.canal ?? 'WEB',
      }),
    });
    return normalizeTramite(raw);
  } catch {
    markMock();
    const estudio = MOCK_ESTUDIOS.find((e) => e.id === input.estudioId) ?? MOCK_ESTUDIOS[0];
    const t = mockTramite();
    t.paciente = { id: 'pac-ana-001', nombre: input.nombre, telefono: input.telefono, fechaNacimiento: input.fechaNacimiento };
    t.estudio = estudio;
    t.precioCotizado = estudio.precio;
    t.estado = 'INICIADO';
    t.canalOrigen = 'WEB';
    return t;
  }
}

export async function cotizar(tramiteId: string, estudioId: string): Promise<Tramite> {
  try {
    const raw = await fetchJson<Record<string, unknown>>(`/tramites/${encodeURIComponent(tramiteId)}/cotizar`, {
      method: 'POST',
      body: JSON.stringify({ estudioId: toNumberId(estudioId) }),
    });
    return normalizeTramite(raw);
  } catch {
    markMock();
    const estudio = MOCK_ESTUDIOS.find((e) => e.id === estudioId) ?? MOCK_ESTUDIOS[0];
    const t = mockTramite();
    t.estudio = estudio;
    t.precioCotizado = estudio.precio;
    t.estado = 'COTIZADO';
    return t;
  }
}

export async function seleccionarClinica(tramiteId: string, clinicaId: string): Promise<Tramite> {
  try {
    // Backend: POST /tramites/{id}/seleccionar-clinica (no /clinica)
    const raw = await fetchJson<Record<string, unknown>>(
      `/tramites/${encodeURIComponent(tramiteId)}/seleccionar-clinica`,
      {
        method: 'POST',
        body: JSON.stringify({ clinicaId: toNumberId(clinicaId) }),
      },
    );
    return normalizeTramite(raw);
  } catch {
    markMock();
    const clinica = MOCK_CLINICAS.find((c) => c.id === clinicaId) ?? MOCK_CLINICAS[0];
    const t = mockTramite();
    t.clinica = clinica;
    t.estado = 'CLINICA_SELECCIONADA';
    return t;
  }
}

export async function confirmar(tramiteId: string, fechaCita: string): Promise<Tramite> {
  try {
    const raw = await fetchJson<Record<string, unknown>>(`/tramites/${encodeURIComponent(tramiteId)}/confirmar`, {
      method: 'POST',
      body: JSON.stringify({ fechaCita }),
    });
    return normalizeTramite(raw);
  } catch {
    markMock();
    const t = mockTramite();
    t.clinica = MOCK_CLINICAS[0];
    t.fechaCita = fechaCita;
    t.estado = 'CONFIRMADO';
    t.datosPendientes = [];
    return t;
  }
}

export async function enviarWhatsapp(
  telefono: string,
  mensaje: string,
): Promise<{ respuesta: string; tramite: Tramite | null; tramites?: Tramite[] }> {
  try {
    // Backend: POST /api/canales/whatsapp/mensaje (no /api/whatsapp/mensaje).
    // Devuelve { mensaje, respuesta, tramite, estado, siguientePaso } y,
    // si el paciente tiene varios folios activos y no dijo cual, { tramites }.
    const raw = await fetchJson<Record<string, unknown>>('/canales/whatsapp/mensaje', {
      method: 'POST',
      body: JSON.stringify({ telefono, mensaje }),
    });
    const tramiteRaw = raw.tramite as Record<string, unknown> | null | undefined;
    const tramitesRaw = raw.tramites as Record<string, unknown>[] | undefined;
    return {
      respuesta: String(raw.mensaje ?? raw.respuesta ?? ''),
      tramite: tramiteRaw ? normalizeTramite(tramiteRaw) : null,
      tramites: tramitesRaw ? tramitesRaw.map(normalizeTramite) : undefined,
    };
  } catch {
    markMock();
    const tramiteMock = mockTramiteParaTelefono(telefono);
    if (!tramiteMock) {
      return {
        tramite: null,
        respuesta: 'Hola. No encontré un trámite con ese teléfono. ¿Me compartes tu folio (por ejemplo SD-2026-0001) o el estudio que necesitas?',
      };
    }
    return mockWhatsappRespuesta(mensaje, telefono);
  }
}

