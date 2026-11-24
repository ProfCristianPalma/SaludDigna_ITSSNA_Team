# SaludDigna_ITSSNA_Team

HackaTec Nacional 2026 — Reto Salud Digna: **"Orquestación Omnicanal de Atención:
Un paciente, una sola conversación."**

Prototipo que conecta Web, App, WhatsApp, Call Center y Clínica para que el
paciente cambie de canal sin perder contexto ni repetir datos. Un solo trámite,
una sola conversación, con trazabilidad completa.

## Arquitectura en 10 líneas

1. Modular monolith Spring Boot 3.3 + Java 21 + React 18 + Vite + PostgreSQL 16.
2. Módulos por contexto acotado: Identidad, Trámite, Eventos, Catálogo, Canales.
3. Columna vertebral: event log append-only (`eventos`: id, tramite_id, tipo,
   canal, actor, payload, timestamp). Todo cambio de estado es un evento.
4. Máquina de estados: INICIADO → DATOS_PENDIENTES → COTIZADO →
   CLINICA_SELECCIONADA → CONFIRMADO → ATENDIDO.
5. Identidad: teléfono móvil como llave principal (WhatsApp = teléfono),
   email secundario, folio `SD-2026-XXXX` como llave de continuidad.
6. Canales simulados sobre la misma API (Web, WhatsApp, Call Center, App,
   Clínica); si Salud Digna da sandbox real, se conecta el mismo endpoint.
7. Seguridad demo: JWT por rol (Paciente / Asesor / Administrador) con
   fallback `X-Rol` documentado como simplificación de hackatón, no producción.
8. Datos 100% sintéticos: paciente ficticia Ana, catálogo y clínicas inventados.
9. Tablero Patient Journey en tiempo real: etapa actual, canales recorridos,
   línea de tiempo unificada.
10. Diagramas (arquitectura, modelo de datos, secuencia): `docs/arquitectura.md`.

## Cómo correrlo

Requisitos en la PC: Java 21, Maven 3.9+, Node 24, Docker (opcional, para la
opción A). Puertos: backend 8080, frontend 5173, Postgres 5432.

### Opción A — todo con Docker (recomendada para la demo)

```bash
docker compose up --build
# Frontend: http://localhost:5173 — Backend: http://localhost:8080/api/estudios
```

### Opción B — sin Docker, con Postgres local

```bash
# 1. Crea la BD (usuario saluddigna / saluddigna)
createdb -U postgres saluddigna
# 2. Backend
cd backend && mvn spring-boot:run
# 3. Frontend (otra terminal)
cd frontend && npm install && npm run dev   # http://localhost:5173
```

### Opción C — demo rápida sin Postgres (perfil H2 en memoria)

```bash
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=h2
cd frontend && npm install && npm run dev
```

## Credenciales de prueba por rol

| Rol | Usuario | Contraseña | Uso |
|---|---|---|---|
| Paciente | `ana` | `ana123` | Ana García López, tel. +52 222 000 0001, nac. 1990-05-14 |
| Asesor (Call Center) | `asesor` | `asesor123` | Consola `/asesor` |
| Administrador | `admin` | `admin123` | Tablero `/journey` |

Login: `POST /api/auth/login`. En la demo también vale el header
`X-Rol: Paciente | Asesor | Administrador` (simplificación documentada).

Datos semilla: 4 estudios (Ultrasonido abdominal $850, Perfil tiroideo $620,
Radiografía tórax $450, Análisis sangre $380) y 2 clínicas (Clínica Puebla
Centro, Clínica Salud Digna Tehuacán). Hay un trámite demo de Ana en estado
COTIZADO (folio `SD-2026-0001`) para que el journey no llegue vacío.

## Demo de Ana en 5 pasos

1. **Web** (`/`): como Ana, cotiza el Ultrasonido abdominal ($850), elige
   Clínica Puebla Centro y pulsa "Cerrar sin confirmar (simular interrupción)".
   Anota el folio `SD-2026-0001`.
2. **WhatsApp** (`/whatsapp`): escribe "quiero continuar mi trámite". El bot
   reconoce a Ana por su teléfono, recupera el trámite pendiente y le ofrece
   seguir donde se quedó. El evento `canal.cambiado` queda en el log.
3. **Call Center** (`/asesor`): busca por teléfono `+52 222 000 0001` o por
   folio. El asesor ve estudio, clínica, precio y datos pendientes **sin
   pedirle a Ana que repita nada**. Completa el dato pendiente si hace falta.
4. **App** (`/app`): Ana confirma su cita (fecha y hora) y ve la cita
   confirmada en su vista móvil.
5. **Resiliencia + Journey** (`/journey`): refresca o cierra cualquier
   pestaña a mitad del flujo y vuelve a cargar por folio: el trámite aparece
   intacto, reconstruido desde el event log. El tablero muestra la etapa
   actual, los canales recorridos y la línea de tiempo unificada.

Si el backend no responde, el frontend entra en "Modo demo sin backend" con
datos mock locales: la demo nunca se cae en vivo.

## Endpoints principales

| Método | Ruta | Descripción |
|---|---|---|
| POST | `/api/auth/login` | Login demo por rol, devuelve JWT |
| POST | `/api/pacientes` | Registrar paciente |
| GET | `/api/pacientes?telefono=` | Resolver identidad por teléfono (+fechaNacimiento) |
| POST | `/api/tramites` | Crear trámite |
| GET | `/api/tramites/{id}` | Trámite + estado actual |
| GET | `/api/tramites?telefono=` | Trámites de un paciente |
| POST | `/api/tramites/{id}/cotizar` | Cotizar estudio |
| POST | `/api/tramites/{id}/seleccionar-clinica` | Elegir clínica |
| POST | `/api/tramites/{id}/confirmar` | Confirmar cita |
| POST | `/api/tramites/{id}/completar-dato` | Completar dato pendiente |
| POST | `/api/tramites/{id}/eventos` | Registrar evento (append-only) |
| GET | `/api/tramites/{id}/timeline` | Línea de tiempo del trámite |
| GET | `/api/journey/{tramiteId}` | Patient Journey (estado + timeline + siguiente paso) |
| GET | `/api/estudios`, `/api/clinicas` | Catálogo |
| POST | `/api/canales/whatsapp/mensaje` | WhatsApp simulado (resuelve identidad y recupera trámite) |
| POST | `/api/canales/call-center/nota` | Nota del asesor |

Catálogo de eventos: `paciente.registrado`, `tramite.iniciado`,
`cotizacion.creada`, `clinica.seleccionada`, `identidad.verificada`,
`canal.cambiado`, `dato.pendiente.completado`, `cita.confirmada`,
`atencion.concluida`, `tramite.recuperado`.

## Estructura

```
SaludDigna_ITSSNA_Team/
├── backend/    Spring Boot 3.3, Java 21, Maven (ver backend/README.md)
├── frontend/   React 18 + Vite + TypeScript (ver frontend/README.md)
├── docs/arquitectura.md   Diagramas Mermaid
├── docker-compose.yml     postgres:16 + backend + frontend
└── Reto Software Inteligente - SALUD DIGNA.pdf
```

## Notas

- El premio de $30,000 del primer lugar está condicionado a firmar la cesión
  de todos los derechos de PI a Salud Digna A.C. (ver PDF del reto).
- La autenticación demo (`X-Rol`, usuarios `ana`/`asesor`/`admin`) es solo
  para el hackatón; no usar en producción.
