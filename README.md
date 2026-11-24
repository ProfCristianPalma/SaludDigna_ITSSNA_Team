# SaludDigna_ITSSNA_Team

HackaTec Nacional 2026 — Reto Salud Digna: **"Orquestación Omnicanal de Atención:
Un paciente, una sola conversación."**

Prototipo que conecta Web, App, WhatsApp, Call Center y Clínica para que el
paciente cambie de canal sin perder contexto ni repetir datos. Un solo trámite,
una sola conversación, con trazabilidad completa.

## Arquitectura en 10 líneas

1. Modular monolith Spring Boot 3.3 + Java 21 + React 18 + Vite + PostgreSQL 15.
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

## Credenciales de prueba

| Paciente | Teléfono (usuario) | Contraseña | Nac. |
|---|---|---|---|
| Ana García López | `2220000001` | `ana123` | 1990-05-14 |
| Luis Hernández Torres | `2221112233` | `luis123` | 1985-03-22 |
| María Fernanda Ruiz | `2224445566` | `maria123` | 1992-08-09 |
| Carlos Mendoza Vega | `2387778899` | `carlos123` | 1978-11-30 |
| Fernanda López Díaz | `2229990011` | `fernanda123` | 2001-06-17 |

| Rol staff | Usuario | Contraseña | Uso |
|---|---|---|---|
| Asesor (Call Center) | `asesor` | `asesor123` | Consola `/asesor` |
| Administrador | `admin` | `admin123` | Tablero `/journey` |

También vale el primer nombre como usuario de paciente (`ana` + `ana123`).
En la demo también vale el header `X-Rol: Paciente | Asesor | Administrador`
(simplificación documentada).


## Navegación (mapa de rutas)

La app arranca en `/` (Bienvenida). Desde ahí se va a todo lo demás:

| Ruta | Ventana | Qué es |
|---|---|---|
| `/` | Bienvenida | Presentación de Salud Digna y de cada canal; botones a login, registro y cotizar |
| `/cotizar` (alias `/web`) | Web | Cotizar un estudio y crear un folio nuevo |
| `/login` | Iniciar sesión | Ventana separada; tras entrar, va a `/app` |
| `/registro` | Crear cuenta | Ventana separada; tras crearla, entra directo a `/app` |
| `/app` | App | Tus citas (exige sesión, muestra tu nombre y solo tus folios) |
| `/historial` | Mi historial | TODOS tus folios, del más reciente al más viejo |
| `/whatsapp` | WhatsApp | Continuar un trámite con el bot (con tu sesión, arranca con tus datos) |
| `/asesor` | Call Center | Consola del asesor (busca por teléfono, folio o nombre) |
| `/clinica` | Clínica | Recepción: citas confirmadas de TODOS los pacientes |
| `/journey` | Journey | Tu recorrido paso a paso, en tiempo real |

Login y Registro son ventanas separadas y se puede navegar entre ellas
y al inicio: Login tiene "Crear cuenta" y "Volver a inicio"; Registro
tiene "Iniciar sesión" y "Volver a inicio".

## Teléfonos a 10 dígitos, sin +52 (momentáneo)

Todos los teléfonos se guardan y se comparan a **10 dígitos, sin +52**:
`2220000001` en vez de `+52 222 000 0001`. El backend normaliza cada
teléfono que entra (`TelefonoUtil`, misma regla en `api.ts`:
`normalizarTelefono()`): quita espacios, guiones y paréntesis, y el
prefijo +52 / 0052 / 52 solo cuando sobran dígitos. Antes, "+52 ..." y
"222..." eran pacientes distintos en la BD y los folios se iban a un
duplicado: por eso un historial por teléfono podía salir vacío aunque
los folios sí existieran. La migración Flyway
`V5__telefonos_sin_lada.sql` convierte los teléfonos ya guardados
(para bases nuevas, V2/V3 ya siembran a 10 dígitos). Si un paciente
estaba duplicado (+52 y sin +52), V5 solo normaliza la fila más
antigua; revisa la lista en `GET /api/pacientes` y borra el duplicado
sin trámites.

## Login y registro (BD real)

La App (`/app`) exige sesión: ya no se escribe el teléfono a mano ni se
muestra a Ana por defecto. El nombre que sale en "Hola, …" es el del
paciente logueado, y solo se ven **sus** folios.

- **Registro:** `POST /api/auth/registro` con `{nombre, telefono, email,
  fechaNacimiento, password}` (mínimo 6 caracteres). El teléfono es único:
  si ya existe con contraseña devuelve 409. Si la cuenta ya existía por
  haber cotizado en la Web sin registrarse (sin contraseña), el registro la
  completa en vez de bloquearla. Al registrarse hace auto-login y devuelve
  `{token, paciente, rol:"PACIENTE"}`.
