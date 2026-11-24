import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { buscarPacientePorCurp, getTramitesPorPacienteId, isApiError } from '../api';
import { esCurp, normalizarCurp, validarCurp } from '../curp';
import { formatearFechaHora } from '../fechas';
import type { Paciente, Tramite } from '../types';
import { siguientePaso } from '../types';
import EstadoBadge from './EstadoBadge';

/**
 * "Retomar mi trámite con CURP" — sin login. Se teclea la CURP, se
 * resuelve el paciente (GET /api/pacientes?curp=) y se listan sus
 * folios (GET /api/pacientes/{id}/tramites) con su estado, su
 * siguiente paso y botones al Journey de cada folio o a WhatsApp.
 */
export default function RetomarTramite() {
  const [curp, setCurp] = useState('');
  const [errorCurp, setErrorCurp] = useState('');
  const [paciente, setPaciente] = useState<Paciente | null>(null);
  const [tramites, setTramites] = useState<Tramite[]>([]);
  const [mensaje, setMensaje] = useState('');
  const [buscando, setBuscando] = useState(false);
  const navigate = useNavigate();

  function handleCurpChange(valor: string) {
    const normalizada = normalizarCurp(valor);
    setCurp(normalizada);
    setMensaje('');
    if (!normalizada) {
      setErrorCurp('');
      return;
    }
    const v = validarCurp(normalizada);
    // En vivo solo se marca el error cuando ya tiene 18 caracteres
    // (antes, el largo incompleto no es un error, es que falta teclear).
    setErrorCurp(normalizada.length === 18 && !v.valida ? v.motivo : '');
  }

  async function handleBuscar(e: React.FormEvent) {
    e.preventDefault();
    const v = validarCurp(curp);
    if (!v.valida) {
      setErrorCurp(v.motivo);
      return;
    }
    setBuscando(true);
    setMensaje('');
    setPaciente(null);
    setTramites([]);
    try {
      const p = await buscarPacientePorCurp(curp);
      setPaciente(p);
      const lista = await getTramitesPorPacienteId(p.id);
      setTramites(lista);
      if (lista.length === 0) {
        setMensaje(`Encontramos a ${p.nombre}, pero aún no tiene folios. Cotiza un estudio en la web para crear el primero.`);
      }
    } catch (err) {
      if (isApiError(err) && err.status === 404) {
        setMensaje('No encontramos ningún trámite con esa CURP. Verifica que esté bien escrita o crea tu cuenta en Registro.');
      } else if (isApiError(err)) {
        setMensaje(`No se pudo buscar (HTTP ${err.status}: ${err.message}).`);
      } else {
        setMensaje('El backend no respondió, no se pudo buscar. Revisa que el backend esté arriba.');
      }
    } finally {
      setBuscando(false);
    }
  }

  function continuarEnWhatsapp(_t: Tramite) {
    // WhatsApp ya no toma en cuenta la sesión: el bot pide la CURP al
    // entrar, así que este botón solo navega a /whatsapp.
    navigate('/whatsapp');
  }

  return (
    <section className="card" style={{ marginTop: 16 }}>
      <h2>Retomar mi trámite con CURP</h2>
      <p className="muted">
        Sin iniciar sesión: escribe tu CURP y te mostramos tus folios con su estado actual y el siguiente paso.
      </p>
      <form className="form-inline" onSubmit={handleBuscar}>
        <input
          value={curp}
          onChange={(e) => handleCurpChange(e.target.value)}
          placeholder="CURP (18 caracteres)"
          aria-label="CURP"
          maxLength={18}
          autoComplete="off"
          style={{ textTransform: 'uppercase' }}
        />
        <button type="submit" className="btn btn-primary" disabled={buscando || !esCurp(curp)}>
          {buscando ? 'Buscando…' : 'Ver mis folios'}
        </button>
      </form>
      {errorCurp && (
        <p className="notice" role="alert">
          {errorCurp}
        </p>
      )}
      {mensaje && <p className="notice" role="status">{mensaje}</p>}

      {paciente && tramites.length > 0 && (
        <div style={{ marginTop: 12 }}>
          <p>
            Paciente: <strong>{paciente.nombre}</strong>
            {paciente.telefono ? ` · ${paciente.telefono}` : ' · Sin teléfono'}
          </p>
          <ul>
            {tramites.map((t) => (
              <li key={t.id} className="dato-row">
                <span>
                  <strong>{t.folio}</strong> · {t.estudio.nombre} — ${t.precioCotizado}{' '}
                  <EstadoBadge estado={t.estado} />
                  {t.clinica ? ` · ${t.clinica.nombre}` : ''}
                  {t.fechaCita ? ` · Cita: ${formatearFechaHora(t.fechaCita)}` : ''}
                  <br />
                  <span className="muted">{siguientePaso(t)}</span>
                </span>
                <span className="actions">
                  <Link className="btn btn-small" to={`/journey/${encodeURIComponent(t.folio)}`}>
                    Ver Journey
                  </Link>
                  <button type="button" className="btn btn-small btn-primary" onClick={() => continuarEnWhatsapp(t)}>
                    Continuar en WhatsApp
                  </button>
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
