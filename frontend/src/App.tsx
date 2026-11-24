import { useEffect, useState } from 'react';
import { Link, NavLink, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { checkBackend, getPerfilYo, getToken, logout as cerrarSesionApi } from './api';
import type { PerfilYo } from './api';
import AdminUsuariosPage from './pages/AdminUsuariosPage';
import AppPage from './pages/AppPage';
import AsesorPage from './pages/AsesorPage';
import BienvenidaPage from './pages/BienvenidaPage';
import ClinicaPage from './pages/ClinicaPage';
import DoctorPage from './pages/DoctorPage';
import HistorialPage from './pages/HistorialPage';
import JourneyPage from './pages/JourneyPage';
import LoginPage from './pages/LoginPage';
import RegistroPage from './pages/RegistroPage';
import WebPage from './pages/WebPage';
import WhatsappPage from './pages/WhatsappPage';

export default function App() {
  const [mock, setMock] = useState(false);
  const [sesion, setSesion] = useState<PerfilYo | null>(null);
  const location = useLocation();
  const navigate = useNavigate();

  // La sesión vive en sessionStorage (ver api.ts): al cerrar el
  // navegador o abrir una pestaña nueva no hay usuario cargado. Si
  // queda un token viejo en localStorage (de antes de este cambio),
  // se borra al montar la App para que no vuelva a aparecer Luis.
  useEffect(() => {
    window.localStorage.removeItem('saluddigna-token');
  }, []);

  // Antes esto se chequeaba UNA sola vez al cargar: si el backend aún
  // estaba arrancando (Docker tarda 30-60 s la primera vez), el banner
  // "Modo demo sin backend" quedaba fijo aunque el backend ya estuviera
  // arriba. Ahora reintenta cada 5 s hasta que responde.
  useEffect(() => {
    let cancelado = false;
    async function chequear() {
      const vivo = await checkBackend();
      if (!cancelado) setMock(!vivo);
    }
    chequear();
    const id = window.setInterval(chequear, 5000);
    return () => {
      cancelado = true;
      window.clearInterval(id);
    };
  }, []);

  // La sesión se restaura al recargar y al cambiar de página (p. ej.
  // después de loguear en /login y navegar a /app).
  useEffect(() => {
    async function restaurar() {
      if (!getToken()) {
        setSesion(null);
        return;
      }
      try {
        setSesion(await getPerfilYo());
      } catch {
        setSesion(null);
      }
    }
    void restaurar();
  }, [location.pathname]);

  async function reintentar() {
    const vivo = await checkBackend();
    setMock(!vivo);
  }

  function cerrarSesion() {
    cerrarSesionApi();
    setSesion(null);
    navigate('/');
  }

  const nombreSesion = sesion?.paciente?.nombre.split(' ')[0] ?? sesion?.usuario ?? null;

  // Saludo: al doctor se le trata como tal, con su nombre de staff
  // (GET /api/auth/yo lo devuelve en `nombre`). El resto igual.
  const saludo =
    sesion?.rol === 'DOCTOR' ? `Hola, Dr. ${sesion.nombre ?? sesion.usuario}` : `Hola, ${nombreSesion}`;

  // Asesor y Clínica son del personal: sus enlaces solo se ven con
  // sesión de asesor o admin. Sin sesión, o con sesión de paciente,
  // no aparecen en el nav. Esto es solo visibilidad: la protección
  // real está en cada página (VistaPersonal), que pide el login de
  // personal aunque se entre por URL directa.
  const esPersonal = sesion?.rol === 'ASESOR' || sesion?.rol === 'ADMINISTRADOR';
  const esDoctor = sesion?.rol === 'DOCTOR' || sesion?.rol === 'ADMINISTRADOR';

  return (
    <div className="app-shell">
      <header className="topnav">
        <Link to="/" className="brand">
          <span className="brand-mark" aria-hidden="true">SD</span>
          <span>
            <strong>Salud Digna</strong>
            <small>Un paciente, una sola conversación</small>
          </span>
        </Link>
        <nav className="nav-links" aria-label="Canales">
          {/* La pestaña Inicio solo se ve SIN sesión: con sesión
              iniciada no aparece en ninguna página. El logo de marca
              sigue enlazando a "/". */}
          {!sesion && <NavLink to="/">Inicio</NavLink>}
          <NavLink to="/cotizar">Web</NavLink>
          <NavLink to="/whatsapp">WhatsApp</NavLink>
          {esPersonal && <NavLink to="/asesor">Asesor</NavLink>}
          {esPersonal && <NavLink to="/admin/usuarios">Usuarios</NavLink>}
          {esDoctor && <NavLink to="/doctor">Doctor</NavLink>}
          <NavLink to="/app">App</NavLink>
          <NavLink to="/historial">Historial</NavLink>
          {esPersonal && <NavLink to="/clinica">Clínica</NavLink>}
          <NavLink to="/journey">Journey</NavLink>
        </nav>
        <nav className="nav-links" aria-label="Sesión">
          {/* Sin sesión: Iniciar sesión y Crear cuenta. Con sesión, en
              su lugar "Hola, {nombre}" (con color claro sobre la barra
              azul, sin recorte) y Cerrar sesión bien visible. */}
          {sesion ? (
            <>
              <span className="sesion-nombre">{saludo}</span>
              <button type="button" className="btn btn-secondary" onClick={cerrarSesion}>
                Cerrar sesión
              </button>
            </>
          ) : (
            <>
              <NavLink to="/login">Iniciar sesión</NavLink>
              <NavLink to="/registro">Crear cuenta</NavLink>
            </>
          )}
        </nav>
      </header>

      {mock && (
        <div className="mock-banner" role="status">
          Modo demo sin backend — mostrando datos locales. El backend en http://localhost:8080/api no respondió.
          {' '}
          <button type="button" className="btn btn-secondary" onClick={reintentar} style={{ marginLeft: 8 }}>
            Reintentar conexión
          </button>
        </div>
      )}

      <main className="container">
        <Routes>
          <Route path="/" element={<BienvenidaPage />} />
          <Route path="/cotizar" element={<WebPage />} />
          <Route path="/web" element={<WebPage />} />
          <Route path="/whatsapp" element={<WhatsappPage />} />
          <Route path="/asesor" element={<AsesorPage />} />
          <Route path="/app" element={<AppPage />} />
          <Route path="/historial" element={<HistorialPage />} />
          <Route path="/clinica" element={<ClinicaPage />} />
          <Route path="/doctor" element={<DoctorPage />} />
          <Route path="/journey/:id" element={<JourneyPage />} />
          <Route path="/journey" element={<JourneyPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/registro" element={<RegistroPage />} />
          {/* Admin de usuarios: la pestaña "Usuarios" del nav solo se
              ve con sesión de asesor o admin (ver arriba); la puerta
              de personal de la página sigue protegiendo la URL directa. */}
          <Route path="/admin/usuarios" element={<AdminUsuariosPage />} />
          <Route path="*" element={<BienvenidaPage />} />
        </Routes>
      </main>

      <footer className="footer">
        <span>HackaTec Nacional 2026 · Salud Digna · Orquestación Omnicanal de Atención</span>
      </footer>
    </div>
  );
}
