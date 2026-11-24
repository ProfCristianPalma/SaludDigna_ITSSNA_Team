import { MOCK_CLINICAS, MOCK_ESTUDIOS, MOCK_PACIENTE_ANA, mockTimeline, mockTramite } from './mock';
import type { Clinica, Estudio, Evento, Journey, Paciente, Tramite } from './types';

export const API_BASE = 'http://localhost:8080/api';

// true cuando la última llamada usó el fallback mock (backend no disponible).
export let isMock = false;

export function isMockMode(): boolean {
  return isMock;
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
    const res = await fetch(`${API_BASE}${path}`, {
      ...init,
      headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
      signal: controller.signal,
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    markLive();
    return (await res.json()) as T;
  } finally {
    window.clearTimeout(timer);
  }
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

function getMockWaTramite(): Tramite {
  if (!mockWaTramite) {
    const t = mockTramite();
    mockWaTramite = { ...t, estudio: { ...t.estudio }, clinica: null, datosPendientes: [...t.datosPendientes] };
  }
  return mockWaTramite;
}

function mockWhatsappRespuesta(mensaje: string): { respuesta: string; tramite: Tramite } {
  const t = getMockWaTramite();
  const norm = normalizarTexto(mensaje);

  if (t.estado === 'COTIZADO') {
    if (norm === '1' || norm.includes('puebla') || norm.includes('centro')) {
      t.clinica = MOCK_CLINICAS[0];
      t.estado = 'CLINICA_SELECCIONADA';
      return {
        tramite: t,
        respuesta: `Listo Ana, elegiste ${t.clinica.nombre} para tu trámite ${t.folio}. Ahora dime qué día prefieres para tu cita, en formato AAAA-MM-DD (por ejemplo 2026-11-24).`,
      };
    }
    if (norm === '2' || norm.includes('tehuacan')) {
      t.clinica = MOCK_CLINICAS[1];
      t.estado = 'CLINICA_SELECCIONADA';
      return {
        tramite: t,
        respuesta: `Listo Ana, elegiste ${t.clinica.nombre} para tu trámite ${t.folio}. Ahora dime qué día prefieres para tu cita, en formato AAAA-MM-DD (por ejemplo 2026-11-24).`,
      };
    }
    if (norm.includes('si') || norm.includes('quiero') || norm.includes('elegir') || norm.includes('clinica') || norm.includes('continuar')) {
      return {
        tramite: t,
        respuesta: `Perfecto Ana, continuamos tu trámite ${t.folio}. Elige una clínica respondiendo con el número:\n1) Clínica Puebla Centro (Puebla)\n2) Clínica Salud Digna Tehuacán (Tehuacán)\nResponde 1 o 2.`,
      };
    }
    return {
      tramite: t,
      respuesta: `Hola Ana 👋. Encontré tu trámite pendiente ${t.folio}: ${t.estudio.nombre} por $${t.precioCotizado} (estado: ${t.estado}). ¿Quieres continuar y elegir clínica?`,
    };
  }

  if (t.estado === 'CLINICA_SELECCIONADA') {
    const m = mensaje.match(/(\d{4})-(\d{2})-(\d{2})/);
    if (m) {
      t.fechaCita = `${m[1]}-${m[2]}-${m[3]}T10:00:00`;
      t.estado = 'CONFIRMADO';
      return {
        tramite: t,
        respuesta: `Confirmado Ana. Tu cita del trámite ${t.folio} quedó para el ${m[1]}-${m[2]}-${m[3]} a las 10:00 en ${t.clinica?.nombre ?? ''}. Te esperamos.`,
      };
    }
    return {
      tramite: t,
      respuesta: `Ya tienes clínica (${t.clinica?.nombre ?? ''}) en tu trámite ${t.folio}. Dime qué día prefieres, en formato AAAA-MM-DD (por ejemplo 2026-11-24).`,
    };
  }

  if (t.estado === 'CONFIRMADO') {
    return { tramite: t, respuesta: `Hola Ana, tu cita del trámite ${t.folio} está confirmada para el ${t.fechaCita}. Te esperamos.` };
  }

  return {
    tramite: t,
    respuesta: `Hola Ana 👋. Tu trámite ${t.folio} está en estado ${t.estado}.`,
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

export async function getTramite(id: string): Promise<Tramite> {
  try {
    const raw = await fetchJson<Record<string, unknown>>(`/tramites/${encodeURIComponent(id)}`);
    return normalizeTramite(raw);
  } catch {
    markMock();
    const t = mockTramite();
    if (id && id.startsWith('SD-') && id !== t.folio) t.folio = id;
    return t;
  }
}

export async function getTimeline(id: string): Promise<Evento[]> {
  try {
    return await fetchJson<Evento[]>(`/tramites/${encodeURIComponent(id)}/timeline`);
  } catch {
    markMock();
    return mockTimeline();
  }
}

export async function getJourney(id: string): Promise<Journey> {
  try {
    // Backend: GET /api/journey/{tramiteId} (no /tramites/.../journey)
    const raw = await fetchJson<Record<string, unknown>>(`/journey/${encodeURIComponent(id)}`);
    const tramiteRaw = raw.tramite as Record<string, unknown> | undefined;
    return {
      tramite: tramiteRaw ? normalizeTramite(tramiteRaw) : await getTramite(id),
      timeline: (raw.timeline as Evento[]) ?? mockTimeline(),
    };
  } catch {
    markMock();
    return { tramite: await getTramite(id), timeline: mockTimeline() };
  }
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
    } catch {
      paciente = await fetchJson<Paciente>('/pacientes', {
        method: 'POST',
        body: JSON.stringify({
          nombre: input.nombre,
          telefono: input.telefono,
          email: '',
          fechaNacimiento: input.fechaNacimiento,
        }),
      });
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
): Promise<{ respuesta: string; tramite: Tramite | null }> {
  try {
    // Backend: POST /api/canales/whatsapp/mensaje (no /api/whatsapp/mensaje).
    // Devuelve { mensaje, respuesta, tramite, estado, siguientePaso }.
    const raw = await fetchJson<Record<string, unknown>>('/canales/whatsapp/mensaje', {
      method: 'POST',
      body: JSON.stringify({ telefono, mensaje }),
    });
    const tramiteRaw = raw.tramite as Record<string, unknown> | null | undefined;
    return {
      respuesta: String(raw.mensaje ?? raw.respuesta ?? ''),
      tramite: tramiteRaw ? normalizeTramite(tramiteRaw) : null,
    };
  } catch {
    markMock();
    const limpio = telefono.replace(/\D/g, '');
    const esAna = limpio === '522220000001' || limpio === '2220000001' || telefono.includes('222 000 0001');
    if (!esAna) {
      return {
        tramite: null,
        respuesta: 'Hola. No encontré un trámite con ese teléfono. ¿Me compartes tu folio (por ejemplo SD-2026-0001) o el estudio que necesitas?',
      };
    }
    return mockWhatsappRespuesta(mensaje);
  }
}

export async function loginDemo(): Promise<Paciente> {
  try {
    return await fetchJson<Paciente>('/auth/demo', { method: 'POST', body: JSON.stringify({}) });
  } catch {
    markMock();
    return MOCK_PACIENTE_ANA;
  }
}
