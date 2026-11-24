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
5. Identidad: CURP como llave principal (única, V7), teléfono opcional,
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

| Paciente | CURP (usuario) | Contraseña | Nac. |
|---|---|---|---|
| Ana García López | `GALA900514MDFRPN00` | `ana123` | 1990-05-14 |
| Luis Hernández Torres | `HETL850322HDFRRS01` | `luis123` | 1985-03-22 |
| María Fernanda Ruiz | `RUXF920809MDFZXR02` | `maria123` | 1992-08-09 |
| Carlos Mendoza Vega | `MEVC781130HDFNGR03` | `carlos123` | 1978-11-30 |
| Fernanda López Díaz | `LODF010617MDFPZR04` | `fernanda123` | 2001-06-17 |
| Jorge Alberto Mendoza Ruiz | `MERJ880315HDFNZR00` | `jorge123` | 1988-03-15 |
| Sofía Isabel Herrera Gómez | `HEGS950722MDFRMF00` | `sofia123` | 1995-07-22 |
| Diego Alejandro Torres Sánchez | `TOSD900108HDFRNG00` | `diego123` | 1990-01-08 |
| Valeria Nicole Ortiz Medina | `ORMV981130MDFTDL00` | `valeria123` | 1998-11-30 |
| Ricardo Emmanuel Vargas León | `VALR830605HDFRNC00` | `ricardo123` | 1983-06-05 |
| Camila Fernanda Reyes Morales | `REMC970214MDFYRM00` | `camila123` | 1997-02-14 |
| Andrés Felipe Cruz Navarro | `CUNA910919HDFRVN00` | `andres123` | 1991-09-19 |
| Mariana Guadalupe Flores Díaz | `FODM930427MDFLZR00` | `mariana123` | 1993-04-27 |
| Miguel Ángel Soto Ramírez | `SORM861203HDFTMG00` | `miguel123` | 1986-12-03 |
| Daniela Alejandra Vega Castillo | `VECD990816MDFGSN00` | `daniela123` | 1999-08-16 |
| Óscar Eduardo Peña Jiménez | `PEJO800525HDFXMS00` | `oscar123` | 1980-05-25 |
| Paola Fernanda Luna Herrera | `LUHP021011MDFNRL00` | `paola123` | 2002-10-11 |
| Javier Alejandro Méndez Ortiz | `MEOJ890707HDFNRV00` | `javier123` | 1989-07-07 |
| Lucía Fernanda Aguilar Ramos | `AURL960321MDFGMC00` | `lucia123` | 1996-03-21 |
| Eduardo José Salazar Peña | `SAPE840909HDFLXD00` | `eduardo123` | 1984-09-09 |

Las CURPs son **de demostración** (formato válido, derivadas del nombre,
la fecha de nacimiento, el sexo y la entidad DF; no son CURPs reales de
ninguna persona). El login vale solo con la CURP.

| Rol staff | Usuario | Contraseña | Uso |
|---|---|---|---|
| Asesor (Call Center) | `asesor` | `asesor123` | Consola `/asesor` |
| Administrador | `admin` | `admin123` | Clínica, Asesor y `/admin/usuarios` |

En la demo también vale el header `X-Rol: Paciente | Asesor | Administrador`
(simplificación documentada).


## Navegación (mapa de rutas)

La app arranca en `/` (Bienvenida). Desde ahí se va a todo lo demás:

| Ruta | Ventana | Qué es |
|---|---|---|
| `/` | Bienvenida | Solo presentación de Salud Digna y acciones de sesión (sin tarjetas de canales ni botón de cotizar; para cotizar está el enlace "Cotizar" del menú). Sin sesión: botones a login y registro. Con sesión: "Continuar como {nombre}", "Ver mi historial" y "Cerrar sesión" |
| `/cotizar` (alias `/web`) | Web | Cotizar un estudio y crear un folio nuevo. Sin fecha de nacimiento: con sesión los datos vienen de la sesión (no editables); sin sesión se piden nombre y CURP (obligatoria), el teléfono es opcional |
| `/login` | Iniciar sesión | Ventana separada; tras entrar, va a `/app` (paciente) o a `/asesor` (asesor/admin) |
| `/registro` | Crear cuenta | Ventana separada; tras crearla, entra directo a `/app` |
| `/app` | App | Tus citas (exige sesión, muestra tu nombre y solo tus folios) |
| `/historial` | Mi historial | TODOS tus folios, del más reciente al más viejo |
| `/whatsapp` | WhatsApp | Continuar un trámite con el bot: no usa la sesión, siempre pide la CURP del paciente antes de tocar ningún dato (una tercera persona puede continuar el trámite con la CURP) |
| `/asesor` | Call Center | Consola del asesor (busca por CURP, teléfono, folio o nombre) — **solo personal** |
| `/clinica` | Clínica | Recepción: citas confirmadas de TODOS los pacientes — **solo personal** |
| `/journey` | Journey | Tu recorrido paso a paso, en tiempo real |
| `/admin/usuarios` | Administración de usuarios | **Solo por URL** (sin botón ni enlace) y **solo personal**: listado de usuarios con CRUD real contra Postgres |

