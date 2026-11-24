import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getPerfilYo, getToken, getTramitesPorPacienteId, isApiError } from '../api';
import { formatearFechaHora } from '../fechas';
import type { EstadoTramite, Paciente, Tramite } from '../types';
import { siguientePaso } from '../types';
import EstadoBadge from '../components/EstadoBadge';

// Pasos del flujo, en orden: INICIADO → COTIZADO → CLINICA_SELECCIONADA
// → CONFIRMADO → ATENDIDO. DATOS_PENDIENTES es una rama de INICIADO:
// el trámite empezó pero faltan datos por completar antes de cotizar.
const PASOS_FLUJO: { estado: EstadoTramite; etiqueta: string }[] = [
  { estado: 'INICIADO', etiqueta: 'Trámite iniciado' },
  { estado: 'COTIZADO', etiqueta: 'Cotizado' },
  { estado: 'CLINICA_SELECCIONADA', etiqueta: 'Clínica elegida' },
  { estado: 'CONFIRMADO', etiqueta: 'Cita confirmada' },
  { estado: 'ATENDIDO', etiqueta: 'Atención en clínica' },
];

const INDICE_PASO: Record<string, number> = {
  INICIADO: 0,
  DATOS_PENDIENTES: 0,
  COTIZADO: 1,
  CLINICA_SELECCIONADA: 2,
  CONFIRMADO: 3,
  ATENDIDO: 4,
  // Llegaron a CONFIRMADO: el cierre es terminal, no un paso más.
  NO_PRESENTADO: 3,
  NO_REALIZADO: 3,
};

/** Pasos realizados y faltantes de un folio, derivados de su estado y
    de sus datos pendientes (sin pedir el timeline: lo simple y correcto). */
function pasosDe(t: Tramite): { realizados: string[]; faltantes: string[] } {
  // Cierres terminales: los 4 primeros pasos + la etiqueta de cierre,
  // y nada faltante (el trámite ya terminó).
  if (t.estado === 'NO_PRESENTADO') {
    return {
      realizados: [...PASOS_FLUJO.slice(0, 4).map((p) => p.etiqueta), 'No se presentó a la cita'],
      faltantes: [],
    };
  }
  if (t.estado === 'NO_REALIZADO') {
    return {
      realizados: [...PASOS_FLUJO.slice(0, 4).map((p) => p.etiqueta), 'No se pudo realizar el estudio'],
      faltantes: [],
    };
  }
  const idx = INDICE_PASO[t.estado] ?? 0;
  const realizados = PASOS_FLUJO.slice(0, idx + 1).map((p) => p.etiqueta);
  const faltantes = PASOS_FLUJO.slice(idx + 1).map((p) => p.etiqueta);
  if (t.estado === 'DATOS_PENDIENTES' || t.datosPendientes.length > 0) {
    const pendientes =
      t.datosPendientes.length > 0
        ? `Completar datos pendientes (${t.datosPendientes.join(', ')})`
        : 'Completar datos pendientes';
    faltantes.unshift(pendientes);
  }
  return { realizados, faltantes };
}

