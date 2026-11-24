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

export async function getEstudios(): Promise<Estudio[]> {
  try {
    return await fetchJson<Estudio[]>('/estudios');
  } catch {
    markMock();
    return MOCK_ESTUDIOS;
  }
}

export async function getClinicas(): Promise<Clinica[]> {
  try {
    return await fetchJson<Clinica[]>('/clinicas');
  } catch {
    markMock();
    return MOCK_CLINICAS;
  }
}

export async function getTramite(id: string): Promise<Tramite> {
  try {
    return await fetchJson<Tramite>(`/tramites/${encodeURIComponent(id)}`);
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
    return await fetchJson<Journey>(`/tramites/${encodeURIComponent(id)}/journey`);
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
    return await fetchJson<Tramite>('/tramites', {
      method: 'POST',
      body: JSON.stringify(input),
    });
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
    return await fetchJson<Tramite>(`/tramites/${encodeURIComponent(tramiteId)}/cotizar`, {
      method: 'POST',
      body: JSON.stringify({ estudioId }),
    });
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
    return await fetchJson<Tramite>(`/tramites/${encodeURIComponent(tramiteId)}/clinica`, {
      method: 'POST',
      body: JSON.stringify({ clinicaId }),
    });
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
    return await fetchJson<Tramite>(`/tramites/${encodeURIComponent(tramiteId)}/confirmar`, {
      method: 'POST',
      body: JSON.stringify({ fechaCita }),
    });
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
    return await fetchJson<{ respuesta: string; tramite: Tramite | null }>('/whatsapp/mensaje', {
      method: 'POST',
      body: JSON.stringify({ telefono, mensaje }),
    });
  } catch {
    markMock();
    const tramite = telefono.replace(/\D/g, '') === '522220000001' || telefono.includes('222 000 0001') ? mockTramite() : null;
    const respuesta = tramite
      ? `Hola Ana 👋. Encontré tu trámite pendiente ${tramite.folio}: ${tramite.estudio.nombre} por $${tramite.precioCotizado} (estado: ${tramite.estado}). ¿Quieres continuar y elegir clínica?`
      : 'Hola. No encontré un trámite con ese teléfono. ¿Me compartes tu folio (por ejemplo SD-2026-0001) o el estudio que necesitas?';
    return { respuesta, tramite };
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
