import type { EstadoTramite } from '../types';

const LABELS: Record<EstadoTramite, string> = {
  INICIADO: 'Iniciado',
  DATOS_PENDIENTES: 'Datos pendientes',
  COTIZADO: 'Cotizado',
  CLINICA_SELECCIONADA: 'Clínica seleccionada',
  CONFIRMADO: 'Confirmado',
  ATENDIDO: 'Atendido',
  CANCELADO: 'Cancelado',
};

const CLASSES: Record<EstadoTramite, string> = {
  INICIADO: 'badge badge-info',
  DATOS_PENDIENTES: 'badge badge-warning',
  COTIZADO: 'badge badge-info',
  CLINICA_SELECCIONADA: 'badge badge-primary',
  CONFIRMADO: 'badge badge-success',
  ATENDIDO: 'badge badge-success',
  CANCELADO: 'badge badge-danger',
};

export default function EstadoBadge({ estado }: { estado: EstadoTramite }) {
  return <span className={CLASSES[estado]}>{LABELS[estado]}</span>;
}