- **Login:** `POST /api/auth/login` con `{usuario, password}`. El usuario
  puede ser el **teléfono a 10 dígitos** (`2220000001`) o el **primer nombre**
  (`ana`); el staff usa `asesor` / `admin`. Contraseña incorrecta o
  usuario inexistente → 401. Devuelve `{token, usuario, rol, paciente?}`.
- **Sesión:** `GET /api/auth/yo` con header `Authorization: Bearer <token>`
  devuelve `{paciente, usuario, rol}`. El front guarda el token en
  `localStorage` (`saluddigna-token`) y lo manda en cada llamada; al
  recargar, la sesión se restaura llamando a `/auth/yo`. "Cerrar sesión"
  borra el token y vuelve a `/login`.
- **Contraseñas:** se guardan como hash **PBKDF2WithHmacSHA256** (salt
  aleatorio de 16 bytes, 65536 iteraciones, 256 bits) en
  `pacientes.password_hash` (migración Flyway `V4__password_pacientes.sql`,
  se aplica sola al arrancar, sin `down -v`). El hash **nunca** sale en
  las respuestas JSON (`@JsonIgnore`). Es grado demo para el hackatón, no
  producción. El JWT es HS256, expira en 8 h y lleva el claim `pacienteId`.

### Flujo de prueba

1. En `http://localhost:5173/registro` crea un usuario nuevo (nombre,
   teléfono, fecha de nacimiento, contraseña de 6+ caracteres) → entra
   directo a `/app` y ve "Hola, {su nombre}" sin folios todavía.
2. En la Web (`/cotizar`) cotiza un estudio: el formulario ya viene prellenado
   con sus datos → folio nuevo en "Mis folios".
3. Vuelve a `/app`: su folio aparece ahí, solo el suyo.
4. Cierra sesión y entra como Luis (`2221112233` / `luis123`):
   la App ahora dice "Hola, Luis" y muestra **sus** folios, no los de Ana.

Datos semilla: 4 estudios (Ultrasonido abdominal $850, Perfil tiroideo $620,
Radiografía tórax $450, Análisis sangre $380) y 2 clínicas (Clínica Puebla
Centro, Clínica Salud Digna Tehuacán).

## Usuarios ejemplo (5, en la BD de Postgres y en el mock)

| # | Paciente | Teléfono | Nac. | Estudio | Estado |
|---|---|---|---|---|---|
| 1 | Ana García López | 2220000001 | 1990-05-14 | Ultrasonido abdominal $850 | COTIZADO (folio `SD-2026-0001`) |
| 2 | Luis Hernández Torres | 2221112233 | 1985-03-22 | Perfil tiroideo $620 | CLINICA_SELECCIONADA (Puebla Centro) |
| 3 | María Fernanda Ruiz | 2224445566 | 1992-08-09 | Radiografía tórax $450 | CONFIRMADO (cita 2026-11-24 10:00) |
| 4 | Carlos Mendoza Vega | 2387778899 | 1978-11-30 | Análisis sangre $380 | ATENDIDO |
| 5 | Fernanda López Díaz | 2229990011 | 2001-06-17 | — (pendiente) | DATOS_PENDIENTES (CURP, orden médica) |

Se siembran con Flyway `V2__seed.sql` + `V3__usuarios_ejemplo.sql` y con
`DataSeeder` (idempotente, crea el trámite de cada uno en su estado).
Si ya tenías la base creada, basta con `docker compose down -v` y volver
a levantar para que Flyway aplique V3, o simplemente reinicia el backend:
`DataSeeder` crea los pacientes/trámites que falten en cada arranque.

## Varios folios por paciente

Un paciente puede tener **varios trámites activos a la vez, uno por cada
estudio que elija**. Cada folio (`SD-2026-XXXX`) tiene su propio estudio,
precio, clínica, cita y estado, y todo vive en Postgres: nada se guarda
solo en el navegador. Web, WhatsApp, Call Center, App, Clínica y Journey
leen y escriben sobre los mismos trámites.

- **Cómo se crean:** en la Web, cada Cotizar crea un **folio nuevo** (un
  folio por estudio). Si Ana cotiza Ultrasonido y luego Perfil tiroideo,
  tiene 2 folios distintos. La Web muestra la lista "Mis folios" con todos
  los de ese teléfono.
- **Cómo se busca:** por **teléfono** (`GET /api/tramites?telefono=`),
  por **folio** (`GET /api/tramites/folio/SD-2026-0001`) o por **nombre**
  (`GET /api/pacientes/buscar?nombre=Ana` y luego sus trámites). El
  Journey acepta folio, teléfono o UUID: con teléfono muestra la lista de
  folios para elegir uno.
