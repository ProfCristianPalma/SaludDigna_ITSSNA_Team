import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { isApiError, login } from '../api';
import PasswordInput from '../components/PasswordInput';

export default function LoginPage() {
  const [usuario, setUsuario] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [cargando, setCargando] = useState(false);
  const navigate = useNavigate();

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    setCargando(true);
    try {
      const sesion = await login(usuario, password);
      // Cada quien a su lugar: el personal a su consola, el paciente a su App.
      if (sesion.rol === 'DOCTOR') {
        navigate('/doctor');
      } else if (sesion.rol === 'ASESOR' || sesion.rol === 'ADMINISTRADOR') {
        navigate('/asesor');
      } else {
        navigate('/app');
      }
    } catch (err) {
      setError(
        isApiError(err) && err.status === 401
          ? 'Usuario o contraseña incorrectos. Revisa tus datos y tu contraseña.'
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
      <h1>Iniciar sesión</h1>
      <p className="muted">Entra con tu CURP y tu contraseña. La sesión dura lo que dura esta pestaña: al cerrarla se cierra sola y la app arranca limpia, sin usuario cargado.</p>

      <section className="card" style={{ maxWidth: 480 }}>
        <form onSubmit={handleSubmit} className="form">
          <label>
            CURP
            <input
              value={usuario}
              onChange={(e) => setUsuario(e.target.value.toUpperCase().replace(/\s/g, ''))}
              placeholder="Escribe tu CURP"
              autoComplete="username"
              maxLength={18}
              style={{ textTransform: 'uppercase' }}
              required
            />
          </label>
          <label>
            Contraseña
            <PasswordInput
              value={password}
              onChange={setPassword}
              placeholder="Tu contraseña"
              autoComplete="current-password"
              required
            />
          </label>
          {error && <p className="notice" role="alert">{error}</p>}
          <button type="submit" className="btn btn-primary" disabled={cargando}>
            {cargando ? 'Entrando…' : 'Entrar'}
          </button>
        </form>
        <p className="muted" style={{ marginTop: 12 }}>
          ¿No te sabes tu CURP?{' '}
          <a href="https://www.gob.mx/curp/" target="_blank" rel="noopener noreferrer">
            Consúltala aquí
          </a>{' '}
          (se abre en una pestaña nueva, sin cerrar este inicio de sesión).
        </p>
        <p className="muted">
          ¿No tienes cuenta? <Link to="/registro">Regístrate aquí</Link>.
        </p>
        <p className="muted">
          <Link to="/">← Volver a inicio</Link>
        </p>
      </section>

      <section className="card" style={{ maxWidth: 480, marginTop: 16 }}>
        <h2>Cuentas de prueba</h2>
        <p className="muted">El login de pacientes es solo con CURP. El personal entra con su usuario.</p>
        <ul>
          <li>Ana García López — CURP <code>GALA900514MDFRPN00</code> — contraseña <code>ana123</code></li>
          <li>Luis Hernández Torres — CURP <code>HETL850322HDFRRS01</code> — <code>luis123</code></li>
          <li>María Fernanda Ruiz — CURP <code>RUXF920809MDFZXR02</code> — <code>maria123</code></li>
          <li>Carlos Mendoza Vega — CURP <code>MEVC781130HDFNGR03</code> — <code>carlos123</code></li>
          <li>Fernanda López Díaz — CURP <code>LODF010617MDFPZR04</code> — <code>fernanda123</code></li>
          <li>Asesor — usuario <code>asesor</code> — <code>asesor123</code></li>
          <li>Admin — usuario <code>admin</code> — <code>admin123</code></li>
          <li>Doctor — usuario <code>doctor</code> — <code>doctor123</code></li>
        </ul>
      </section>
    </div>
  );
}
