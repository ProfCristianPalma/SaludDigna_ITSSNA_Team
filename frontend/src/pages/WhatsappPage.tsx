import { useEffect, useRef, useState } from 'react';
import { enviarWhatsapp } from '../api';
import { MOCK_PACIENTE_ANA } from '../mock';
import type { Tramite } from '../types';
import EstadoBadge from '../components/EstadoBadge';

interface MensajeChat {
  id: number;
  de: 'yo' | 'bot';
  texto: string;
}

export default function WhatsappPage() {
  const [telefono, setTelefono] = useState(MOCK_PACIENTE_ANA.telefono);
  const [texto, setTexto] = useState('');
  const [mensajes, setMensajes] = useState<MensajeChat[]>([
    { id: 1, de: 'bot', texto: 'Hola, soy Salud Digna 👋. ¿En qué te ayudo hoy? Si ya empezaste un trámite en la web, dime tu teléfono o tu folio y lo retomamos donde lo dejaste.' },
  ]);
  const [tramite, setTramite] = useState<Tramite | null>(null);
  const [enviando, setEnviando] = useState(false);
  const finRef = useRef<HTMLDivElement>(null);

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
      setMensajes((prev) => [...prev, { id: idBase + 1, de: 'bot', texto: res.respuesta }]);
    } catch {
      setMensajes((prev) => [
        ...prev,
        { id: idBase + 1, de: 'bot', texto: 'No pude conectar en este momento, pero tu mensaje quedó registrado. Inténtalo de nuevo en un momento.' },
      ]);
    } finally {
      setEnviando(false);
    }
  }

  return (
    <div>
      <h1>WhatsApp — continúa tu trámite</h1>
      <p className="muted">El bot reconoce tu trámite pendiente por teléfono y te ofrece continuar sin repetir datos.</p>

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
            placeholder="Tu teléfono"
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
          <p className="muted">Paciente: {tramite.paciente?.nombre ?? MOCK_PACIENTE_ANA.nombre}</p>
        </section>
      )}
    </div>
  );
}