Login y Registro son ventanas separadas y se puede navegar entre ellas
y al inicio: Login tiene "Crear cuenta" y "Volver a inicio"; Registro
tiene "Iniciar sesión" y "Volver a inicio".

Con sesión iniciada, "Iniciar sesión" y "Crear cuenta" ya NO se ven
(ni en el menú ni en la bienvenida): en su lugar sale "Hola, {nombre}"
y el botón "Cerrar sesión". La pestaña "Inicio" del menú tampoco se
ve con sesión (el logo sigue enlazando a "/").

## Vistas de personal: Clínica, Asesor y Admin

`/clinica`, `/asesor` y `/admin/usuarios` son vistas de personal: solo
las ve quien entró como asesor (`asesor` / `asesor123`) o admin
(`admin` / `admin123`). Sin sesión, la página pide el login de
personal y **no carga ningún dato** (ni citas ni pacientes ni
usuarios); con sesión de paciente, avisa que es vista de personal y
enlaza a la App y al Historial. La App (`/app`) y el Historial
(`/historial`) son al revés: solo de paciente.

El control es a nivel de front en este demo: las API no exigen rol
todavía, así que quien conozca la URL de un endpoint podría llamarlo
directo. Para el hackatón el candado está en la página, no en la API.
Los enlaces a Asesor y Clínica tampoco aparecen en el menú si no hay
sesión de personal: solo se ven con rol asesor o admin.

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
(V2/V3 siembran con +52 como antes y V5 los normaliza a 10 dígitos al
arrancar). Si un paciente
estaba duplicado (+52 y sin +52), V5 solo normaliza la fila más
antigua; revisa la lista en `GET /api/pacientes` y borra el duplicado
sin trámites.

> **Nunca edites una migración Flyway ya aplicada** (V1, V2, V3…):
> Flyway valida el checksum de cada migración aplicada en la BD y
> rechaza el arranque si cambió. Los cambios de esquema o datos van
> siempre en una migración nueva (V6, V7…), como hizo V5 con los
> teléfonos.

## CURP como llave omnicanal (V7)

La **CURP** es la llave principal del paciente (`CurpUtil`: normaliza a
mayúsculas sin espacios y valida formato `^[A-Z]{4}\d{6}[HM][A-Z]{2}[A-Z]{3}[A-Z\d]\d$`,
fecha real en los 6 dígitos AAMMDD y código de entidad válido). El
**teléfono es opcional** desde V7: un paciente puede registrarse solo con
CURP, y la BD ya no impone teléfono único ni NOT NULL (la unicidad del
teléfono, cuando hay, la sigue imponiendo el servicio).

- **Login:** `POST /api/auth/login` acepta **solo la CURP** para pacientes (cualquier otro identificador → 401 genérico). El staff entra con usuario + contraseña, sin cambios.
- **Registro:** `POST /api/auth/registro` exige CURP; teléfono opcional.
- **Búsqueda:** `GET /api/pacientes?curp=` y `GET /api/pacientes/{id}/tramites`
  (trámites del paciente como mapas planos, igual que el journey).
- **Editar:** `PUT /api/pacientes/{id}` acepta `curp` (valida formato y
  unicidad; vacía no la borra) y `telefono: ""` **borra** el teléfono;
  `telefono` ausente no lo toca.
- **WhatsApp:** `POST /api/canales/whatsapp/mensaje` con `{curp?, mensaje, folio?}` (el campo `telefono` se eliminó). Sin CURP el bot la pide antes de tocar ningún dato del paciente; un identificador no registrado se registra en el chat con nombre + CURP + fecha DD/MM/AAAA + contraseña. El bot puede completar la reservación entera (estudio → clínica → fecha y hora): si la hora está ocupada lo dice y ofrece las horas libres cercanas.
- Las CURPs semilla son de demostración (ver tabla de credenciales).

## Fechas en formato latinoamericano DD/MM/AAAA

