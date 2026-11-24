# Salud Digna Omnicanal — Frontend

Frontend del HackaTec Nacional 2026 (Salud Digna): **Un paciente, una sola conversación**.
React + Vite + TypeScript, sin dependencias externas de UI.

Canales: Web · WhatsApp · Call Center (Asesor) · App · Clínica · Journey.

## Requisitos

- Node.js 20+
- Backend en `http://localhost:8080/api` (opcional: si no responde, la app usa datos mock locales y muestra el banner **"Modo demo sin backend"**).

## Uso

```bash
npm install
npm run dev
```

Abre http://localhost:5173

```bash
npm run build
```

## Demo

Paciente demo: **Ana García López**, teléfono **+52 222 000 0001**, nacida el **1990-05-14**.
Trámite demo: folio **SD-2026-0001**, estado **COTIZADO** (Ultrasonido abdominal, $850).

Flujo: Web (cotizar) → cerrar sin confirmar → WhatsApp (retomar por teléfono) → Asesor (contexto completo) → App → Clínica (check-in y atención) → Journey (tablero en tiempo real, actualiza cada 5 s).

## Docker

```bash
docker compose up --build
```

El frontend se sirve con nginx en el puerto 80 (mapeado a 5173 en `docker-compose.yml`).
