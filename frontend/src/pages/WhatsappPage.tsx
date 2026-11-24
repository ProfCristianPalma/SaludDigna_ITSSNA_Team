import { useEffect, useRef, useState } from 'react';
import { enviarWhatsapp } from '../api';
import { normalizarCurp } from '../curp';
import { fechaMananaLatina } from '../fechas';
import type { Tramite } from '../types';
import EstadoBadge from '../components/EstadoBadge';

interface MensajeChat {
  id: number;
  de: 'yo' | 'bot';
  texto: string;
  /** Solo en mensajes del bot: false cuando el bot respondió "No entendí". */
  entendido?: boolean;
}

export default function WhatsappPage() {
  // WhatsApp NO toma en cuenta quién inició sesión: el bot trabaja
  // solo con la CURP que se escribe aquí. Sin CURP el bot la pide
  // antes de tocar ningún dato del paciente. Quien escribe la CURP
  // del paciente (una tercera persona) puede continuar su trámite.
  const [identificador, setIdentificador] = useState('');
  const [texto, setTexto] = useState('');
  const [mensajes, setMensajes] = useState<MensajeChat[]>([
    { id: 1, de: 'bot', texto: 'Hola, soy Salud Digna 👋. Para ayudarte necesito tu CURP: escríbela arriba y dime en qué te ayudo. Con tu CURP retomo tus folios donde los dejaste.' },
  ]);
  const [tramite, setTramite] = useState<Tramite | null>(null);
  const [tramites, setTramites] = useState<Tramite[]>([]);
  const [enviando, setEnviando] = useState(false);
  // Folio en contexto: se manda con CADA mensaje para que el bot no
  // tenga que adivinar con cuál de tus folios seguir.
  const [folioActivo, setFolioActivo] = useState<string | null>(null);
  // Respuestas sugeridas que devuelve el bot para el estado actual.
  const [opciones, setOpciones] = useState<string[]>([]);
  const finRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    finRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [mensajes]);

  async function handleEnviar(e: React.FormEvent, textoForzado?: string, sinFolio = false) {
    e.preventDefault();
    const limpio = (textoForzado ?? texto).trim();
    if (!limpio) return;
    const idBase = Date.now();
    setMensajes((prev) => [...prev, { id: idBase, de: 'yo', texto: limpio }]);
    setTexto('');
    setEnviando(true);
    try {
      const res = await enviarWhatsapp(identificador, limpio, sinFolio ? undefined : folioActivo ?? undefined);
      if (res.tramite) {
        setTramite(res.tramite);
        setFolioActivo(res.tramite.folio);
      } else if ((res.tramites ?? []).length > 0) {
        // Respuesta de lista (tramite null, tramites no vacío): el bot
        // ya no sigue un folio en concreto, se limpia el contexto.
        setTramite(null);
        setFolioActivo(null);
      }
      setTramites(res.tramites ?? []);
      setOpciones(res.opciones ?? []);
      setMensajes((prev) => [...prev, { id: idBase + 1, de: 'bot', texto: res.respuesta, entendido: res.entendido }]);
    } catch {
      setMensajes((prev) => [
        ...prev,
        { id: idBase + 1, de: 'bot', texto: 'No pude conectar con el backend, tu mensaje no se envió ni se guardó. Revisa que el backend esté arriba e inténtalo de nuevo.' },
      ]);
    } finally {
      setEnviando(false);
    }
  }

  // "Cambiar de folio": manda "volver" SIN el folio en contexto para
  // que el bot liste los folios, y limpia el folio activo en el front.
  async function handleCambiarFolio(e: React.MouseEvent) {
    e.preventDefault();
    setFolioActivo(null);
    setTramite(null);
    await handleEnviar(e as unknown as React.FormEvent, 'volver', true);
  }

  return (
    <div>
      <h1>WhatsApp — continúa tu trámite</h1>
      <p className="muted">
        El bot te reconoce por tu CURP: escríbela y dime en qué te ayudo. Si tienes varios folios, escribe el folio (por ejemplo SD-2026-0001) o “folio 1” para elegir con cuál seguir. Quien tenga tu CURP puede continuar tu trámite por ti.
      </p>

      <div className="wa-shell">
        <div className="wa-header">
          <span className="wa-avatar" aria-hidden="true">SD</span>
          <div>
            <strong>Salud Digna</strong>
            <small>en línea · responde al instante</small>
          </div>
          {tramite && <EstadoBadge estado={tramite.estado} />}
          {folioActivo && (
            <button
              type="button"
              className="btn btn-small btn-secondary"
              onClick={handleCambiarFolio}
              style={{ marginLeft: 'auto' }}
            >
              Cambiar de folio
            </button>
          )}
        </div>

        <div className="wa-body">
          {mensajes.map((m) => (
            <div key={m.id} className={m.de === 'yo' ? 'bubble bubble-yo' : 'bubble bubble-bot'}>
              {m.texto}
              {m.de === 'bot' && m.entendido === false && (
                <div className="muted" style={{ marginTop: 6, fontSize: '0.85em' }}>
                  ⚠ El bot no entendió ese mensaje.
                </div>
              )}
            </div>
          ))}
          <div ref={finRef} />
        </div>

        <form className="wa-form" onSubmit={handleEnviar}>
          <input
            value={identificador}
            onChange={(e) => setIdentificador(normalizarCurp(e.target.value))}
            placeholder="Escribe la CURP del paciente"
            aria-label="CURP del paciente"
            maxLength={18}
            style={{ textTransform: 'uppercase' }}
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

        {/* Respuestas rápidas: las sugiere el bot para el estado actual
            (campo `opciones` de la respuesta) y cada botón manda ese
            texto exacto CON el folio en contexto. Sin opciones del bot,
            solo "Ayuda" (o la fecha de mañana si falta confirmar la cita).
            Nada hardcodeado: la fecha de mañana se calcula en el front. */}
        <div className="wa-quick" style={{ display: 'flex', flexWrap: 'wrap', gap: 8, padding: 12 }}>
          {opciones.length > 0 ? (
            opciones.map((op) => (
              <button key={op} type="button" className="btn btn-secondary" onClick={(e) => handleEnviar(e, op)}>
                {op}
              </button>
            ))
          ) : tramite?.estado === 'CLINICA_SELECCIONADA' ? (
            <button
              type="button"
              className="btn btn-secondary"
              onClick={(e) => handleEnviar(e, fechaMananaLatina())}
            >
              Confirmar {fechaMananaLatina()}
            </button>
          ) : (
            <button type="button" className="btn btn-secondary" onClick={(e) => handleEnviar(e, 'Ayuda')}>
              Ayuda
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
          <p className="muted">Paciente: {tramite.paciente?.nombre ?? '—'}</p>
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
                <button
                  type="button"
                  className="btn btn-small"
                  onClick={(e) => {
                    setFolioActivo(t.folio);
                    handleEnviar(e, t.folio);
                  }}
                >
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