Todas las fechas que ve, escribe o recibe el usuario son **DD/MM/AAAA**:
la fecha de cita se muestra como `24/11/2026 10:00`, la fecha de
nacimiento se escribe como `14/05/1990` (con su placeholder
`DD/MM/AAAA` y validación de fecha real: 29/02 solo en año bisiesto),
y el bot de WhatsApp pide la fecha así (`por ejemplo 24/11/2026`).
Por dentro, la API y Postgres siguen guardando ISO (`AAAA-MM-DD`):
la conversión vive en `frontend/src/fechas.ts`
(`formatearFecha`, `formatearFechaHora`, `parsearFechaLatina`) y en el
bot (`CanalController.extraerFecha`, que acepta DD/MM/AAAA y también
AAAA-MM-DD por compatibilidad). El timeline muestra día y hora como
`24/11/2026 10:00`.

## Login y registro (BD real)

La App (`/app`) exige sesión: ya no se escribe el teléfono a mano ni se
muestra a Ana por defecto. El nombre que sale en "Hola, …" es el del
paciente logueado, y solo se ven **sus** folios.

- **Registro:** `POST /api/auth/registro` con `{nombre, curp, telefono,
  email, fechaNacimiento, password}` (mínimo 6 caracteres). La **CURP es
  obligatoria** (formato válido, fecha real y entidad válida; si no viene
  la fecha de nacimiento se deriva de la CURP, y si viene y no coincide
  con ella devuelve 400); el **teléfono es opcional** y el email es
  obligatorio. La CURP es única: si ya existe con contraseña devuelve
  409. Si la cuenta ya existía por haber cotizado en la Web sin
  registrarse (sin contraseña), el registro la completa en vez de
  bloquearla. Al registrarse hace auto-login y devuelve
  `{token, paciente, rol:"PACIENTE"}`.
- **Login:** `POST /api/auth/login` con `{usuario, password}`. El usuario
  es la **CURP** (`GALA900514MDFRPN00`) para pacientes; el staff usa `asesor` /
  `admin` / `doctor`. Contraseña incorrecta o usuario inexistente → 401. Devuelve
  `{token, usuario, rol, paciente?}`. El `usuario` de la sesión (y el
  subject del JWT) es la CURP del paciente.
- **Sesión:** `GET /api/auth/yo` con header `Authorization: Bearer <token>`
  devuelve `{paciente, usuario, rol}`. El front guarda el token en
  **`sessionStorage`** (`saluddigna-token`) y lo manda en cada llamada.
  La sesión dura lo que dura la pestaña: al cerrarla o abrir una pestaña
  nueva, la app arranca limpia, sin ningún usuario cargado. "Iniciar
  sesión" y "Crear cuenta" están siempre visibles en la barra y en la
  bienvenida; con sesión activa aparece además "Continuar como {nombre}"
  y un botón **Cerrar sesión** bien visible (en la barra, en la
  bienvenida y en la App). "Cerrar sesión" borra el token (y cualquier
  resto viejo en `localStorage`) y vuelve a `/`.
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

Datos semilla: 6 estudios (Ultrasonido abdominal $850, Perfil tiroideo $620,
Radiografía tórax $450, Análisis sangre $380, Electrocardiograma $450,
Mastografía $950) y 2 clínicas (Clínica Puebla Centro, Clínica Salud Digna
Tehuacán).

## Persistencia: nada volátil

Usuarios y folios viven en **Postgres** (volumen Docker `pgdata` en
`docker-compose.yml`): sobreviven a reinicios del backend y a
`docker compose down` normal. **Solo `docker compose down -v` borra la
base de datos** (borra el volumen). El registro guarda al paciente en
la BD con su hash PBKDF2, y cada folio es un trámite en la BD: nada se
guarda solo en el navegador ni en un mapa en memoria.

Si el backend no responde, las escrituras (cotizar, crear folio,
elegir clínica, confirmar, WhatsApp, completar dato, atender) **no
inventan datos**: lanzan el error y la página avisa que no se creó ni
se guardó nada. Lo mismo en las lecturas de Historial, Journey,
Clínica y pacientes: muestran el error real, no datos de ejemplo. Solo
el catálogo (estudios y clínicas) sigue mostrando datos locales con el
banner "Modo demo sin backend" mientras el backend no responde.

## Administración de usuarios (solo por URL)

`/admin/usuarios` es una herramienta demo de administración: **solo se
entra escribiendo la URL**, no hay botón, no hay enlace desde ninguna
página y no se menciona en la bienvenida. Además es vista de personal:
pide sesión de asesor o admin, como Clínica y Asesor (ver
"Vistas de personal").

