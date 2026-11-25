import { useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { isApiError, registro } from '../api';
import PasswordInput from '../components/PasswordInput';
import { fechaDesdeCurp, normalizarCurp, validarCurp } from '../curp';
import { esFechaLatinaValida, formatearFecha, parsearFechaLatina } from '../fechas';

export default function RegistroPage() {
  const [nombre, setNombre] = useState('');
  const [curp, setCurp] = useState('');
  const [errorCurp, setErrorCurp] = useState('');
  const [telefono, setTelefono] = useState('');
  const [email, setEmail] = useState('');
  const [fechaNacimiento, setFechaNacimiento] = useState('');
  const [errorFecha, setErrorFecha] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [cargando, setCargando] = useState(false);
  const navigate = useNavigate();
  // Fecha autocompletada desde la última CURP válida: si el usuario
  // la cambia a mano, ya no se sobrescribe al teclear la CURP.
  const fechaAutocompletada = useRef('');

  function handleCurpChange(valor: string) {
    const normalizada = normalizarCurp(valor);
    setCurp(normalizada);
    setError('');
    if (!normalizada) {
      setErrorCurp('');
      return;
    }
    const v = validarCurp(normalizada);
    setErrorCurp(normalizada.length === 18 && !v.valida ? v.motivo : '');
    if (!v.valida) return;
    // CURP válida: autocompletar la fecha de nacimiento desde la CURP,
    // solo si está vacía o todavía tiene la fecha autocompletada antes.
    const iso = fechaDesdeCurp(normalizada);
    if (!iso) return;
    const latina = formatearFecha(iso);
    if (fechaNacimiento === '' || fechaNacimiento === fechaAutocompletada.current) {
      fechaAutocompletada.current = latina;
      setFechaNacimiento(latina);
      setErrorFecha('');
    } else {
      // La fecha escrita a mano no coincide con la CURP: avisar, no sobrescribir.
      const escritaIso = parsearFechaLatina(fechaNacimiento);
      setErrorFecha(
        escritaIso && escritaIso !== iso
          ? `La fecha de nacimiento (${fechaNacimiento}) no coincide con la fecha de tu CURP (${latina}). Corrige una de las dos.`
          : '',
      );
    }
  }

  function handleFechaChange(valor: string) {
    setFechaNacimiento(valor);
    setError('');
    const iso = fechaDesdeCurp(curp);
    if (iso && validarCurp(curp).valida) {
      const escritaIso = parsearFechaLatina(valor);
      setErrorFecha(
        escritaIso && escritaIso !== iso
          ? `La fecha de nacimiento (${valor}) no coincide con la fecha de tu CURP (${formatearFecha(iso)}). Corrige una de las dos.`
          : '',
      );
    } else {
      setErrorFecha('');
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    const vCurp = validarCurp(curp);
    if (!vCurp.valida) {
      setErrorCurp(vCurp.motivo);
      setError(vCurp.motivo);
      return;
    }
    if (password.length < 6) {
      setError('La contraseña debe tener al menos 6 caracteres.');
      return;
    }
    // La fecha se escribe en DD/MM/AAAA y solo se convierte a ISO al enviar a la API.
    const fechaIso = parsearFechaLatina(fechaNacimiento);
    if (!esFechaLatinaValida(fechaNacimiento) || !fechaIso) {
      setError('La fecha de nacimiento no es válida. Escríbela como DD/MM/AAAA, por ejemplo 14/05/1990.');
      return;
    }
    // La fecha debe coincidir con la de la CURP: si no, no se envía.
    const fechaCurp = fechaDesdeCurp(curp);
    if (fechaCurp && fechaIso !== fechaCurp) {
      const msg = `La fecha de nacimiento (${fechaNacimiento}) no coincide con la fecha de tu CURP (${formatearFecha(fechaCurp)}). Corrige una de las dos.`;
      setErrorFecha(msg);
      setError(msg);
      return;
    }
    setCargando(true);
    try {
      await registro({
        nombre,
        curp: normalizarCurp(curp),
        telefono: telefono.trim() ? telefono : null,
        email,
        fechaNacimiento: fechaIso,
        password,
      });
      navigate('/app');
    } catch (err) {
      setError(
        isApiError(err) && err.status === 409
          ? err.message || 'Esa CURP o ese teléfono ya está registrado. Inicia sesión o usa otros datos.'
          : isApiError(err)
            ? err.message
            : 'No se pudo conectar con el backend. Verifica que Docker esté arriba.',
      );
    } finally {
      setCargando(false);
    }
  }

  return (
    <div>
      <h1>Crear cuenta</h1>
      <p className="muted">Tu cuenta queda guardada en la base de datos de Salud Digna. Al registrarte entras directo a tu App. Tu CURP es tu identificador: con ella retomas tus trámites en cualquier canal.</p>

      <section className="card" style={{ maxWidth: 480 }}>
        <form onSubmit={handleSubmit} className="form">
          <label>
            Nombre completo
            <input value={nombre} onChange={(e) => setNombre(e.target.value)} placeholder="Ej. Ana García López" autoComplete="name" required />
          </label>
          <label>
            CURP (obligatoria, 18 caracteres)
            <input
              value={curp}
              onChange={(e) => handleCurpChange(e.target.value)}
              placeholder="Ej. GALA900514MDFRPN01"
              autoComplete="off"
              maxLength={18}
              style={{ textTransform: 'uppercase' }}
              required
            />
          </label>
          {errorCurp && (
            <p className="notice" role="alert">
              {errorCurp}
            </p>
          )}
          <label>
            Teléfono (WhatsApp) — opcional
            <input value={telefono} onChange={(e) => setTelefono(e.target.value)} placeholder="2220000001 (opcional)" autoComplete="tel" />
          </label>
          <label>
            Correo electrónico
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="tu@correo.com" autoComplete="email" required />
          </label>
          <label>
            Fecha de nacimiento
            <input
              type="text"
              value={fechaNacimiento}
              onChange={(e) => handleFechaChange(e.target.value)}
              placeholder="DD/MM/AAAA"
              inputMode="numeric"
              required
            />
          </label>
          <p className="muted" style={{ marginTop: -6 }}>
            Se autocompleta desde tu CURP; puedes cambiarla, pero debe coincidir con la fecha de la CURP.
          </p>
          {errorFecha && (
            <p className="notice" role="alert">
              {errorFecha}
            </p>
          )}
          <label>
            Contraseña (mínimo 6 caracteres)
            <PasswordInput
              value={password}
              onChange={setPassword}
              placeholder="Mínimo 6 caracteres"
              autoComplete="new-password"
              minLength={6}
              required
            />
          </label>
          {error && <p className="notice" role="alert">{error}</p>}
          <button type="submit" className="btn btn-primary" disabled={cargando}>
            {cargando ? 'Creando cuenta…' : 'Crear cuenta y entrar'}
          </button>
        </form>
        <p className="muted" style={{ marginTop: 12 }}>
          ¿No te sabes tu CURP?{' '}
          <a href="https://www.gob.mx/curp/" target="_blank" rel="noopener noreferrer">
            Consúltala aquí
          </a>{' '}
          (se abre en una pestaña nueva, sin cerrar este registro).
        </p>
        <p className="muted" style={{ marginTop: 12 }}>
          ¿Ya tienes cuenta? <Link to="/login">Inicia sesión aquí</Link>.
        </p>
        <p className="muted">
          <Link to="/">← Volver a inicio</Link>
        </p>
      </section>
    </div>
  );
}
