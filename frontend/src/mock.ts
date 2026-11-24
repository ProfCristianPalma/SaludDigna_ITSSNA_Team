import type { Clinica, Estudio, Evento, Paciente, Tramite } from './types';

export const MOCK_PACIENTE_ANA: Paciente = {
  id: 'pac-ana-001',
  nombre: 'Ana García López',
  telefono: '2220000001',
  fechaNacimiento: '1990-05-14',
};

/** 5 usuarios ejemplo, igual que en la BD (V2/V3 + DataSeeder). */
export const MOCK_PACIENTES: Paciente[] = [
  MOCK_PACIENTE_ANA,
  { id: 'pac-luis-002', nombre: 'Luis Hernández Torres', telefono: '2221112233', fechaNacimiento: '1985-03-22' },
  { id: 'pac-maria-003', nombre: 'María Fernanda Ruiz', telefono: '2224445566', fechaNacimiento: '1992-08-09' },
  { id: 'pac-carlos-004', nombre: 'Carlos Mendoza Vega', telefono: '2387778899', fechaNacimiento: '1978-11-30' },
  { id: 'pac-fernanda-005', nombre: 'Fernanda López Díaz', telefono: '2229990011', fechaNacimiento: '2001-06-17' },
];

/** Trámite ejemplo por usuario, cada uno en un estado distinto. */
export function mockTramiteParaTelefono(telefono: string): Tramite | null {
  const limpio = telefono.replace(/\D/g, '');
  const idx = MOCK_PACIENTES.findIndex((p) => (p.telefono ?? '').replace(/\D/g, '') === limpio);
  if (idx < 0) return null;
  const paciente = MOCK_PACIENTES[idx];
  const base: Tramite = {
    id: `tram-000${idx + 1}`,
    folio: `SD-2026-000${idx + 1}`,
    estado: 'COTIZADO',
    paciente,
    estudio: MOCK_ESTUDIOS[0],
    clinica: null,
    precioCotizado: 850,
    fechaCita: null,
    canalOrigen: 'WEB',
    datosPendientes: [],
  };
  if (idx === 1) return { ...base, estudio: MOCK_ESTUDIOS[1], precioCotizado: 620, estado: 'CLINICA_SELECCIONADA', clinica: MOCK_CLINICAS[0] };
  if (idx === 2) return { ...base, estudio: MOCK_ESTUDIOS[2], precioCotizado: 450, estado: 'CONFIRMADO', clinica: MOCK_CLINICAS[1], fechaCita: '2026-11-24T10:00:00' };
  if (idx === 3) return { ...base, estudio: MOCK_ESTUDIOS[3], precioCotizado: 380, estado: 'ATENDIDO', clinica: MOCK_CLINICAS[0], fechaCita: '2026-11-23T09:00:00' };
  if (idx === 4) return { ...base, estudio: MOCK_ESTUDIOS[0], precioCotizado: 850, estado: 'DATOS_PENDIENTES', datosPendientes: ['CURP', 'Orden médica'] };
  return base;
}

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
      mensaje: 'Ana se registró en la web con su teléfono 2220000001.',
      payload: { paciente: 'Ana García López', telefono: '2220000001' },
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