- **Lista:** todos los usuarios de Postgres con su cantidad de folios,
  buscador por nombre o teléfono.
- **Crear:** nombre, CURP (obligatoria), teléfono opcional, email, fecha
  de nacimiento y contraseña (mínimo 6, hash PBKDF2). La contraseña
  recién creada se muestra **una sola vez** ("Anótala, no se volverá a
  mostrar"): las guardadas son hash y no se pueden volver a ver.
- **Editar:** nombre, CURP, teléfono, email y fecha; la contraseña solo se
  cambia si se escribe una nueva (también se muestra una sola vez).
- **Restablecer contraseña:** botón por fila (pacientes y staff) con input
  + ojo + "Generar" (12 caracteres legibles): pacientes por
  `POST /api/pacientes/{id}/restablecer-password` (la nueva se devuelve
  solo en esa respuesta), staff por `PUT /api/admin/staff/{id}`.
- **Borrar:** con confirmación que dice cuántos folios se borrarán.
  Borra en cascada y en la misma transacción al usuario, sus folios
  (trámites), sus citas y sus eventos: `tramites.paciente_id` es una FK
  sin `ON DELETE CASCADE` y `citas.tramite_id` también referencia a
  `tramites`, así que borrar a secas fallaría por violación de FK; y los
  eventos de esos trámites quedarían huérfanos (su `tramite_id` no tiene
  FK). Si algo falla, todo se revierte.

Endpoints: `GET /api/admin/usuarios`, `POST /api/admin/usuarios`,
`PUT /api/admin/usuarios/{id}`, `DELETE /api/admin/usuarios/{id}`
(responde `{"foliosEliminados": N}`).

### Personal — cuentas de acceso (tabla usuarios_staff, V6)

En la misma página `/admin/usuarios`, segunda sección, va el CRUD de
las cuentas del personal (asesor y admin). Antes vivían hardcodeadas
en `AuthController` (contraseñas en texto plano en el código, fuera de
la BD y sin CRUD); ahora viven en Postgres y el login las consulta
ahí (usuario en minúsculas, hash PBKDF2, rol desde la BD).

- **Siembra inicial:** DataSeeder crea `admin` (Administrador,
  `admin123`, ADMINISTRADOR) y `asesor` (Asesor Call Center,
  `asesor123`, ASESOR) si no existen, con hash PBKDF2. Si ya existen,
  no se pisan.
- **Lista:** usuario, nombre, rol y fecha de creación. La contraseña
  **nunca se muestra ni se devuelve por la API**: el DTO no lleva
  `password_hash`.
- **Crear:** nombre, usuario único, contraseña (mínimo 6, hash PBKDF2)
  y rol ASESOR o ADMINISTRADOR. 409 si el usuario ya existe.
- **Editar:** nombre, usuario y rol; la contraseña solo se cambia si
  se escribe una nueva. 409 si el usuario choca con otra cuenta.
- **Borrar:** con confirmación. 409 si es el último ADMINISTRADOR o
  si es tu propia cuenta (la del JWT).

Endpoints: `GET /api/admin/staff`, `POST /api/admin/staff`,
`PUT /api/admin/staff/{id}`, `DELETE /api/admin/staff/{id}`.

## Usuarios ejemplo (20, en la BD de Postgres y en el mock)

| # | Paciente | CURP | Teléfono | Nac. | Estudio | Estado |
|---|---|---|---|---|---|---|
| 1 | Ana García López | `GALA900514MDFRPN00` | 2220000001 | 14/05/1990 | Ultrasonido abdominal $850 | COTIZADO (folio `SD-2026-0001`) |
| 2 | Luis Hernández Torres | `HETL850322HDFRRS01` | 2221112233 | 22/03/1985 | Perfil tiroideo $620 | CLINICA_SELECCIONADA (Puebla Centro) |
| 3 | María Fernanda Ruiz | `RUXF920809MDFZXR02` | 2224445566 | 09/08/1992 | Radiografía tórax $450 | CONFIRMADO (cita mañana 10:00, Tehuacán) |
| 4 | Carlos Mendoza Vega | `MEVC781130HDFNGR03` | 2387778899 | 30/11/1978 | Análisis sangre $380 | ATENDIDO (cita mañana 09:00, Puebla) |
| 5 | Fernanda López Díaz | `LODF010617MDFPZR04` | 2229990011 | 17/06/2001 | — (pendiente) | DATOS_PENDIENTES (CURP, orden médica) |
| 6 | Jorge Alberto Mendoza Ruiz | `MERJ880315HDFNZR00` | 2223334455 | 15/03/1988 | Ultrasonido abdominal $850 | CONFIRMADO (cita mañana 08:00, Puebla) |
| 7 | Sofía Isabel Herrera Gómez | `HEGS950722MDFRMF00` | 2224446677 | 22/07/1995 | Perfil tiroideo $620 | CONFIRMADO (cita mañana 09:30, Puebla) |
| 8 | Diego Alejandro Torres Sánchez | `TOSD900108HDFRNG00` | 2225557788 | 08/01/1990 | Radiografía tórax $450 | CONFIRMADO (cita mañana 08:30, Tehuacán) |
| 9 | Valeria Nicole Ortiz Medina | `ORMV981130MDFTDL00` | 2226668899 | 30/11/1998 | Análisis sangre $380 | CONFIRMADO (cita mañana 11:00, Tehuacán) |
| 10 | Ricardo Emmanuel Vargas León | `VALR830605HDFRNC00` | 2227779900 | 05/06/1983 | Radiografía tórax $450 | CONFIRMADO (cita mañana 14:00, Puebla) |
| 11 | Camila Fernanda Reyes Morales | `REMC970214MDFYRM00` | 2228880011 | 14/02/1997 | Ultrasonido abdominal $850 | CONFIRMADO (cita +2 días 09:00, Tehuacán) |
| 12 | Andrés Felipe Cruz Navarro | `CUNA910919HDFRVN00` | 2229991122 | 19/09/1991 | Análisis sangre $380 | CONFIRMADO (cita +2 días 10:00, Puebla) |
| 13 | Mariana Guadalupe Flores Díaz | `FODM930427MDFLZR00` | 2221113344 | 27/04/1993 | Perfil tiroideo $620 | CONFIRMADO (cita +2 días 15:30, Tehuacán) |
| 14 | Miguel Ángel Soto Ramírez | `SORM861203HDFTMG00` | 2222224455 | 03/12/1986 | Ultrasonido abdominal $850 | CONFIRMADO (cita +3 días 10:00, Puebla) |
| 15 | Daniela Alejandra Vega Castillo | `VECD990816MDFGSN00` | 2223335566 | 16/08/1999 | Radiografía tórax $450 | CONFIRMADO (cita +3 días 10:30, Tehuacán) |
| 16 | Óscar Eduardo Peña Jiménez | `PEJO800525HDFXMS00` | 2224446678 | 25/05/1980 | Análisis sangre $380 | CONFIRMADO (cita +3 días 16:00, Puebla) |
| 17 | Paola Fernanda Luna Herrera | `LUHP021011MDFNRL00` | 2225557789 | 11/10/2002 | Perfil tiroideo $620 | CONFIRMADO (cita +3 días 12:00, Tehuacán) |
| 18 | Javier Alejandro Méndez Ortiz | `MEOJ890707HDFNRV00` | 2226668890 | 07/07/1989 | Radiografía tórax $450 | CONFIRMADO (cita +4 días 11:30, Puebla) |
| 19 | Lucía Fernanda Aguilar Ramos | `AURL960321MDFGMC00` | 2227779901 | 21/03/1996 | Análisis sangre $380 | CONFIRMADO (cita +4 días 09:30, Tehuacán) |
| 20 | Eduardo José Salazar Peña | `SAPE840909HDFLXD00` | 2228880012 | 09/09/1984 | Perfil tiroideo $620 | CONFIRMADO (cita +5 días 08:30, Puebla) |

CURPs de demostración (formato válido, no reales; ver "Credenciales de prueba").

Las citas de los usuarios 3 y 6–20 son **relativas al día del primer
arranque** (mañana, +2 días, …): nunca se quedan en el pasado. Son citas
CONFIRMADAS reales en la BD, así que **ocupan su hueco en la parrilla**
para todos los usuarios: en Web, WhatsApp y Asesor esa hora sale
deshabilitada y `confirmar` la rechaza con 409 si se intenta tomar.
La cita de Carlos (usuario 4) quedó ATENDIDA, así que su hueco (mañana
09:00, Puebla) vuelve a estar libre.

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
  folio por estudio), **salvo que el paciente ya tenga un trámite ACTIVO
  de ese estudio**: entonces NO se crea otro. El backend devuelve 409 con
  el folio existente y lo que le falta (clínica / fecha / hora), y la
  Web, el Asesor y WhatsApp ofrecen **retomarlo** para completar solo
  los pasos que faltan sobre ese mismo folio. Repetir un estudio ya
  ATENDIDO, NO_PRESENTADO o NO_REALIZADO sí crea un folio nuevo: es
  historial, no un duplicado. Los duplicados que ya había en la BD de
  las pruebas (p. ej. Ana con 2 folios de Ultrasonido COTIZADO) los
  limpia la migración V9 (conserva el de estado más avanzado y, en
  empate, el más reciente; nunca toca los terminales).
  Si Ana cotiza Ultrasonido y luego Perfil tiroideo,
  tiene 2 folios distintos. La Web muestra la lista "Mis folios" con todos
  los de esa CURP.
