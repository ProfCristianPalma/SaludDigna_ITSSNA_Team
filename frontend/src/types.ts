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
  telefono: string;
  fechaNacimiento: string;
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
