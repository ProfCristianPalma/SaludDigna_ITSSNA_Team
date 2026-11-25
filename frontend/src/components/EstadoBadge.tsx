import type { EstadoTramite } from '../types';

const LABELS: Record<EstadoTramite, string> = {
  INICIADO: 'Iniciado',
  DATOS_PENDIENTES: 'Datos pendientes',
  COTIZADO: 'Cotizado',
  CLINICA_SELECCIONADA: 'Clínica seleccionada',
  CONFIRMADO: 'Confirmado',
  ATENDIDO: 'Atendido',
  NO_PRESENTADO: 'No se presentó',
  NO_REALIZADO: 'No realizado',
  CANCELADO: 'Cancelado',
};

const CLASSES: Record<EstadoTramite, string> = {
  INICIADO: 'badge badge-info',
  DATOS_PENDIENTES: 'badge badge-warning',
  COTIZADO: 'badge badge-info',
  CLINICA_SELECCIONADA: 'badge badge-primary',
  CONFIRMADO: 'badge badge-success',
  ATENDIDO: 'badge badge-success',
  NO_PRESENTADO: 'badge badge-warning',
  NO_REALIZADO: 'badge badge-danger',
  CANCELADO: 'badge badge-danger',
};

/**
 * Insignia legible del estado de un trámite: nunca el nombre técnico
 * del enum. Con clínica elegida se muestra el NOMBRE de la clínica
 * (o "Clínica seleccionada" si no se pasa ninguna).
 */
export default function EstadoBadge({ estado, clinica }: { estado: EstadoTramite; clinica?: string | null }) {
  const label = estado === 'CLINICA_SELECCIONADA' && clinica ? clinica : LABELS[estado];
  return <span className={CLASSES[estado]}>{label}</span>;
}
