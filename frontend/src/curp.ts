/**
 * CURP — identificador principal del paciente.
 *
 * Mismo contrato que el backend: normalizar = trim + mayúsculas +
 * sin espacios; regex ^[A-Z]{4}\d{6}[HM][A-Z]{2}[A-Z]{3}[A-Z\d]\d$;
 * fecha real en los 6 dígitos (AAMMDD) y código de entidad válido.
 * Toda la app valida la CURP desde aquí: no duplicar la regex.
 */

export const CURP_RE = /^[A-Z]{4}\d{6}[HM][A-Z]{2}[A-Z]{3}[A-Z\d]\d$/;

export const CODIGOS_ENTIDAD = new Set([
  'AS', 'BC', 'BS', 'CC', 'CL', 'CM', 'CS', 'CH', 'DF', 'DG', 'GR', 'GT',
  'HG', 'JC', 'MC', 'MN', 'MS', 'NT', 'NL', 'OC', 'PL', 'QT', 'QR', 'SP',
  'SL', 'SR', 'TC', 'TL', 'TS', 'VZ', 'YN', 'ZS', 'NE',
]);

/** trim + mayúsculas + sin espacios (también quita guiones accidentales). */
export function normalizarCurp(curp: string | null | undefined): string {
  if (!curp) return '';
  return curp.trim().toUpperCase().replace(/[\s-]/g, '');
}

/** ¿Parece una CURP (18 caracteres alfanuméricos, 4 letras al inicio)? Sin validar a fondo. */
export function esCurp(texto: string | null | undefined): boolean {
  if (!texto) return false;
  const c = normalizarCurp(texto);
  return /^[A-Z]{4}[A-Z0-9]{14}$/.test(c);
}

/**
 * Busca una CURP dentro de un texto libre (mismo patrón que el backend:
 * CurpUtil.PATRON_EN_TEXTO) y la devuelve normalizada, o null si no hay.
 * Sirve para que el front reconozca la CURP cuando el usuario la escribe
 * como mensaje (no en el campo CURP) y la recuerde de ahí en adelante.
 */
export function buscarCurpEnTexto(texto: string | null | undefined): string | null {
  if (!texto) return null;
  const m = texto.toUpperCase().match(/[A-Z]{4}\d{6}[HM][A-Z]{2}[A-Z]{3}[A-Z\d]\d/);
  return m ? m[0] : null;
}

export interface ValidacionCurp {
  valida: boolean;
  motivo: string;
}

/**
 * Valida formato, fecha real (AAMMDD) y código de entidad.
 * Devuelve el motivo en claro para mostrarlo en el formulario.
 */
export function validarCurp(curp: string | null | undefined): ValidacionCurp {
  const c = normalizarCurp(curp);
  if (!c) return { valida: false, motivo: 'La CURP es obligatoria.' };
  if (c.length !== 18) {
    return {
      valida: false,
      motivo: `La CURP debe tener 18 caracteres, llevas ${c.length}.`,
    };
  }
  if (!/^[A-Z0-9]+$/.test(c)) {
    return { valida: false, motivo: 'La CURP solo lleva letras y números, sin espacios ni símbolos.' };
  }
  if (!CURP_RE.test(c)) {
    return {
      valida: false,
      motivo:
        'La CURP no tiene el formato correcto: 4 letras, 6 dígitos de fecha (AAMMDD), H o M, 2 letras de entidad, 3 letras y 2 caracteres finales.',
    };
  }
  // Fecha real en los dígitos 5-10 (AAMMDD).
  const fecha = fechaDesdeCurp(c);
  if (!fecha) {
    return { valida: false, motivo: 'La fecha de nacimiento dentro de la CURP no es una fecha real (revisa AAMMDD).' };
  }
  // Código de entidad en las posiciones 12-13.
  const entidad = c.substring(11, 13);
  if (!CODIGOS_ENTIDAD.has(entidad)) {
    return { valida: false, motivo: `El código de entidad "${entidad}" de la CURP no es válido.` };
  }
  return { valida: true, motivo: '' };
}

/**
 * Fecha de nacimiento desde la CURP (dígitos AAMMDD) en ISO AAAA-MM-DD,
 * o null si no es una fecha real. Siglo: años 00..año actual van al 2000,
 * el resto al 1900 (p. ej. 85 → 1985, 01 → 2001, 99 → 1999).
 */
export function fechaDesdeCurp(curp: string | null | undefined): string | null {
  const c = normalizarCurp(curp);
  if (c.length < 10) return null;
  const aa = parseInt(c.substring(4, 6), 10);
  const mm = parseInt(c.substring(6, 8), 10);
  const dd = parseInt(c.substring(8, 10), 10);
  if (Number.isNaN(aa) || Number.isNaN(mm) || Number.isNaN(dd)) return null;
  if (mm < 1 || mm > 12 || dd < 1) return null;
  const anioActual2 = new Date().getFullYear() % 100;
  const anio = aa <= anioActual2 ? 2000 + aa : 1900 + aa;
  const diasDelMes = new Date(anio, mm, 0).getDate();
  if (dd > diasDelMes) return null;
  const dd2 = String(dd).padStart(2, '0');
  const mm2 = String(mm).padStart(2, '0');
  return `${anio}-${mm2}-${dd2}`;
}