// Historial = TODOS los folios de UN solo paciente (el logueado).
// No confundir con Clínica, que es la recepción con las citas
// confirmadas de TODOS los pacientes.
export default function HistorialPage() {
  const [paciente, setPaciente] = useState<Paciente | null>(null);
  const [tramites, setTramites] = useState<Tramite[]>([]);
  const [mensaje, setMensaje] = useState('');
  const [sinSesion, setSinSesion] = useState(false);
  const [cargando, setCargando] = useState(true);
  const cargar = useCallback(async () => {
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
      const lista = await getTramitesPorPacienteId(perfil.paciente.id);
      setTramites(lista);
      if (lista.length === 0) setMensaje('Aún no tienes folios.');
    } catch (err) {
      if (isApiError(err) && err.status === 401) {
        setSinSesion(true);
      } else {
        setMensaje('No se pudo cargar tu historial. Verifica que el backend esté arriba.');
      }
    } finally {
      setCargando(false);
    }
  }, []);

  useEffect(() => {
    void cargar();
  }, [cargar]);

  // Recarga al volver a la página, para que los folios creados en Web
  // o avanzados en WhatsApp aparezcan sin tener que recargar a mano.
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

  if (sinSesion) {
    return (
      <div>
        <h1>Mi historial de folios</h1>
        <p className="muted">Para ver tus folios, primero inicia sesión con tu CURP y tu contraseña.</p>
        <div className="actions">
          <Link className="btn btn-primary" to="/login">Iniciar sesión</Link>
          <Link className="btn btn-secondary" to="/registro">Crear cuenta</Link>
        </div>
      </div>
    );
  }

  return (
    <div>
      <h1>Mi historial de folios</h1>
      <p className="muted">
        {paciente ? `${paciente.nombre} · ${paciente.curp ?? paciente.telefono ?? 'Sin teléfono'}` : 'Tus folios, guardados en la base de datos.'}
        {' '}Un folio por cada estudio que hayas elegido, del más reciente al más viejo.
      </p>

      <div className="actions" style={{ marginBottom: 16 }}>
        <button type="button" className="btn btn-secondary" onClick={() => void cargar()} disabled={cargando}>
          {cargando ? 'Cargando…' : 'Actualizar'}
        </button>
        <Link className="btn btn-primary" to="/cotizar">Cotizar un estudio</Link>
      </div>

      {cargando && tramites.length === 0 && <p className="muted">Cargando tu historial…</p>}
      {mensaje && <p className="notice" role="status">{mensaje}</p>}
      {tramites.length === 0 && !cargando && !mensaje && (
        <section className="card">
          <p>Aún no tienes folios.</p>
          <Link className="btn btn-primary" to="/cotizar">Cotizar tu primer estudio</Link>
        </section>
      )}

      {tramites.map((t) => {
        const pasos = pasosDe(t);
        return (
          <section key={t.id} className="card" style={{ marginBottom: 12 }}>
            <p>
              Folio: <strong>{t.folio}</strong> <EstadoBadge estado={t.estado} />
            </p>
            <h2>{t.estudio.nombre}</h2>
            <dl className="kv">
              <dt>Precio</dt><dd><strong>${t.precioCotizado}</strong></dd>
              <dt>Clínica</dt><dd>{t.clinica ? `${t.clinica.nombre} (${t.clinica.ciudad})` : 'Por elegir'}</dd>
              <dt>Fecha de cita</dt><dd>{t.fechaCita ? formatearFechaHora(t.fechaCita) : 'Por confirmar'}</dd>
              <dt>Canal de origen</dt><dd>{t.canalOrigen}</dd>
              {t.datosPendientes.length > 0 && (
                <>
                  <dt>Datos pendientes</dt><dd>{t.datosPendientes.join(', ')}</dd>
                </>
              )}
            </dl>
            <h3>Pasos realizados</h3>
            <ul className="pasos-lista">
              {pasos.realizados.map((p) => (
                <li key={p}>✓ {p}</li>
              ))}
            </ul>
            <h3>Pasos faltantes</h3>
            {pasos.faltantes.length === 0 ? (
              <p className="muted">Trámite completado, no hay pasos pendientes.</p>
            ) : (
              <ul className="pasos-lista">
                {pasos.faltantes.map((p) => (
                  <li key={p} className="pasos-falta">○ {p}</li>
                ))}
              </ul>
            )}
            <p className="muted">{siguientePaso(t)}</p>
            <div className="actions">
              <Link className="btn btn-secondary" to={`/journey/${encodeURIComponent(t.folio)}`}>
                Ver mi Journey
              </Link>
              {t.estado !== 'ATENDIDO' && t.estado !== 'NO_PRESENTADO' && t.estado !== 'NO_REALIZADO' && t.estado !== 'CANCELADO' && (
                <Link className="btn btn-primary" to="/whatsapp">
                  Continuar en WhatsApp
                </Link>
              )}
            </div>
          </section>
        );
      })}
    </div>
  );
}
