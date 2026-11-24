import type { Clinica, Estudio, Evento, Paciente, Tramite } from './types';

export const MOCK_PACIENTE_ANA: Paciente = {
  id: 'pac-ana-001',
  nombre: 'Ana García López',
  telefono: '+52 222 000 0001',
  fechaNacimiento: '1990-05-14',
};

export const MOCK_ESTUDIOS: Estudio[] = [
  { id: 'est-001', nombre: 'Ultrasonido abdominal', precio: 850, descripcion: 'Ultrasonido abdominal general' },
  { id: 'est-002', nombre: 'Perfil tiroideo', precio: 620, descripcion: 'Perfil tiroideo en sangre' },
  { id: 'est-003', nombre: 'Radiografía tórax', precio: 450, descripcion: 'Radiografía de tórax PA y lateral' },
  { id: 'est-004', nombre: 'Análisis sangre', precio: 380, descripcion: 'Análisis de sangre general' },
];

export const MOCK_CLINICAS: Clinica[] = [
  { id: 'cli-001', nombre: 'Clínica Puebla Centro', ciudad: 'Puebla', direccion: 'Centro, Puebla, Pue.' },
  { id: 'cli-002', nombre: 'Clínica Salud Digna Tehuacán', ciudad: 'Tehuacán', direccion: 'Tehuacán, Puebla' },
];

export function mockTramite(): Tramite {
  return {
    id: 'tram-0001',
    folio: 'SD-2026-0001',
    estado: 'COTIZADO',
    paciente: MOCK_PACIENTE_ANA,
    estudio: MOCK_ESTUDIOS[0],
    clinica: null,
    precioCotizado: 850,
    fechaCita: null,
    canalOrigen: 'WEB',
    datosPendientes: ['Confirmar ayuno de 8 horas', 'Confirmar fecha de cita'],
  };
}

export function mockTimeline(): Evento[] {
  const base = '2026-11-23T09:30:00';
  return [
    {
      id: 'ev-001',
      tipo: 'paciente.registrado',
      canal: 'WEB',
      actor: 'Ana García López',
      timestamp: `${base}`,
      mensaje: 'Ana se registró en la web con su teléfono +52 222 000 0001.',
      payload: { paciente: 'Ana García López', telefono: '+52 222 000 0001' },
    },
    {
      id: 'ev-002',
      tipo: 'tramite.iniciado',
      canal: 'WEB',
      actor: 'Ana García López',
      timestamp: '2026-11-23T09:32:00',
      mensaje: 'Trámite SD-2026-0001 iniciado en la web para Ultrasonido abdominal.',
      payload: { folio: 'SD-2026-0001', estudio: 'Ultrasonido abdominal' },
    },
    {
      id: 'ev-003',
      tipo: 'cotizacion.creada',
      canal: 'WHATSAPP',
      actor: 'Sistema Salud Digna',
      timestamp: '2026-11-23T09:35:00',
      mensaje: 'Cotización creada: Ultrasonido abdominal por $850. Folio SD-2026-0001.',
      payload: { folio: 'SD-2026-0001', precio: 850 },
    },
  ];
}
