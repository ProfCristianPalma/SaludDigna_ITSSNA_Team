import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { isApiError, login } from '../api';

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
      await login(usuario, password);
      navigate('/app');
    } catch (err) {
      setError(
        isApiError(err) && err.status === 401
          ? 'Usuario o contraseña incorrectos. Revisa tu teléfono y tu contraseña.'
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
      <p className="muted">Entra con tu teléfono (o tu primer nombre) y tu contraseña. La sesión se guarda en este navegador.</p>

      <section className="card" style={{ maxWidth: 480 }}>
        <form onSubmit={handleSubmit} className="form">
          <label>
            Usuario / teléfono
            <input
              value={usuario}
              onChange={(e) => setUsuario(e.target.value)}
              placeholder="2220000001 o ana"
              autoComplete="username"
              required
            />
          </label>
          <label>
            Contraseña
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
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
          ¿No tienes cuenta? <Link to="/registro">Regístrate aquí</Link>.
        </p>
        <p className="muted">
          <Link to="/">← Volver a inicio</Link>
        </p>
      </section>

      <section className="card" style={{ maxWidth: 480, marginTop: 16 }}>
        <h2>Cuentas de prueba</h2>
        <ul>
          <li>Ana García López — teléfono <code>2220000001</code> — contraseña <code>ana123</code></li>
          <li>Luis Hernández Torres — <code>2221112233</code> — <code>luis123</code></li>
          <li>María Fernanda Ruiz — <code>2224445566</code> — <code>maria123</code></li>
          <li>Asesor — usuario <code>asesor</code> — <code>asesor123</code></li>
        </ul>
      </section>
    </div>
  );
}