- **WhatsApp con varios folios:** si el paciente tiene varios trámites
  activos y no dice cuál, el bot **no elige uno al azar**: lista sus folios
  con estudio y estado y pide el folio (ej. `SD-2026-0002`) o `folio 1`,
  `folio 2`. Si el mensaje ya trae el folio, usa ese trámite (y verifica
  que sea de ese teléfono). Solo crea un trámite nuevo cuando el paciente
  no tiene ningún trámite activo. Un número suelto (`1`, `2`) no elige
  folio, porque en un trámite COTIZADO `1` significa "clínica 1".
- **Clínica:** la recepción lista los trámites CONFIRMADOS desde la BD
  (`GET /api/tramites?estado=CONFIRMADO`). El check-in queda en el log de
  eventos y "Registrar atención" llama a `POST /api/tramites/{id}/atender`
  (CONFIRMADO → ATENDIDO); el atendido desaparece de la lista al recargar.
- **Asesor:** busca por teléfono, folio o nombre, ve la lista de folios
  del paciente y elige uno. "Completar dato" guarda en la BD
  (`POST /tramites/{id}/completar-dato`) y "Guardar nota" queda en el log
  (`POST /canales/call-center/nota`); ambas recargan el trámite/timeline.
- **Modo demo sin backend:** el mock solo se usa cuando el backend **no
  responde** (error de red). Si el backend responde 404 (folio o teléfono
  inexistente), la página muestra "no encontrado" en vez de inventar a Ana.

### Probar 2 folios de un mismo usuario

1. En la Web (`/cotizar`), como Ana (2220000001), cotiza Ultrasonido → folio nuevo.
2. Con el mismo teléfono, cotiza Perfil tiroideo → **otro folio nuevo**.
3. En "Mis folios" deben aparecer los 2, cada uno con su estudio y precio.
4. En WhatsApp escribe "hola": el bot lista los 2 folios; responde
   `SD-2026-0002` (o el folio que toque) y continúa solo con ese.
5. En el asesor busca `Ana` o su teléfono: ve los 2 folios y elige uno.
6. En Clínica, tras confirmar una cita, atiende ese folio: solo ese pasa
   a ATENDIDO; el otro sigue en su estado.


## Historial de folios vs Clínica (no son lo mismo)

- **Historial (`/historial`)**: TODOS los folios de UN solo paciente, el
  logueado. Exige sesión, muestra su nombre y teléfono, y cada folio con
  estudio, precio, estado, clínica, fecha de cita y canal de origen,
  del más reciente al más viejo, con enlace a su Journey y, si está
  COTIZADO, botón "Continuar en WhatsApp" que abre `/whatsapp` con ese
  folio. La App (`/app`) muestra lo mismo en formato móvil, con botón
  "Actualizar" y recarga al volver a la página; en Web, "Mis folios"
  enlaza también al Historial.
- **Clínica (`/clinica`)**: recepción con las citas confirmadas de
  TODOS los pacientes. Se carga desde el DTO plano
  `GET /api/clinica/citas?estado=CONFIRMADO` (o `?estado=ATENDIDO`, o
  sin `estado` para todos), que devuelve folio, tramiteId,
  pacienteNombre, pacienteTelefono, estudioNombre, precio,
  clinicaNombre, clinicaCiudad, fechaCita y estado, construido en el
  servicio cargando explícitamente paciente/estudio/clínica: así la
  respuesta nunca depende de serializar una entidad JPA con paciente
  LAZY. La página tiene filtros Confirmados / Atendidos / Todos, un
  buscador por folio, teléfono o nombre, y muestra el error REAL
  (status HTTP + mensaje del backend) si la carga falla. El check-in
  queda en el log de eventos y "Registrar atención" llama a
  `POST /api/tramites/{id}/atender` (CONFIRMADO → ATENDIDO).
- **Por qué Clínica salía vacía antes:** el endpoint viejo
  `GET /api/tramites?estado=` solo existe desde el cambio de
  varios folios; con un backend sin reconstruir tras `git pull`
  devolvía 400 y el front solo mostraba el mensaje genérico.
  (La serialización LAZY no era la causa: `spring.jpa.open-in-view=true`
  está activado.) El DTO es ahora el camino principal por robustez, y
  el endpoint viejo sigue intacto.

### WhatsApp con sesión

Si hay sesión, `/whatsapp` arranca con EL paciente logueado: su nombre,
su teléfono (10 dígitos) y sus folios activos, con botón por folio para
seguir con ese trámite. "Abrir WhatsApp" desde App guarda el teléfono
de sesión en `localStorage` (`saluddigna-wa-telefono`); "Continuar en
WhatsApp" desde Historial guarda además el folio
(`saluddigna-wa-folio`) y el bot continúa con ese folio. Sin sesión,
WhatsApp sigue con Ana de ejemplo como antes. La máquina de estados
del bot no cambió.

