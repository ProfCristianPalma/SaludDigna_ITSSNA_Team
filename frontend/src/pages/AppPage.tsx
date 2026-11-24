import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getPerfilYo, getToken, getTramitesPorTelefono, isApiError } from '../api';
import type { Paciente, Tramite } from '../types';
import EstadoBadge from '../components/EstadoBadge';

export default function AppPage() {
  const [paciente, setPaciente] = useState<Paciente | null>(null);
  const [tramites, setTramites] = useState<Tramite[]>([]);
  const [mensaje, setMensaje] = useState('');
  const [sinSesion, setSinSesion] = useState(false);
  const [cargando, setCargando] = useState(true);

  const cargar = useCallback(async () => {
    // Sin sesión no hay paciente que mostrar: la App exige login.
    if (!getToken()) {
      setSinSesion(true);
      setCargando(false);
      return;
    }
    setCargando(true);
    setMensaje('');
    try {
      const perfil = await getPerfilYo();
      if (!perfil.paciente) {
        setMensaje('Esta vista es para pacientes. Tu sesión es de staff: usa Asesor o Clínica.');
        setCargando(false);
        return;
      }
      setPaciente(perfil.paciente);
      // Solo los folios de ESTE paciente, por su teléfono de sesión.
      const lista = await getTramitesPorTelefono(perfil.paciente.telefono);
      setTramites(lista);
      if (lista.length === 0) setMensaje('No tienes folios todavía. Cotiza un estudio en la web y aparecerá aquí.');
    } catch (err) {
      if (isApiError(err) && err.status === 401) {
        setSinSesion(true);
      } else {
        setMensaje('No se pudo cargar tu perfil. Verifica que el backend esté arriba.');
      }
    } finally {
      setCargando(false);
    }
  }, []);

  useEffect(() => {
    void cargar();
  }, [cargar]);

  // Recarga al volver a la página: los folios creados en Web o
  // avanzados en WhatsApp aparecen sin recargar a mano.
  useEffect(() => {
    const alVolver = () => {
      if (!document.hidden) void cargar();
    };
    window.addEventListener('focus', alVolver);
    document.addEventListener('visibilitychange', alVolver);
    return () => {
      window.removeEventListener('focus', alVolver);
      document.removeEventListener('visibilitychange', alVolver);
    };
  }, [cargar]);

  function abrirWhatsapp() {
    // WhatsApp arranca con el paciente logueado, no con Ana de ejemplo.
    if (paciente) {
      window.localStorage.setItem('saluddigna-wa-telefono', paciente.telefono);
    }
  }

  const primerNombre = paciente?.nombre.split(' ')[0] ?? 'Hola';
  const tramitePrincipal = tramites[0] ?? null;

  return (
    <div className="phone-wrap">
      <div className="phone">
        <div className="phone-header">
          <span className="brand-mark" aria-hidden="true">SD</span>
          <strong>Salud Digna</strong>
        </div>
        <div className="phone-body">
          {sinSesion ? (
            <>
              <h1>Hola 👋</h1>
              <p className="muted">Para ver tus citas, primero inicia sesión con tu teléfono y tu contraseña.</p>
              <div className="actions">
                <Link className="btn btn-primary" to="/login">Iniciar sesión</Link>
                <Link className="btn btn-secondary" to="/registro">Crear cuenta</Link>
              </div>
              <p className="muted">Cuenta de prueba: 2220000001 / ana123</p>
            </>
          ) : (
            <>
              <h1>Hola, {primerNombre} 👋</h1>
              <p className="muted">Tu salud, en una sola conversación.</p>
              {paciente && <p className="muted">{paciente.nombre} · {paciente.telefono}</p>}

              <div className="actions">
                <button type="button" className="btn btn-secondary" onClick={() => void cargar()} disabled={cargando}>
                  {cargando ? 'Cargando…' : 'Actualizar'}
                </button>
                <Link className="btn btn-secondary" to="/historial">Ver mi historial</Link>
              </div>

              {cargando && <p className="muted">Cargando tus citas…</p>}
              {mensaje && <p className="notice" role="status">{mensaje}</p>}

              {tramites.length === 0 && !mensaje && !cargando && (
                <section className="card cita-card">
                  <p>No hay una cita cargada todavía.</p>
                  <Link className="btn btn-primary" to="/cotizar">Cotizar en la web</Link>
                </section>
              )}

              {tramites.map((t) => (
                <section key={t.id} className="card cita-card">
                  <p className="muted">Tu cita</p>
                  <h2>{t.estudio.nombre}</h2>
                  <p>
                    Folio: <strong>{t.folio}</strong>
                  </p>
                  <p>
                    <EstadoBadge estado={t.estado} />
                  </p>
                  <p>
                    Precio: <strong>${t.precioCotizado}</strong>
                  </p>
                  <p>Clínica: {t.clinica ? `${t.clinica.nombre} (${t.clinica.ciudad})` : 'Por elegir'}</p>
                  <p>Fecha: {t.fechaCita ?? 'Por confirmar'}</p>
                  <div className="actions">
                    <Link className="btn btn-secondary" to={`/journey/${encodeURIComponent(t.folio)}`}>
                      Ver mi Journey
                    </Link>
                  </div>
                </section>
              ))}

              <section className="card">
                <h2>Próximos pasos</h2>
                <ol className="steps-list">
                  <li>Elige tu clínica en WhatsApp o en la web.</li>
                  <li>Confirma fecha y hora de tu cita.</li>
                  <li>
                    {tramites.length > 1
                      ? 'Llega 15 minutos antes con cualquiera de tus folios.'
                      : `Llega 15 minutos antes con tu folio ${tramitePrincipal?.folio ?? '—'}.`}
                  </li>
                  <li>En clínica, haz check-in con ese folio.</li>
                </ol>
              </section>

              <div className="actions">
                <Link className="btn btn-primary" to="/whatsapp" onClick={abrirWhatsapp}>
                  Abrir WhatsApp
                </Link>
                {tramitePrincipal && (
                  <Link className="btn btn-secondary" to={`/journey/${encodeURIComponent(tramitePrincipal.folio)}`}>
                    Ver mi Journey
                  </Link>
                )}
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
