export type EstadoTramite =
  | 'INICIADO'
  | 'DATOS_PENDIENTES'
  | 'COTIZADO'
  | 'CLINICA_SELECCIONADA'
  | 'CONFIRMADO'
  | 'ATENDIDO'
  | 'NO_PRESENTADO'
  | 'NO_REALIZADO'
  | 'CANCELADO';

export type Canal = 'WEB' | 'WHATSAPP' | 'CALL_CENTER' | 'APP' | 'CLINICA';

export interface Paciente {
  id: string;
  nombre: string;
  /** CURP (identificador principal). Los pacientes viejos pueden no tenerla. */
  curp?: string | null;
  /** El teléfono es opcional desde el registro por CURP: puede faltar. */
  telefono: string | null;
  fechaNacimiento: string;
}

/**
 * Siguiente paso para continuar un trámite donde se quedó, según su estado.
 * Se usa en "Retomar mi trámite", App e Historial.
 */
export function siguientePaso(t: Tramite): string {
  switch (t.estado) {
    case 'INICIADO':
      return 'Te quedaste en: cotizar el estudio';
    case 'DATOS_PENDIENTES':
      return `Te quedaste en: completar datos pendientes${t.datosPendientes.length > 0 ? ` (${t.datosPendientes.join(', ')})` : ''}`;
    case 'COTIZADO':
      return 'Te quedaste en: elegir clínica';
    case 'CLINICA_SELECCIONADA':
      return 'Te quedaste en: confirmar fecha y hora';
    case 'CONFIRMADO':
      return 'Te quedaste en: asistir a tu cita (llega 15 minutos antes con tu folio)';
    case 'ATENDIDO':
      return 'Trámite completado: ya te atendieron en clínica';
    case 'NO_PRESENTADO':
      return 'No te presentaste a la cita: contacta al Call Center para reagendar';
    case 'NO_REALIZADO':
      return 'El estudio no se pudo realizar: contacta al Call Center';
    case 'CANCELADO':
      return 'Trámite cancelado';
    default:
      return 'Continúa tu trámite';
  }
}

export interface Estudio {
  id: string;
  nombre: string;
  precio: number;
  descripcion?: string;
}

export interface Clinica {
  id: string;
  nombre: string;
  ciudad: string;
  direccion?: string;
}

export interface Tramite {
  id: string;
  folio: string;
  estado: EstadoTramite;
  paciente?: Paciente;
  estudio: Estudio;
  /** null cuando el trámite aún no tiene estudio (se normaliza al primer estudio del catálogo como placeholder). */
  estudioId?: string | null;
  clinica?: Clinica | null;
  precioCotizado: number;
  fechaCita?: string | null;
  canalOrigen: Canal;
  datosPendientes: string[];
}

export interface Evento {
  id?: string;
  tipo: string;
  canal: Canal;
  actor: string;
  timestamp: string;
  payload?: Record<string, unknown>;
  mensaje?: string;
}

export interface Journey {
  tramite: Tramite;
  timeline: Evento[];
}
