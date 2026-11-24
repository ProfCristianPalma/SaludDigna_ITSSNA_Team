import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getPerfilYo, getToken } from '../api';
import type { PerfilYo } from '../api';

// Página de inicio: bienvenida a Salud Digna y mapa de canales.
// La Web de cotizar vive en /cotizar (con alias /web); aquí solo se presenta.
export default function BienvenidaPage() {
  const [sesion, setSesion] = useState<PerfilYo | null>(null);

  useEffect(() => {
    if (!getToken()) return;
    getPerfilYo().then(setSesion).catch(() => setSesion(null));
  }, []);

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
            <p>Hola, <strong>{nombre}</strong> 👋</p>
            <div className="actions">
              <Link className="btn btn-primary" to="/app">Ir a mi App</Link>
              <Link className="btn btn-secondary" to="/historial">Ver mi historial</Link>
              <Link className="btn btn-secondary" to="/cotizar">Cotizar un estudio</Link>
            </div>
          </>
        ) : (
          <div className="actions">
            <Link className="btn btn-primary" to="/login">Iniciar sesión</Link>
            <Link className="btn btn-secondary" to="/registro">Crear cuenta</Link>
            <Link className="btn btn-secondary" to="/cotizar">Cotizar un estudio</Link>
          </div>
        )}
      </section>

      <div className="grid-2" style={{ marginTop: 16 }}>
        <section className="card">
          <h2>Web — cotizar</h2>
          <p className="muted">Elige tu estudio, ve el precio y crea tu folio. Cada estudio es un folio nuevo.</p>
          <Link className="btn btn-primary" to="/cotizar">Cotizar un estudio</Link>
        </section>
        <section className="card">
          <h2>WhatsApp — continuar</h2>
          <p className="muted">Retoma tu trámite por teléfono: elige clínica y confirma tu cita sin repetir datos.</p>
          <Link className="btn btn-secondary" to="/whatsapp">Abrir WhatsApp</Link>
        </section>
        <section className="card">
          <h2>App — tus citas</h2>
          <p className="muted">Inicia sesión y ve tus citas con tu nombre y tus folios. Sin sesión no hay paciente que mostrar.</p>
          <Link className="btn btn-secondary" to="/app">Ir a mi App</Link>
        </section>
        <section className="card">
          <h2>Historial — tus folios</h2>
          <p className="muted">Todos tus folios en un solo lugar, del más reciente al más viejo, cada uno con su Journey.</p>
          <Link className="btn btn-secondary" to="/historial">Ver mi historial</Link>
        </section>
        <section className="card">
          <h2>Asesor — Call Center</h2>
          <p className="muted">El asesor busca por teléfono, folio o nombre y ve todo tu contexto sin pedirte que repitas nada.</p>
          <Link className="btn btn-secondary" to="/asesor">Consola del asesor</Link>
        </section>
        <section className="card">
          <h2>Clínica — recepción</h2>
          <p className="muted">Las citas confirmadas de todos los pacientes, listas para check-in y atención. No es tu historial personal.</p>
          <Link className="btn btn-secondary" to="/clinica">Ir a recepción</Link>
        </section>
        <section className="card">
          <h2>Journey — tu recorrido</h2>
          <p className="muted">Tu trámite paso a paso y todos los canales que recorriste, en tiempo real.</p>
          <Link className="btn btn-secondary" to="/journey">Ver Journey</Link>
        </section>
      </div>
    </div>
  );
}
