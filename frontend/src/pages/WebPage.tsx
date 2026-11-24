import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { cotizar, crearTramite, getClinicas, getEstudios, seleccionarClinica } from '../api';
import { MOCK_PACIENTE_ANA } from '../mock';
import type { Clinica, Estudio, Tramite } from '../types';
import EstadoBadge from '../components/EstadoBadge';

const FOLIO_KEY = 'saluddigna-folio';

export default function WebPage() {
  const [nombre, setNombre] = useState(MOCK_PACIENTE_ANA.nombre);
  const [telefono, setTelefono] = useState(MOCK_PACIENTE_ANA.telefono);
  const [fechaNacimiento, setFechaNacimiento] = useState(MOCK_PACIENTE_ANA.fechaNacimiento);
  const [estudios, setEstudios] = useState<Estudio[]>([]);
  const [clinicas, setClinicas] = useState<Clinica[]>([]);
  const [estudioId, setEstudioId] = useState('');
  const [tramite, setTramite] = useState<Tramite | null>(null);
  const [clinicaId, setClinicaId] = useState('');
  const [mensaje, setMensaje] = useState('');
  const [cargando, setCargando] = useState(false);

  useEffect(() => {
    getEstudios().then((list) => {
      setEstudios(list);
      if (list.length > 0) setEstudioId(list[0].id);
    });
    getClinicas().then(setClinicas);
    const guardado = window.localStorage.getItem(FOLIO_KEY);
    if (guardado) setMensaje(`Tienes un trámite guardado: ${guardado}. Puedes retomarlo en WhatsApp o en Journey.`);
  }, []);

  const estudioSeleccionado = estudios.find((e) => e.id === estudioId);

  async function handleCotizar(e: React.FormEvent) {
    e.preventDefault();
    if (!estudioId) return;
    setCargando(true);
    setMensaje('');
    try {
      const creado = await crearTramite({ nombre, telefono, fechaNacimiento, estudioId, canal: 'WEB' });
      const cotizado = await cotizar(creado.id, estudioId);
      setTramite(cotizado);
      window.localStorage.setItem(FOLIO_KEY, cotizado.folio);
      setMensaje(`Cotización lista. Folio ${cotizado.folio}.`);
    } catch {
      setMensaje('No se pudo cotizar. Inténtalo de nuevo.');
    } finally {
      setCargando(false);
    }
  }

  async function handleClinica() {
    if (!tramite || !clinicaId) return;
    setCargando(true);
    try {
      const actualizado = await seleccionarClinica(tramite.id, clinicaId);
      setTramite(actualizado);
      setMensaje(`Clínica seleccionada: ${actualizado.clinica?.nombre ?? ''}.`);
    } catch {
      setMensaje('No se pudo seleccionar la clínica.');
    } finally {
      setCargando(false);
    }
  }

  function handleInterrumpir() {
    if (!tramite) return;
    window.localStorage.setItem(FOLIO_KEY, tramite.folio);
    setMensaje(
      `Simulación de interrupción: cerraste sin confirmar. Tu folio ${tramite.folio} quedó guardado y puedes retomarlo por WhatsApp, con el asesor o en la App — sin repetir tus datos.`,
    );
  }

  return (
    <div>
      <h1>Cotiza tu estudio en línea</h1>
      <p className="muted">Un paciente, una sola conversación: empieza aquí y continúa en cualquier canal.</p>

      <div className="grid-2">
        <section className="card">
          <h2>1 · Tus datos y tu estudio</h2>
          <form onSubmit={handleCotizar} className="form">
            <label>
              Nombre completo
              <input value={nombre} onChange={(e) => setNombre(e.target.value)} required />
            </label>
            <label>
              Teléfono (WhatsApp)
              <input value={telefono} onChange={(e) => setTelefono(e.target.value)} required />
            </label>
            <label>
              Fecha de nacimiento
              <input type="date" value={fechaNacimiento} onChange={(e) => setFechaNacimiento(e.target.value)} required />
            </label>
            <label>
              Estudio
              <select value={estudioId} onChange={(e) => setEstudioId(e.target.value)}>
                {estudios.map((est) => (
                  <option key={est.id} value={est.id}>
                    {est.nombre} — ${est.precio}
                  </option>
                ))}
              </select>
            </label>
            {estudioSeleccionado && (
              <p className="precio">
                Precio: <strong>${estudioSeleccionado.precio}</strong>
              </p>
            )}
            <button type="submit" className="btn btn-primary" disabled={cargando}>
              {cargando ? 'Cotizando…' : 'Cotizar'}
            </button>
          </form>
        </section>

        <section className="card">
          <h2>2 · Tu cotización</h2>
          {!tramite && <p className="muted">Aún no hay cotización. Completa el formulario y presiona Cotizar.</p>}
          {tramite && (
            <div>
              <p>
                Folio: <strong>{tramite.folio}</strong> <EstadoBadge estado={tramite.estado} />
              </p>
              <p>
                Estudio: {tramite.estudio.nombre} — <strong>${tramite.precioCotizado}</strong>
              </p>
              <p className="muted">Canal de origen: {tramite.canalOrigen}</p>

              <h3>Elige clínica</h3>
              <div className="form">
                <select value={clinicaId} onChange={(e) => setClinicaId(e.target.value)}>
                  <option value="">Selecciona una clínica</option>
                  {clinicas.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.nombre} ({c.ciudad})
                    </option>
                  ))}
                </select>
                <button type="button" className="btn btn-secondary" onClick={handleClinica} disabled={!clinicaId || cargando}>
                  Seleccionar clínica
                </button>
              </div>
              {tramite.clinica && <p>Clínica: <strong>{tramite.clinica.nombre}</strong> ({tramite.clinica.ciudad})</p>}

              <div className="actions">
                <button type="button" className="btn btn-ghost" onClick={handleInterrumpir}>
                  Cerrar sin confirmar (simular interrupción)
                </button>
                <Link className="btn btn-primary" to={`/journey/${encodeURIComponent(tramite.folio)}`}>
                  Ver mi Journey
                </Link>
              </div>
            </div>
          )}
        </section>
      </div>

      {mensaje && <p className="notice" role="status">{mensaje}</p>}
    </div>
  );
}
