/**
 * Fechas en formato latinoamericano DD/MM/AAAA.
 *
 * Regla de toda la app: lo que el usuario ve, escribe y recibe (pantallas,
 * formularios, bot de WhatsApp) es DD/MM/AAAA. Por dentro, la API y la
 * base de datos siguen usando ISO (AAAA-MM-DD) y la conversión a ISO solo
 * existe aquí y en el envío a la API. Nunca se muestra una fecha en ISO
 * ni en formato MM/DD/AAAA.
 */

/** "2026-11-24" o "2026-11-24T10:00:00" -> "24/11/2026". Si no es ISO, devuelve el texto tal cual. */
export function formatearFecha(iso: string | null | undefined): string {
  if (!iso) return '';
  const fecha = iso.trim().split('T')[0].split(' ')[0];
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(fecha);
  if (!m) return iso;
  return `${m[3]}/${m[2]}/${m[1]}`;
}

/** "2026-11-24T10:00:00" -> "24/11/2026 10:00". Sin hora, solo la fecha. */
export function formatearFechaHora(iso: string | null | undefined): string {
  if (!iso) return '';
  const texto = iso.trim();
  const [fecha, hora] = texto.split('T');
  const fechaLatina = formatearFecha(fecha);
  if (!hora) {
    // Puede venir como "2026-11-24 10:00:00" (con espacio).
    const partes = texto.split(' ');
    if (partes.length > 1) return `${formatearFecha(partes[0])} ${partes[1].slice(0, 5)}`;
    return fechaLatina;
  }
  return `${fechaLatina} ${hora.slice(0, 5)}`;
}

/** Fecha de mañana en DD/MM/AAAA (para el botón rápido de WhatsApp: nunca una fecha fija). */
export function fechaMananaLatina(): string {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  const dd = String(d.getDate()).padStart(2, '0');
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  return `${dd}/${mm}/${d.getFullYear()}`;
}

/** ¿Es una fecha real en DD/MM/AAAA? Día 1-31, mes 1-12, año 1900-2100, 29/02 solo en bisiesto. */
export function esFechaLatinaValida(texto: string): boolean {
  return parsearFechaLatina(texto) !== null;
}

const FECHA_LATINA_RE = /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/;

/**
 * "24/11/2026" -> "2026-11-24" (ISO, para enviar a la API).
 * Acepta día y mes con uno o dos dígitos ("4/5/1990"). Devuelve null
 * si el formato no es DD/MM/AAAA o la fecha no existe (31/02, 32/01, 29/02/2025...).
 */
export function parsearFechaLatina(texto: string): string | null {
  const m = FECHA_LATINA_RE.exec(texto.trim());
  if (!m) return null;
  const dia = parseInt(m[1], 10);
  const mes = parseInt(m[2], 10);
  const anio = parseInt(m[3], 10);
  if (anio < 1900 || anio > 2100 || mes < 1 || mes > 12 || dia < 1) return null;
  const diasDelMes = new Date(anio, mes, 0).getDate();
  if (dia > diasDelMes) return null;
  const dd = String(dia).padStart(2, '0');
  const mm = String(mes).padStart(2, '0');
  return `${anio}-${mm}-${dd}`;
}
