import { useEffect, useState } from 'react';
import { Link, NavLink, Route, Routes } from 'react-router-dom';
import { checkBackend } from './api';
import AppPage from './pages/AppPage';
import AsesorPage from './pages/AsesorPage';
import ClinicaPage from './pages/ClinicaPage';
import JourneyPage from './pages/JourneyPage';
import WebPage from './pages/WebPage';
import WhatsappPage from './pages/WhatsappPage';

export default function App() {
  const [mock, setMock] = useState(false);

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

  async function reintentar() {
    const vivo = await checkBackend();
    setMock(!vivo);
  }

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
          <NavLink to="/">Web</NavLink>
          <NavLink to="/whatsapp">WhatsApp</NavLink>
          <NavLink to="/asesor">Asesor</NavLink>
          <NavLink to="/app">App</NavLink>
          <NavLink to="/clinica">Clínica</NavLink>
          <NavLink to="/journey">Journey</NavLink>
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
          <Route path="/" element={<WebPage />} />
          <Route path="/whatsapp" element={<WhatsappPage />} />
          <Route path="/asesor" element={<AsesorPage />} />
          <Route path="/app" element={<AppPage />} />
          <Route path="/clinica" element={<ClinicaPage />} />
          <Route path="/journey/:id" element={<JourneyPage />} />
          <Route path="/journey" element={<JourneyPage />} />
          <Route path="*" element={<WebPage />} />
        </Routes>
      </main>

      <footer className="footer">
        <span>HackaTec Nacional 2026 · Salud Digna · Orquestación Omnicanal de Atención</span>
      </footer>
    </div>
  );
}
