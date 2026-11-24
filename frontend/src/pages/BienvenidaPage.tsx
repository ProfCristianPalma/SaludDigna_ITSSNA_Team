import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { getPerfilYo, getToken, logout } from '../api';
import type { PerfilYo } from '../api';

// Página de inicio: solo presentación y acciones de sesión. Sin
// tarjetas de canales y sin botón de cotizar: para cotizar está el
// enlace "Cotizar" del menú. Con sesión, no se muestran "Iniciar
// sesión" ni "Crear cuenta": se muestra "Continuar como {nombre}",
// "Ver mi historial" y "Cerrar sesión".
export default function BienvenidaPage() {
  const [sesion, setSesion] = useState<PerfilYo | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    if (!getToken()) return;
    getPerfilYo().then(setSesion).catch(() => setSesion(null));
  }, []);

  function cerrarSesion() {
    logout();
    setSesion(null);
    navigate('/');
  }

  const nombre = sesion?.paciente?.nombre.split(' ')[0] ?? sesion?.usuario ?? null;

  return (
    <div>
      <section className="card">
        <h1>Bienvenido a Salud Digna</h1>
        <p className="muted">
          <strong>Un paciente, una sola conversación.</strong> Empieza tu trámite en un canal
          y continúa en cualquier otro sin repetir tus datos: tu folio te sigue a la Web,
          WhatsApp, el Call Center, la App y la Clínica.
        </p>
        {nombre ? (
          <>
            <p>
              Sesión activa: Hola, <strong>{nombre}</strong> 👋
            </p>
            <div className="actions">
              <Link className="btn btn-primary" to="/app">Continuar como {nombre}</Link>
              <Link className="btn btn-secondary" to="/historial">Ver mi historial</Link>
              <button type="button" className="btn btn-secondary" onClick={cerrarSesion}>
                Cerrar sesión
              </button>
            </div>
          </>
        ) : (
          <div className="actions">
            <Link className="btn btn-primary" to="/login">Iniciar sesión</Link>
            <Link className="btn btn-secondary" to="/registro">Crear cuenta</Link>
          </div>
        )}
      </section>
    </div>
  );
}