- **Cómo se busca:** por **CURP** (`GET /api/pacientes?curp=` y luego sus
  trámites), por **folio** (`GET /api/tramites/folio/SD-2026-0001`) o por **nombre**
  (`GET /api/pacientes/buscar?nombre=Ana` y luego sus trámites). El
  Journey acepta folio, teléfono o UUID: con teléfono muestra la lista de
  folios para elegir uno.
- **WhatsApp es un chatbot con contexto:** el front manda la CURP y el
  folio en contexto con cada mensaje (campos `curp` y `folio`), así que "1" o "25/11/2026"
  se aplican al trámite que se venía siguiendo. Sin CURP el bot la pide
  antes de tocar ningún dato del paciente. El orden para elegir
  trámite es: folio explícito en el texto o en contexto, "folio N" /
  "opción N", la intención por estado (elegir clínica → el COTIZADO;
  una fecha → el CLINICA_SELECCIONADA; un estudio → el INICIADO),
  un solo folio activo, y solo si de verdad no se puede saber, lista
  los folios y pregunta (nunca elige al azar). El bot entiende por
  estado: en COTIZADO elige clínica con "1", "uno", "primera",
  "Puebla", "Tehuacán" o el nombre de la clínica; en
  CLINICA_SELECCIONADA confirma con una fecha y hora DD/MM/AAAA HH:MM
  ("hoy", "mañana" o "pasado mañana" valen; si solo da la fecha, el bot
  ofrece las horas libres de ese día; si la hora está ocupada lo dice y
  ofrece las libres cercanas; fecha pasada o imposible → lo dice y
  vuelve a pedir), y "cambiar clínica" vuelve a COTIZADO; en
  INICIADO pide el estudio por nombre o palabra clave; en CONFIRMADO
  dice cuándo y dónde es la cita (cambiar fecha y cancelar no están
  disponibles por WhatsApp: remite al Call Center); en ATENDIDO ofrece
  cotizar otro estudio. "Ayuda" y "hola" listan lo que puede hacer EN
  ESE ESTADO. Si no entiende, responde "No entendí ..." con el estado
  del folio y las opciones válidas, sin inventar avances. Cada
  respuesta trae `opciones` (botones) y `entendido`. Un "hola" NUNCA
  crea un trámite: solo se crea cuando el usuario pide un estudio
  concreto. Un paciente no registrado se registra
  en el mismo chat con nombre completo + CURP + fecha de nacimiento
  (DD/MM/AAAA) + contraseña (mínimo 6); si la CURP ya existe, se sigue
  con ese paciente.
