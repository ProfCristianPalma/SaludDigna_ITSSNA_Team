import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { isApiError, registro } from '../api';

export default function RegistroPage() {
  const [nombre, setNombre] = useState('');
  const [telefono, setTelefono] = useState('');
  const [email, setEmail] = useState('');
  const [fechaNacimiento, setFechaNacimiento] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [cargando, setCargando] = useState(false);
  const navigate = useNavigate();

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    if (password.length < 6) {
      setError('La contraseña debe tener al menos 6 caracteres.');
      return;
    }
    setCargando(true);
    try {
      await registro({ nombre, telefono, email, fechaNacimiento, password });
      navigate('/app');
    } catch (err) {
      setError(
        isApiError(err) && err.status === 409
          ? 'Ese teléfono ya está registrado. Inicia sesión o usa otro número.'
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
      <p className="muted">Tu cuenta queda guardada en la base de datos de Salud Digna. Al registrarte entras directo a tu App.</p>

      <section className="card" style={{ maxWidth: 480 }}>
        <form onSubmit={handleSubmit} className="form">
          <label>
            Nombre completo
            <input value={nombre} onChange={(e) => setNombre(e.target.value)} placeholder="Ej. Ana García López" autoComplete="name" required />
          </label>
          <label>
            Teléfono (WhatsApp)
            <input value={telefono} onChange={(e) => setTelefono(e.target.value)} placeholder="+52 222 000 0001" autoComplete="tel" required />
          </label>
          <label>
            Correo electrónico
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="tu@correo.com" autoComplete="email" />
          </label>
          <label>
            Fecha de nacimiento
            <input type="date" value={fechaNacimiento} onChange={(e) => setFechaNacimiento(e.target.value)} required />
          </label>
          <label>
            Contraseña (mínimo 6 caracteres)
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
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
          ¿Ya tienes cuenta? <Link to="/login">Inicia sesión aquí</Link>.
        </p>
      </section>
    </div>
  );
}
