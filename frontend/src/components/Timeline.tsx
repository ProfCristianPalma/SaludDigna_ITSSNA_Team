import type { Evento } from '../types';
import { formatearFechaHora } from '../fechas';

// El timeline muestra día y hora en formato latinoamericano DD/MM/AAAA HH:MM
// (el 'short' de es-MX recorta el año a dos dígitos: 24/11/26).
function formatHora(timestamp: string): string {
  const formateada = formatearFechaHora(timestamp);
  return formateada || timestamp;
}

/** Etiqueta legible del tipo de evento: nunca el nombre técnico crudo ("tramite.editado"). */
const ETIQUETAS: Record<string, string> = {
  'paciente.registrado': 'Paciente registrado',
  'tramite.iniciado': 'Trámite iniciado',
  'cotizacion.creada': 'Cotización creada',
  'clinica.seleccionada': 'Clínica seleccionada',
  'identidad.verificada': 'Identidad verificada',
  'canal.cambiado': 'Cambio de canal',
  'dato.pendiente.completado': 'Dato pendiente completado',
  'cita.confirmada': 'Cita confirmada',
  'atencion.concluida': 'Atención concluida',
  'tramite.recuperado': 'Trámite recuperado',
  'checkin.registrado': 'Check-in registrado',
  'cita.no_presentado': 'No se presentó',
  'cita.no_realizado': 'No se pudo realizar',
  'paciente.actualizado': 'Datos del paciente actualizados',
  'password.restablecida': 'Contraseña restablecida',
  'horas.ofrecidas': 'Horas ofrecidas',
  'mensaje.no_entendido': 'Mensaje no entendido',
  'tramite.editado': 'Trámite editado por el asesor',
  'tramite.cancelado': 'Trámite cancelado por el asesor',
  'tramite.eliminado': 'Trámite eliminado por el asesor',
};

/** El payload llega como string JSON (o ya como objeto): se parsea para sacar nota/motivo/cambios. */
function parsearPayload(payload: unknown): Record<string, unknown> | null {
  if (!payload) return null;
  if (typeof payload === 'object') return payload as Record<string, unknown>;
  if (typeof payload === 'string') {
    try {
      const parsed: unknown = JSON.parse(payload);
      return typeof parsed === 'object' && parsed !== null ? (parsed as Record<string, unknown>) : null;
    } catch {
      return null;
    }
  }
  return null;
}

/**
 * Detalle legible del evento: la nota de una edición, el motivo de una
 * cancelación / eliminación / no realizado, o el mensaje del bot.
 * Sin esto esos datos quedaban escondidos en el JSON del payload y el
 * timeline solo mostraba el tipo crudo. El backend también manda
 * `mensaje` en el Journey; aquí se deriva además del payload para que
 * el historial del asesor (GET /tramites/{id}/timeline) lo muestre igual.
 */
function detalleEvento(ev: Evento): string | null {
  if (ev.mensaje) return ev.mensaje;
  const p = parsearPayload(ev.payload);
  if (!p) return null;
  const partes: string[] = [];
  if (typeof p.nota === 'string' && p.nota) partes.push(`Nota: ${p.nota}`);
  if (typeof p.motivo === 'string' && p.motivo) partes.push(`Motivo: ${p.motivo}`);
  if (typeof p.cambios === 'string' && p.cambios) partes.push(`Cambios: ${p.cambios}`);
  if (typeof p.mensaje === 'string' && p.mensaje) partes.push(p.mensaje);
  return partes.length > 0 ? partes.join(' ') : null;
}

export default function Timeline({ eventos }: { eventos: Evento[] }) {
  if (eventos.length === 0) {
    return <p className="muted">Sin eventos todavía.</p>;
  }
  return (
    <ol className="timeline">
      {eventos.map((ev, i) => {
        const detalle = detalleEvento(ev);
        return (
          <li key={ev.id ?? `${ev.tipo}-${i}`} className="timeline-item">
            <div className="timeline-dot" aria-hidden="true" />
            <div className="timeline-body">
              <div className="timeline-head">
                <strong>{ETIQUETAS[ev.tipo] ?? ev.tipo}</strong>
                <span className="chip">{ev.canal}</span>
                <span className="muted">{formatHora(ev.timestamp)}</span>
              </div>
              <div className="muted">Actor: {ev.actor}</div>
              {detalle && <p>{detalle}</p>}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