## Si ves "Modo demo sin backend"

Ese banner sale cuando el front no llega a `http://localhost:8080/api`.
El front ahora reintenta cada 5 s y tiene botón "Reintentar conexión",
pero si el backend no está arriba, seguirá en mock. Diagnóstico en una
terminal nueva, dentro de la carpeta del proyecto:

```bash
docker compose ps
docker compose logs --tail=100 backend
docker compose logs --tail=50 db
curl http://localhost:8080/api/health
# En PowerShell, si no tienes curl: Invoke-RestMethod http://localhost:8080/api/health
```

- Si `backend` está en `Restarting` o `Exited`, el error está en sus logs.
- Si `db` no está `healthy`, el backend no arranca hasta que Postgres esté listo.
- Si `curl` responde `{"status":"UP"}`, el backend sí está vivo y el banner
  debe desaparecer solo en unos segundos (o pulsa Reintentar).
- Si el puerto 8080 ya está ocupado por otro programa, el backend no puede
  arrancar: para ese programa o cambia el puerto en `docker-compose.yml`.

## Demo de Ana en 5 pasos

1. **Web** (`/cotizar`): como Ana, cotiza el Ultrasonido abdominal ($850), elige
   Clínica Puebla Centro y pulsa "Cerrar sin confirmar (simular interrupción)".
   Anota el folio `SD-2026-0001`.
2. **WhatsApp** (`/whatsapp`): escribe "quiero continuar mi trámite". El bot
   reconoce a Ana por su teléfono, recupera el trámite pendiente y le ofrece
   seguir donde se quedó. El evento `canal.cambiado` queda en el log.
3. **Call Center** (`/asesor`): busca por teléfono `2220000001` o por
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
| POST | `/api/auth/login` | Login paciente (teléfono o primer nombre) o staff, devuelve JWT + paciente |
| POST | `/api/auth/registro` | Registro de paciente en BD + auto-login (409 si el teléfono ya tiene contraseña) |
| GET | `/api/auth/yo` | Perfil de la sesión (Bearer), `{paciente, usuario, rol}` |
| POST | `/api/pacientes` | Registrar paciente |
| GET | `/api/pacientes?telefono=` | Resolver identidad por teléfono (+fechaNacimiento) |
| GET | `/api/pacientes/buscar?nombre=` | Pacientes por nombre (contiene, sin mayúsculas) |
| GET | `/api/pacientes` | Lista de todos los pacientes |
| POST | `/api/tramites` | Crear trámite |
| GET | `/api/tramites/{id}` | Trámite + estado actual (UUID) |
| GET | `/api/tramites/folio/{folio}` | Trámite por folio (ej. SD-2026-0001) |
| GET | `/api/tramites?telefono=` | Trámites de un paciente |
| GET | `/api/tramites?estado=` | Trámites en un estado (ej. CONFIRMADO) |
| GET | `/api/clinica/citas?estado=` | Citas de Clínica como DTOs planos (CONFIRMADO por defecto; ATENDIDO o sin estado = todos) |
| POST | `/api/tramites/{id}/cotizar` | Cotizar estudio |
| POST | `/api/tramites/{id}/seleccionar-clinica` | Elegir clínica |
| POST | `/api/tramites/{id}/confirmar` | Confirmar cita |
| POST | `/api/tramites/{id}/atender` | Registrar atención (CONFIRMADO → ATENDIDO) |
| POST | `/api/tramites/{id}/completar-dato` | Completar dato pendiente |
| POST | `/api/tramites/{id}/eventos` | Registrar evento (append-only) |
| GET | `/api/tramites/{id}/timeline` | Línea de tiempo del trámite |
| GET | `/api/journey/{tramiteId}` | Patient Journey (estado + timeline + siguiente paso) |
| GET | `/api/journey/folio/{folio}` | Patient Journey por folio |
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
├── docker-compose.yml     postgres:15 + backend + frontend
└── Reto Software Inteligente - SALUD DIGNA.pdf
```

## Notas

- El premio de $30,000 del primer lugar está condicionado a firmar la cesión
  de todos los derechos de PI a Salud Digna A.C. (ver PDF del reto).
- La autenticación de pacientes es real contra Postgres (hash PBKDF2 en
  `password_hash`, JWT con `pacienteId`); solo el staff (`asesor`/`admin`)
  sigue siendo de demo en código. El fallback `X-Rol` es solo para el
  hackatón; no usar en producción.