- **Clínica:** la recepción lista los trámites CONFIRMADOS desde la BD
  (`GET /api/tramites?estado=CONFIRMADO`). El check-in queda en el log de
  eventos y "Registrar atención" llama a `POST /api/tramites/{id}/atender`
  (CONFIRMADO → ATENDIDO); el atendido desaparece de la lista al recargar.
- **Asesor:** busca por CURP, teléfono, folio o nombre, ve la lista de folios
  del paciente y elige uno. "Completar dato" guarda en la BD
  (`POST /tramites/{id}/completar-dato`) y "Guardar nota" queda en el log
  (`POST /canales/call-center/nota`); ambas recargan el trámite/timeline.
- **Modo demo sin backend:** el mock solo se usa cuando el backend **no
  responde** (error de red). Si el backend responde 404 (folio o paciente
  inexistente), la página muestra "no encontrado" en vez de inventar a Ana.

### Probar 2 folios de un mismo usuario

1. En la Web (`/cotizar`), como Ana (CURP `GALA900514MDFRPN00`), cotiza Ultrasonido → folio nuevo.
2. Con la misma CURP, cotiza Perfil tiroideo → **otro folio nuevo**.
3. En "Mis folios" deben aparecer los 2, cada uno con su estudio y precio.
4. En WhatsApp escribe "hola": el bot pide la CURP; escríbela y lista los 2 folios; responde
   `SD-2026-0002` (o el folio que toque) y continúa solo con ese.
