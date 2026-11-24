import type { Evento } from '../types';
import { formatearFechaHora } from '../fechas';

// El timeline muestra día y hora en formato latinoamericano DD/MM/AAAA HH:MM
// (el 'short' de es-MX recorta el año a dos dígitos: 24/11/26).
function formatHora(timestamp: string): string {
  const formateada = formatearFechaHora(timestamp);
  return formateada || timestamp;
}

export default function Timeline({ eventos }: { eventos: Evento[] }) {
  if (eventos.length === 0) {
    return <p className="muted">Sin eventos todavía.</p>;
  }
  return (
    <ol className="timeline">
      {eventos.map((ev, i) => (
        <li key={ev.id ?? `${ev.tipo}-${i}`} className="timeline-item">
          <div className="timeline-dot" aria-hidden="true" />
          <div className="timeline-body">
            <div className="timeline-head">
              <strong>{ev.tipo}</strong>
              <span className="chip">{ev.canal}</span>
              <span className="muted">{formatHora(ev.timestamp)}</span>
            </div>
            <div className="muted">Actor: {ev.actor}</div>
            {ev.mensaje && <p>{ev.mensaje}</p>}
          </div>
        </li>
      ))}
    </ol>
  );
}
