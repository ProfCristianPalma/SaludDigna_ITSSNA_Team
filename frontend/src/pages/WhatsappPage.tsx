import { useEffect, useRef, useState } from 'react';
import { getPerfilYo, getToken, getTramitesPorTelefono, enviarWhatsapp, normalizarTelefono } from '../api';
import type { Paciente, Tramite } from '../types';
import EstadoBadge from '../components/EstadoBadge';

interface MensajeChat {
  id: number;
  de: 'yo' | 'bot';
  texto: string;
}

const WA_TELEFONO_KEY = 'saluddigna-wa-telefono';
const WA_FOLIO_KEY = 'saluddigna-wa-folio';

export default function WhatsappPage() {
  // Sin sesión y sin teléfono guardado en esta pestaña, el teléfono
  // arranca VACÍO: ya no hay ningún usuario de ejemplo cargado.
  const [telefono, setTelefono] = useState('');
  const [paciente, setPaciente] = useState<Paciente | null>(null);
  const [texto, setTexto] = useState('');
  const [mensajes, setMensajes] = useState<MensajeChat[]>([
    { id: 1, de: 'bot', texto: 'Hola, soy Salud Digna 👋. ¿En qué te ayudo hoy? Si ya empezaste un trámite en la web, dime tu teléfono o tu folio y lo retomamos donde lo dejaste.' },
  ]);
  const [tramite, setTramite] = useState<Tramite | null>(null);
  const [tramites, setTramites] = useState<Tramite[]>([]);
  const [enviando, setEnviando] = useState(false);
  const [sesionLista, setSesionLista] = useState(false);
  const finRef = useRef<HTMLDivElement>(null);
  const folioAutoEnviado = useRef(false);

  // Con sesión, WhatsApp arranca con EL paciente logueado: su nombre,
  // su teléfono y sus folios. Sin sesión, el teléfono arranca vacío y
  // hay que escribirlo (o entrar desde App/Historial, que lo dejan en
  // sessionStorage como respaldo solo para esta pestaña).
  useEffect(() => {
    async function cargarSesion() {
      const guardadoTel = window.sessionStorage.getItem(WA_TELEFONO_KEY);
      if (guardadoTel) setTelefono(normalizarTelefono(guardadoTel));
      if (!getToken()) return;
      try {
        const perfil = await getPerfilYo();
        if (!perfil.paciente) return;
        setPaciente(perfil.paciente);
        setTelefono(perfil.paciente.telefono);
        const lista = await getTramitesPorTelefono(perfil.paciente.telefono);
        setTramites(lista);
        const activos = lista.filter((t) => t.estado !== 'ATENDIDO');
        if (activos.length > 0) {
          setMensajes((prev) => [
            ...prev,
            {
              id: 2,
              de: 'bot',
              texto: `Hola ${perfil.paciente?.nombre.split(' ')[0]}, tienes ${activos.length} folio${activos.length > 1 ? 's' : ''} activo${activos.length > 1 ? 's' : ''}. Escribe el folio con el que quieres seguir o pulsa uno de abajo.`,
            },
          ]);
        }
      } catch {
        // Sin sesión válida: el teléfono se queda como esté (vacío o el guardado).
      } finally {
        setSesionLista(true);
      }
    }
    void cargarSesion();
  }, []);

  useEffect(() => {
    finRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [mensajes]);

  async function handleEnviar(e: React.FormEvent, textoForzado?: string) {
    e.preventDefault();
    const limpio = (textoForzado ?? texto).trim();
    if (!limpio) return;
    const idBase = Date.now();
    setMensajes((prev) => [...prev, { id: idBase, de: 'yo', texto: limpio }]);
    setTexto('');
    setEnviando(true);
    try {
      const res = await enviarWhatsapp(telefono, limpio);
      if (res.tramite) setTramite(res.tramite);
      setTramites(res.tramites ?? []);
      setMensajes((prev) => [...prev, { id: idBase + 1, de: 'bot', texto: res.respuesta }]);
    } catch {
      setMensajes((prev) => [
        ...prev,
        { id: idBase + 1, de: 'bot', texto: 'No pude conectar con el backend, tu mensaje no se envió ni se guardó. Revisa que el backend esté arriba e inténtalo de nuevo.' },
      ]);
    } finally {
      setEnviando(false);
    }
  }

  // Si se llegó desde Historial con un folio, se manda solo una vez,
  // y solo cuando ya se cargó la sesión (si no, se mandaría con un
  // teléfono vacío o de otra persona y el folio no pertenecería a ese teléfono).
  useEffect(() => {
    if (!sesionLista || folioAutoEnviado.current) return;
    const folio = window.sessionStorage.getItem(WA_FOLIO_KEY);
    if (!folio || !telefono) return;
    folioAutoEnviado.current = true;
    window.sessionStorage.removeItem(WA_FOLIO_KEY);
    void handleEnviar(new Event('submit') as unknown as React.FormEvent, folio);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [telefono, sesionLista]);

  const primerNombre = paciente?.nombre.split(' ')[0];

  return (
    <div>
      <h1>WhatsApp — continúa tu trámite</h1>
      <p className="muted">
        {paciente
          ? `Hola, ${primerNombre}: el bot te reconoce por tu teléfono ${paciente.telefono} y tus folios.`
          : 'El bot reconoce tus trámites por teléfono. Si tienes varios folios, escribe el folio (por ejemplo SD-2026-0001) o “folio 1” para elegir con cuál seguir. Si inicias sesión, arranca con tus datos.'}
      </p>

      <div className="wa-shell">
        <div className="wa-header">
          <span className="wa-avatar" aria-hidden="true">SD</span>
          <div>
            <strong>Salud Digna</strong>
            <small>en línea · responde al instante</small>
          </div>
          {tramite && <EstadoBadge estado={tramite.estado} />}
        </div>

        <div className="wa-body">
          {mensajes.map((m) => (
            <div key={m.id} className={m.de === 'yo' ? 'bubble bubble-yo' : 'bubble bubble-bot'}>
              {m.texto}
            </div>
          ))}
          <div ref={finRef} />
        </div>

        <form className="wa-form" onSubmit={handleEnviar}>
          <input
            value={telefono}
            onChange={(e) => setTelefono(e.target.value)}
            placeholder="Tu teléfono (10 dígitos)"
            aria-label="Teléfono"
          />
          <input
            value={texto}
            onChange={(e) => setTexto(e.target.value)}
            placeholder="Escribe un mensaje…"
            aria-label="Mensaje"
          />
          <button type="submit" className="btn btn-wa" disabled={enviando}>
            {enviando ? '…' : 'Enviar'}
          </button>
        </form>

        {/* Respuestas rápidas: la demo no depende de que el bot adivine
            texto libre. Cada botón manda el texto exacto que el backend
            y el mock saben interpretar. */}
        <div className="wa-quick" style={{ display: 'flex', flexWrap: 'wrap', gap: 8, padding: 12 }}>
          {(!tramite || tramite.estado === 'COTIZADO') && (
            <button type="button" className="btn btn-secondary" onClick={(e) => handleEnviar(e, 'Sí, quiero elegir clínica')}>
              Sí, elegir clínica
            </button>
          )}
          {tramite?.estado === 'COTIZADO' && (
            <>
              <button type="button" className="btn btn-secondary" onClick={(e) => handleEnviar(e, '1')}>
                1 · Puebla Centro
              </button>
              <button type="button" className="btn btn-secondary" onClick={(e) => handleEnviar(e, '2')}>
                2 · Tehuacán
              </button>
            </>
          )}
          {tramite?.estado === 'CLINICA_SELECCIONADA' && (
            <button type="button" className="btn btn-secondary" onClick={(e) => handleEnviar(e, '2026-11-24')}>
              Confirmar 2026-11-24
            </button>
          )}
        </div>
      </div>

      {tramite && (
        <section className="card" style={{ marginTop: 16 }}>
          <h2>Trámite reconocido</h2>
          <p>
            Folio: <strong>{tramite.folio}</strong> <EstadoBadge estado={tramite.estado} />
          </p>
          <p>
            {tramite.estudio.nombre} — <strong>${tramite.precioCotizado}</strong>
          </p>
          <p className="muted">Paciente: {tramite.paciente?.nombre ?? paciente?.nombre ?? '—'}</p>
        </section>
      )}

      {tramites.length > 0 && (
        <section className="card" style={{ marginTop: 16 }}>
          <h2>Tus folios activos</h2>
          <ul>
            {tramites.map((t) => (
              <li key={t.id} className="dato-row">
                <span>
                  <strong>{t.folio}</strong> · {t.estudio.nombre} — ${t.precioCotizado} <EstadoBadge estado={t.estado} />
                </span>
                <button type="button" className="btn btn-small" onClick={(e) => handleEnviar(e, t.folio)}>
                  Seguir con este folio
                </button>
              </li>
            ))}
          </ul>
          <p className="muted">Pulsa un folio para continuar con ese trámite.</p>
        </section>
      )}
    </div>
  );
}