5. En el asesor busca `Ana` o su CURP: ve los 2 folios y elige uno.
6. En Clínica, tras confirmar una cita, atiende ese folio: solo ese pasa
   a ATENDIDO; el otro sigue en su estado.


## Historial de folios vs Clínica (no son lo mismo)

- **Historial (`/historial`)**: TODOS los folios de UN solo paciente, el
  logueado. Exige sesión, muestra su nombre y su CURP, y cada folio con
  estudio, precio, estado, clínica, fecha de cita y canal de origen,
  del más reciente al más viejo, con enlace a su Journey y, si está
  COTIZADO, botón "Continuar en WhatsApp" que abre `/whatsapp` (el bot
  pide la CURP al entrar). Cada tarjeta muestra además "Pasos realizados" y "Pasos
  faltantes" según el estado del folio (INICIADO → COTIZADO → Clínica
  elegida → Cita confirmada → Atención en clínica), más los datos
  pendientes si los tiene. La App (`/app`) muestra lo mismo en formato
  móvil, con botón "Actualizar" y recarga al volver a la página; en Web, "Mis folios"
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

### Journey: datos planos, no entidades JPA

`GET /api/journey/{id}` y `GET /api/journey/folio/{folio}` devuelven
el journey como datos planos (mapas y listas, armados en
`TramiteService.obtenerJourney` dentro de la transacción), nunca
entidades JPA: antes se devolvían el `Tramite` y la `Cita` como
entidades, con sus relaciones LAZY, y la serialización Jackson del mapa
fallaba con 500, así que el front mostraba "No se encontró el trámite"
para CUALQUIER folio aunque existiera. Si el endpoint de journey
falla, el front lo compone con `GET /api/tramites/folio/{folio}` +
`GET /api/tramites/{id}/timeline` (datos reales, no mock), y la
página muestra el error REAL (HTTP + mensaje) en vez del genérico.

### Cotizar sin fecha de nacimiento y sin falsos negativos

El formulario de cotizar ya no pide fecha de nacimiento: con sesión
los datos (nombre, CURP, teléfono) vienen de la sesión y no se
muestran editables; sin sesión se cotiza solo con nombre y CURP
(obligatoria), el teléfono es opcional (la fecha es nullable en la BD
y el paciente se resuelve por CURP). Las escrituras del flujo de cotizar usan timeout de 15 s (antes 4 s):
con el backend frío tras arrancar Docker, la respuesta tardaba más de
4 s, el cliente abortaba y el servidor igual creaba el folio, así que
el front decía que no se creó nada aunque el folio sí existía (falso
negativo). Si aun así falla la respuesta, la página vuelve a consultar
los folios de la CURP y, si el folio sí se creó, lo muestra en el
panel derecho como creado, con una nota de que la respuesta tardó.

### WhatsApp sin sesión, con la CURP

`/whatsapp` **no** usa la sesión: siempre pide la CURP del paciente
antes de tocar ningún dato. Una tercera persona puede continuar el
trámite del paciente con su CURP. Los botones "Abrir WhatsApp" (App) y
"Continuar en WhatsApp" (Historial) solo navegan a `/whatsapp`; el bot
pide la CURP al entrar. La máquina de estados del bot no cambió,
salvo que ahora puede completar la reservación entera (estudio →
clínica → fecha y hora) y que una fecha sin hora hace que ofrezca las
horas libres de ese día.

## Si ves "Modo demo sin backend"

Ese banner sale cuando el front no llega a `http://localhost:8080/api`.
El front ahora reintenta cada 5 s y tiene botón "Reintentar conexión".
Mientras el backend no responda, solo el catálogo (estudios y clínicas)
muestra datos locales; las escrituras y las lecturas de Historial,
Journey, Clínica y pacientes muestran el error y no inventan datos.
Diagnóstico en una terminal nueva, dentro de la carpeta del proyecto:

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

1. **Web** (`/cotizar`): como Ana (CURP `GALA900514MDFRPN00`), cotiza el Ultrasonido abdominal ($850), elige
   Clínica Puebla Centro y pulsa "Cerrar sin confirmar (simular interrupción)".
   Anota el folio `SD-2026-0001`.
2. **WhatsApp** (`/whatsapp`): escribe "hola", el bot pide la CURP; escribe la CURP de Ana
   `GALA900514MDFRPN00` y luego "quiero continuar mi trámite". El bot reconoce a Ana por su CURP,
   recupera el trámite pendiente y le ofrece seguir donde se quedó. El
   evento `canal.cambiado` queda en el log.
3. **Call Center** (`/asesor`): busca por CURP `GALA900514MDFRPN00` o por
   folio. El asesor ve estudio, clínica, precio y datos pendientes **sin
   pedirle a Ana que repita nada**. Completa el dato pendiente si hace falta.
4. **App** (`/app`): Ana confirma su cita (fecha y hora) y ve la cita
   confirmada en su vista móvil.
5. **Resiliencia + Journey** (`/journey`): refresca o cierra cualquier
   pestaña a mitad del flujo y vuelve a cargar por folio: el trámite aparece
   intacto, reconstruido desde el event log. El tablero muestra la etapa
   actual, los canales recorridos y la línea de tiempo unificada.

Si el backend no responde, el frontend muestra el banner "Modo demo sin
backend" y solo el catálogo usa datos locales: las escrituras y las
lecturas de trámites no inventan datos, avisan que no se guardó nada.

## Endpoints principales

| Método | Ruta | Descripción |
|---|---|---|
| POST | `/api/auth/login` | Login paciente (solo CURP) o staff, devuelve JWT + paciente |
| POST | `/api/auth/registro` | Registro de paciente en BD + auto-login (CURP obligatoria, teléfono opcional; 409 si la CURP ya tiene contraseña) |
| GET | `/api/auth/yo` | Perfil de la sesión (Bearer), `{paciente, usuario, rol}` |
| POST | `/api/pacientes` | Registrar paciente |
| GET | `/api/pacientes?telefono=` | Resolver identidad por teléfono (+fechaNacimiento) |
| GET | `/api/pacientes?curp=` | Resolver identidad por CURP |
| GET | `/api/pacientes/{id}/tramites` | Trámites del paciente (mapas planos, como el journey) |
| GET | `/api/pacientes/buscar?nombre=` | Pacientes por nombre (contiene, sin mayúsculas) |
| GET | `/api/pacientes` | Lista de todos los pacientes |
| PUT | `/api/pacientes/{id}` | Editar paciente (acepta `curp`; `telefono: ""` borra el teléfono) |
| GET | `/api/admin/usuarios` | Usuarios con cantidad de folios (solo por URL en el front) |
| POST | `/api/admin/usuarios` | Crear usuario (409 si la CURP ya existe) |
| PUT | `/api/admin/usuarios/{id}` | Editar usuario (la contraseña solo cambia si se envía una nueva) |
| DELETE | `/api/admin/usuarios/{id}` | Borrar usuario con sus folios, citas y eventos (`{"foliosEliminados": N}`) |
| GET | `/api/admin/staff` | Cuentas del personal, sin password_hash (solo por URL en el front) |
| POST | `/api/admin/staff` | Crear cuenta de personal (409 si el usuario ya existe) |
| PUT | `/api/admin/staff/{id}` | Editar cuenta (la contraseña solo cambia si se envía una nueva) |
| DELETE | `/api/admin/staff/{id}` | Borrar cuenta (409 si es el último admin o tu propia cuenta) |
| POST | `/api/pacientes/{id}/restablecer-password` | Restablecer contraseña de paciente (asesor/admin; body opcional `{"password"}`; responde `{"nuevaPassword"}` solo en esa respuesta) |
| POST | `/api/tramites` | Crear trámite (acepta `curp` opcional) |
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
| GET | `/api/disponibilidad/parrilla?clinicaId&fecha&estudioId?&exceptoTramiteId?` | Parrilla de horarios 07:00–16:30 (slots de 30 min, con ocupados, según solape real) |
| GET | `/api/estudios`, `/api/clinicas` | Catálogo |
| POST | `/api/canales/whatsapp/mensaje` | WhatsApp simulado (resuelve por CURP y recupera trámite; sin CURP la pide) |
| POST | `/api/canales/call-center/nota` | Nota del asesor |

Catálogo de eventos: `paciente.registrado`, `tramite.iniciado`,
`cotizacion.creada`, `clinica.seleccionada`, `identidad.verificada`,
`canal.cambiado`, `dato.pendiente.completado`, `cita.confirmada`,
`atencion.concluida`, `tramite.recuperado`, `password.restablecida`.

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
- La autenticación es real contra Postgres (hash PBKDF2 en
  `password_hash`, JWT con `pacienteId` para pacientes y con rol para
  el staff, tabla `usuarios_staff` sembrada por DataSeeder). El
  fallback `X-Rol` es solo para el hackatón; no usar en producción.
