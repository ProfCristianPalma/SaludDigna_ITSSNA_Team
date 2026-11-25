# Salud Digna Omnicanal — Un paciente, una sola conversación

Plataforma de atención omnicanal para el reto de software inteligente de
Salud Digna (HackaTec Nacional 2026): un paciente mantiene "un solo
contexto y una sola conversación" aunque cambie de canal. Web, App,
WhatsApp y Call Center trabajan sobre la misma API, la misma
base de datos y el mismo registro de eventos, así que el paciente nunca
repite su historia y cada paso queda trazado.

Stack: **Spring Boot 3.3 / Java 21** (monolito modular hexagonal/DDD),
**React 18 + Vite + TypeScript**, **PostgreSQL 15** con Flyway, y un
**event log append-only** como columna vertebral: todo cambio de estado
de un trámite es un evento (`eventos`: id, tramite_id, tipo, canal,
actor, payload, timestamp) y el estado se reconstruye desde ahí. La
identidad del paciente es su **CURP** (única); cada trámite tiene un
folio legible (`SD-2026-XXXX`) como llave de continuidad entre canales.

## Repositorio y versión a evaluar

- **Repositorio:** https://github.com/ProfCristianPalma/SaludDigna_ITSSNA_Team.git
- **Rama a evaluar:** `main`
- **Código evaluado:** commit `b46b7e8` (25 de noviembre de 2026).

## Instalación paso a paso (para replicar el demo en su equipo)

Todo corre en Docker: **no hay "en mi equipo sí funciona" como excusa** porque el
equipo evaluador corre exactamente las mismas imágenes y el mismo
código. No hace falta instalar Java, Maven ni Node en la PC: Docker
los lleva dentro de los contenedores.

### 1. Qué necesitan instalar (una sola vez)

| Qué | Para qué | Dónde |
|---|---|---|
| **Git** | Bajar el repositorio | https://git-scm.com/downloads |
| **Docker Desktop** (con **Docker Compose v2**) | Levantar base de datos, backend y frontend | https://www.docker.com/products/docker-desktop |
| **Un navegador moderno** (Chrome, Edge o Firefox) | Abrir la app | — |

- **Windows:** instalen Docker Desktop, activen WSL 2 cuando lo pida y
  esperen a que diga "Running" abajo a la izquierda.
- **Mac:** Docker Desktop, arrastrar a Aplicaciones y abrirlo.
- **Linux:** instalen `docker` y `docker compose` de su distribución.
- Verifiquen en una terminal antes de seguir:
  ```bash
  docker --version
  docker compose version
  ```
  Si alguno dice "command not found" o "Docker Desktop is not running",
  Docker no está listo todavía.
- **Puertos que deben estar libres en la PC:** `5432` (Postgres),
  `8080` (backend) y `5173` (frontend). Si alguno está ocupado por otro
  programa, párenlo o cambien el puerto izquierdo en
  `docker-compose.yml` (por ejemplo `"5433:5432"`).

### 2. Bajar el proyecto

```bash
git clone https://github.com/ProfCristianPalma/SaludDigna_ITSSNA_Team.git
cd SaludDigna_ITSSNA_Team
```

### 3. Arrancar todo

```bash
docker compose up --build
```

- La **primera vez tarda varios minutos**: descarga las imágenes
  (Postgres, Maven/JDK, Node) y compila el backend y el frontend. Las
  siguientes veces es mucho más rápido.
- Dejen esa terminal abierta mientras dure la demo. Para parar:
  `Ctrl+C` y luego `docker compose down`.
- Si solo cambió la documentación o `docs`, basta con
  `docker compose up` (sin `--build`). Después de cambiar código hay
  que reconstruir con `docker compose up --build`; si no, se sigue
  viendo la versión vieja.

### 4. Qué servicios arrancan

| Servicio | Qué es | Puerto en la PC | Contenedor |
|---|---|---|---|
| `db` | PostgreSQL 15 (`postgres:15-alpine`) | 5432 | `saluddigna-db` |
| `backend` | Spring Boot 3.3 / Java 21 | 8080 | `saluddigna-backend` |
| `frontend` | React + Vite, servido con nginx | 5173 | `saluddigna-frontend` |

### 5. Variables de entorno y configuración de la base de datos

**No hace falta crear ningún archivo `.env`**: todos los valores ya
están en `docker-compose.yml`. Son estos (los de demostración, no
usarlos en producción):

| Variable | Valor | Dónde |
|---|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `saluddigna` / `saluddigna` / `saluddigna` | servicio `db` |
| `DATABASE_URL` | `jdbc:postgresql://db:5432/saluddigna` | servicio `backend` |
| `DB_USER` / `DB_PASSWORD` | `saluddigna` / `saluddigna` | servicio `backend` |
| `JWT_SECRET` | `salud-digna-demo-secret-cambiar-en-produccion` | servicio `backend` (demo; en producción debe tener al menos 32 caracteres) |
| `JAVA_TOOL_OPTIONS` | `-Duser.timezone=America/Mexico_City` | servicio `backend` (sin esto el contenedor corre en UTC, 6 h adelantado, y "hoy"/"mañana" y la validación de citas pasadas fallan) |

- **Base de datos:** Postgres `saluddigna`, volumen Docker `pgdata`.
  El esquema lo crean las migraciones **Flyway V1–V12**, que se aplican
  solas al arrancar el backend. Nunca se edita una migración ya
  aplicada: los cambios van siempre en una migración nueva.
- **Carga de datos de prueba:** automática e idempotente. Al arrancar,
  el `DataSeeder` siembra 20 pacientes, 6 estudios, 2 clínicas y las
  cuentas de personal (ver [Datos semilla](#datos-semilla-estado-inicial)).
  Reiniciar no duplica nada. Las citas semilla son **relativas al día
  del primer arranque** (mañana, +2 días…), así que nunca quedan en el
  pasado.

### 6. Verificar que todo está arriba

```bash
docker compose ps
curl http://localhost:8080/api/health
```

- `docker compose ps` debe mostrar los 3 contenedores arriba, con `db`
  en estado `healthy`.
- El health debe responder `{"status":"UP"}`. En PowerShell, sin curl:
  `Invoke-RestMethod http://localhost:8080/api/health`.
- Si el backend está en `Restarting` o `Exited`, el error está en sus
  logs: `docker compose logs --tail=100 backend`.

### 7. URLs de acceso

| Qué | URL |
|---|---|
| **Aplicación (frontend)** | http://localhost:5173 |
| **Salud del backend** | http://localhost:8080/api/health (debe decir `UP`) |
| **API** | http://localhost:8080/api |

No abran `http://localhost:5432` en el navegador: Postgres no es web.

## Credenciales de prueba para cada rol

El login de **pacientes es solo con CURP** (no vale el teléfono ni el
nombre). El campo de contraseña tiene ojo para mostrarla. Las CURPs son
**de demostración** (formato válido, derivadas del nombre, la fecha de
nacimiento, el sexo y la entidad; no son CURPs reales de ninguna
persona). La contraseña de cada paciente es su primer nombre en
minúsculas + `123`.

### Paciente (rol `PACIENTE`)

Entrar en http://localhost:5173/login (o "Iniciar sesión" en la
bienvenida). Tras entrar, la app lleva a `/app` (la App del paciente).

| Paciente | CURP (usuario) | Contraseña |
|---|---|---|
| Ana García López | `GALA900514MDFRPN00` | `ana123` |
| Luis Hernández Torres | `HETL850322HDFRRS01` | `luis123` |
| María Fernanda Ruiz | `RUXF920809MDFZXR02` | `maria123` |
| Carlos Mendoza Vega | `MEVC781130HDFNGR03` | `carlos123` |
| Fernanda López Díaz | `LODF010617MDFPZR04` | `fernanda123` |
| Jorge Alberto Mendoza Ruiz | `MERJ880315HDFNZR00` | `jorge123` |
| Sofía Isabel Herrera Gómez | `HEGS950722MDFRMF00` | `sofia123` |
| Diego Alejandro Torres Sánchez | `TOSD900108HDFRNG00` | `diego123` |
| Valeria Nicole Ortiz Medina | `ORMV981130MDFTDL00` | `valeria123` |
| Ricardo Emmanuel Vargas León | `VALR830605HDFRNC00` | `ricardo123` |
| Camila Fernanda Reyes Morales | `REMC970214MDFYRM00` | `camila123` |
| Andrés Felipe Cruz Navarro | `CUNA910919HDFRVN00` | `andres123` |
| Mariana Guadalupe Flores Díaz | `FODM930427MDFLZR00` | `mariana123` |
| Miguel Ángel Soto Ramírez | `SORM861203HDFTMG00` | `miguel123` |
| Daniela Alejandra Vega Castillo | `VECD990816MDFGSN00` | `daniela123` |
| Óscar Eduardo Peña Jiménez | `PEJO800525HDFXMS00` | `oscar123` |
| Paola Fernanda Luna Herrera | `LUHP021011MDFNRL00` | `paola123` |
| Javier Alejandro Méndez Ortiz | `MEOJ890707HDFNRV00` | `javier123` |
| Lucía Fernanda Aguilar Ramos | `AURL960321MDFGMC00` | `lucia123` |
| Eduardo José Salazar Peña | `SAPE840909HDFLXD00` | `eduardo123` |

- **Paciente de la demo:** **Ana García López** (`GALA900514MDFRPN00` /
  `ana123`). Es la que se usa en todos los pasos de
  [Pasos exactos para replicar el demo](#pasos-exactos-para-replicar-el-demo).
- También se puede crear una cuenta nueva en `/registro` (nombre, CURP
  obligatoria, teléfono opcional, fecha de nacimiento DD/MM/AAAA y
  contraseña de 6+ caracteres): al registrarse entra directo a `/app`.
  Si no se saben la CURP, el login y el registro enlazan a
  https://www.gob.mx/curp/ (se abre en otra pestaña).

### Personal (Asesor, Administrador y Doctor)

El personal entra con **usuario + contraseña** (no con CURP), en el
mismo login. Tras entrar, la app lleva directo a su consola. Las
vistas de personal solo las ve quien entró como personal: sin sesión,
`/asesor`, `/clinica` y `/admin/usuarios` solo piden el login y no
cargan ningún dato (eso es a propósito, no un fallo); con sesión de
paciente, avisan que son vistas de personal.

| Rol | Usuario | Contraseña | Pantalla de acceso | Uso |
|---|---|---|---|---|
| **Asesor** (Call Center) | `asesor` | `asesor123` | `/asesor` | Consola del asesor: busca por CURP, teléfono, folio o nombre y ve todo el contexto del paciente |
| **Administrador** | `admin` | `admin123` | `/admin/usuarios` (solo escribiendo la URL, sin botón ni enlace) | Administración de usuarios (pacientes y personal) |
| **Doctor** (Clínica) | `doctor` | `doctor123` | `/doctor` | Alejandro Ruiz: citas confirmadas del día, check-in, atendido / no presentado / no realizado |

La recepción de Clínica (`/clinica`) también es vista de personal
(asesor o admin): lista las citas confirmadas de todos los pacientes,
con check-in y "Registrar atención".

### Qué cuenta usar en cada paso del demo

| Paso del demo | Cuenta |
|---|---|
| Bienvenida, login y Web | Ana (`GALA900514MDFRPN00` / `ana123`) |
| WhatsApp | Ana por su CURP (el bot la pide antes de tocar ningún dato) |
| Call Center | `asesor` / `asesor123` |
| App | Ana (`GALA900514MDFRPN00` / `ana123`) |
| Clínica | `asesor` / `asesor123` |
| Doctor | `doctor` / `doctor123` |
| Journey | Cualquiera de las anteriores (se ve por folio) |
| Administración (opcional) | `admin` / `admin123` en `/admin/usuarios` |

## Datos semilla (estado inicial)

Esto es lo que hay en la base recién sembrada, antes de tocar nada.
Las citas son **relativas al día del primer arranque** (día +1 = mañana,
día +2, etc.), así que el demo funciona cualquier día, no solo el día
de ejecución.

### Estudios (6) y clínicas (2)

| # | Estudio | Precio | Duración |
|---|---|---|---|
| 1 | Ultrasonido abdominal | $850 | 30 min |
| 2 | Perfil tiroideo | $620 | 15 min |
| 3 | Radiografía tórax | $450 | 20 min |
| 4 | Análisis sangre | $380 | 10 min |
| 5 | Electrocardiograma | $450 | 20 min |
| 6 | Mastografía | $950 | 30 min |

| # | Clínica | Ciudad | Dirección |
|---|---|---|---|
| 1 | Clínica Puebla Centro | Puebla | Av. Juárez 123 |
| 2 | Clínica Salud Digna Tehuacán | Tehuacán | Calle 5 Sur 45 |

### Los 5 pacientes principales (un estado distinto cada uno)

| Paciente | Estado inicial | Detalle |
|---|---|---|
| Ana García López | **COTIZADO** | Ultrasonido abdominal $850, sin clínica elegida todavía |
| Luis Hernández Torres | **CLINICA_SELECCIONADA** | Perfil tiroideo $620, Clínica Puebla Centro |
| María Fernanda Ruiz | **CONFIRMADO** | Radiografía tórax $450, cita **mañana 10:00** en Tehuacán |
| Carlos Mendoza Vega | **ATENDIDO** | Análisis sangre $380, cita mañana 09:00 en Puebla (su hueco vuelve a estar libre) |
| Fernanda López Díaz | **DATOS_PENDIENTES** | Sin estudio todavía; pendientes: CURP y orden médica |

### Los otros 15 pacientes (citas CONFIRMADAS que ocupan el calendario de citas con fecha y hora)

Cada uno tiene una cita confirmada en día y hora distintos, en las dos
clínicas. Esas horas salen **deshabilitadas** en el calendario/horario, y
`confirmar` las rechaza con 409 si se intentan tomar. Al día siguiente
del primer arranque (**día +1**) hay 6 citas: María 10:00 Tehuacán,
Jorge 08:00 Puebla, Sofía 09:30 Puebla, Diego 08:30 Tehuacán, Valeria
11:00 Tehuacán y Ricardo 14:00 Puebla. Días +2 a +5: Camila, Andrés y
Mariana (+2); Miguel, Daniela, Óscar y Paola (+3); Javier y Lucía (+4);
Eduardo (+5).

### Reglas que se ven en el estado inicial

- **Un paciente, varios folios:** un paciente puede tener varios
  trámites activos a la vez, uno por cada estudio. Cada folio
  (`SD-2026-XXXX`) tiene su propio estudio, precio, clínica, cita y
  estado.
- **Sin duplicados:** si el paciente ya tiene un trámite **activo** de
  ese estudio, no se crea otro: el backend devuelve 409 con el folio
  existente y lo que le falta, y la Web, el Asesor y WhatsApp ofrecen
  **retomarlo** para completar solo los pasos que faltan sobre ese
  mismo folio. Repetir un estudio ya ATENDIDO, NO_PRESENTADO o
  NO_REALIZADO sí crea un folio nuevo: es historial, no un duplicado.
- **Máquina de estados:** INICIADO → DATOS_PENDIENTES → COTIZADO →
  CLINICA_SELECCIONADA → CONFIRMADO → ATENDIDO (terminales:
  NO_PRESENTADO, NO_REALIZADO, CANCELADO).
- **Fechas en formato latinoamericano:** todo lo que ve, escribe o
  recibe el usuario es **DD/MM/AAAA** (la cita se muestra como
  `25/11/2026 10:00`). Por dentro, la API y Postgres guardan ISO.
- **Agenda de horarios:** slots de 30 minutos, 07:00–16:30. Una cita
  CONFIRMADA ocupa su intervalo (según la duración del estudio); un
  hueco que ya pasó hoy no se puede reservar.

## Pasos exactos para replicar el demo

Duración objetivo: 5–7 minutos. Un solo paciente, una sola
conversación. Hagan todo el recorrido **en la misma pestaña** del
navegador: la sesión vive en `sessionStorage` y dura lo que dura la
pestaña; si la cierran, hay que volver a entrar (los folios están en
la base, no se pierden).

**Estado inicial:** base recién sembrada (`docker compose down -v` +
`docker compose up --build` si ya se había usado). Paciente de la
demo: **Ana García López**, CURP `GALA900514MDFRPN00`, contraseña
`ana123`. Su trámite semilla está **COTIZADO** (Ultrasonido abdominal
$850, sin clínica elegida).

### Paso 1 — Bienvenida (`/`)

Abrir http://localhost:5173. Se ve la página de bienvenida de Salud
Digna, sin banner "Modo demo sin backend" (si el banner sigue, el
backend no levantó: ver [Solución de problemas](#solución-de-problemas)).
**Resultado esperado:** presentación, botones "Iniciar sesión" y "Crear
cuenta" y la pestaña Web.

### Paso 2 — Login como Ana (`/login`)

Pulsar "Iniciar sesión", escribir CURP `GALA900514MDFRPN00` y
contraseña `ana123`, entrar. **Resultado esperado:** la app lleva a
`/app` y muestra "Hola, Ana" con sus folios (su Ultrasonido COTIZADO).
El login es real contra Postgres (contraseña con hash PBKDF2, JWT de
8 h); el teléfono ya no entra como identificador.

### Paso 3 — Web: cotizar y reservar (`/cotizar`, pestaña "Web")

1. En el menú, ir a **Web**. Como Ana, elegir el estudio **Perfil
   tiroideo ($620)** y pulsar **Reservación**.
   **Resultado esperado:** se crea un **folio nuevo** (un folio por
   estudio; anoten el folio que muestra la pantalla, formato
   `SD-2026-XXXX`) y queda **COTIZADO**. Si eligieran Ultrasonido,
   no se crearía otro: Ana ya tiene uno activo de ese estudio y la
   app ofrecería retomarlo (ver [Reglas](#datos-semilla-estado-inicial)).
2. Elegir la clínica **Clínica Puebla Centro** y pulsar "Seleccionar
   clínica". **Resultado esperado:** el folio pasa a
   **CLINICA_SELECCIONADA** y se muestra el nombre de la clínica (nunca
   el estado técnico).
3. Elegir la fecha (**hoy**, en formato DD/MM/AAAA, o con el
   calendario) y una hora libre de la parrilla (slots de 30 min,
   07:00–16:30; las horas ocupadas por las citas semilla salen
   deshabilitadas). Elijan una hora futura si la fecha es hoy: los
   huecos ya pasados no se pueden reservar.
4. Pulsar **"Cerrar sin confirmar (simular interrupción)"**.
   **Resultado esperado:** el folio queda como estaba (COTIZADO o
   CLINICA_SELECCIONADA, según hasta dónde llegaron), sin perderse
   nada. Esto simula que Ana cuelga a mitad del trámite.

### Paso 4 — WhatsApp (`/whatsapp`)

1. Abrir `/whatsapp` (o "Abrir WhatsApp" desde la App).
2. Escribir `hola`. El bot pide la CURP antes de tocar ningún dato.
   Escribir `GALA900514MDFRPN00`.
   **Resultado esperado:** el bot reconoce a Ana por su CURP y lista
   sus folios activos.
3. Elegir el folio del Perfil tiroideo: escribir el folio completo
   (`SD-2026-XXXX`, el que anotaron en el paso 3) o `folio 2`.
   **Resultado esperado:** el bot recupera ese trámite y ofrece seguir
   donde se quedó. Ojo: un número suelto como `2` significa la
   **clínica 2**, no el folio 2; para elegir folio hay que escribir
   `folio 2`, el folio completo o el ordinal ("segundo folio").
4. Elegir la clínica tocando el botón **"1 · Clinica Puebla Centro"**
   (o escribiendo `1`, `Puebla` o `la primera`).
   **Resultado esperado:** el folio pasa a CLINICA_SELECCIONADA y el
   bot pide fecha y hora.
5. Escribir la fecha como **DD/MM/AAAA** (por ejemplo, la de hoy) y la
   hora como `10`, `10:00`, `a las 10` o `10 am` (todas valen). Si la
   hora está ocupada, el bot lo dice y ofrece las horas libres
   cercanas; si la fecha ya pasó o es imposible, lo dice y vuelve a
   pedirla.
   **Resultado esperado:** el bot confirma la cita y el trámite queda
   **CONFIRMADO**. El evento `cita.confirmada` queda en el log con
   canal WHATSAPP. El trámite avanzó COTIZADO →
   CLINICA_SELECCIONADA → CONFIRMADO **sin que Ana repitiera ningún
   dato**.

### Paso 5 — Resiliencia (lo que prueba el event log)

A mitad del flujo (o ahora), **refrescar el navegador (F5) en la misma
pestaña**. **Resultado esperado:** el trámite aparece intacto, en el
mismo estado: se reconstruye desde el event log append-only, no desde
la pantalla. Si al refrescar pide login de nuevo, es porque se cerró
la pestaña sin querer: vuelvan a entrar y sigan; los folios están en
la base.

### Paso 6 — Call Center (`/asesor`)

1. Cerrar la sesión de Ana y entrar como personal: usuario `asesor`,
   contraseña `asesor123`. El login de staff lleva directo a la
   consola.
2. En "Buscar trámite", buscar por CURP `GALA900514MDFRPN00` (también
   vale por teléfono, folio o nombre).
   **Resultado esperado:** el asesor ve el paciente, sus folios con
   estudio, precio, estado legible, clínica y fecha, y lo que le falta
   a cada uno, **sin pedirle a Ana que repita nada** (contexto
   acumulado). Desde el detalle puede editar los datos del paciente,
   editar la reservación (con nota obligatoria), eliminar/cancelar (con
   causa obligatoria) o continuar el proceso.
3. Guardar una nota desde la consola.
   **Resultado esperado:** la nota queda en la base y aparece en el
   historial y en el Journey del folio, con canal CALL_CENTER.

### Paso 7 — App (`/app`)

Cerrar la sesión de asesor y volver a entrar como Ana (CURP
`GALA900514MDFRPN00` / `ana123`). **Resultado esperado:** "Hola, Ana"
con sus folios y su cita confirmada, con su botón "Ver mi Journey".
Nada de teléfono escrito a mano: son los datos de la sesión del login.

### Paso 8 — Clínica (`/clinica`)

Cerrar la sesión de Ana y entrar como `asesor` / `asesor123`.
**Resultado esperado:** la recepción lista las citas **CONFIRMADAS del
día** de todos los pacientes, incluida la que se acaba de confirmar
en vivo (y, si se sembró ayer, las 6 citas semilla del día). Hacer
check-in en la cita de Ana y pulsar "Registrar atención".
**Resultado esperado:** el folio pasa a **ATENDIDO** de verdad en la
base y desaparece de la lista de confirmados al recargar.

### Paso 9 — Doctor (`/doctor`) NOTA: Se comenzo a trabajar la sección,
### pero no se termino por completo, puesto que no es parte del reto

Entrar como `doctor` / `doctor123` (Alejandro Ruiz).
**Resultado esperado:** las citas CONFIRMADAS del día, con check-in
(una sola vez por folio) y las acciones **Atendido**, **No se
presentó** y **No se pudo realizar** (esta última con el motivo
obligatorio que apunta el doctor; queda en el log y en el Journey).
Si el tiempo apremia, este paso puede ir pegado al 8: es la misma
historia, otro rol.

### Paso 10 — Journey (`/journey` o `/journey/SD-2026-XXXX`)

Abrir el Journey del folio (desde la App, el Historial o escribiendo
la URL con el folio). **Resultado esperado:** el recorrido paso a paso
del trámite — etapa actual, canales recorridos, qué pasó, cuándo y en
qué canal ocurrió cada paso — con la línea de tiempo unificada de
todos los eventos (Web, WhatsApp, Call Center, App y Clínica).

### Opcional — Administración (`/admin/usuarios`)

Solo escribiendo la URL, sin botón ni enlace, con sesión de `admin` /
`admin123`. **Resultado esperado:** listado de pacientes con CRUD real
contra Postgres (crear, editar, restablecer contraseña — se muestra una
sola vez — y borrar con cascada a folios, citas y eventos) y, abajo,
"Personal — cuentas de acceso" con el CRUD de las cuentas de admin,
asesor y doctor.

### Qué canales son simulados (y cuáles no)

| Canal | Estado | Qué significa |
|---|---|---|
| **Web** (`/cotizar`) | Real (prototipo web) | La página de reservación del prototipo |
| **App** (`/app`) | Real (prototipo web móvil) | La vista móvil del paciente, con su sesión |
| **WhatsApp** (`/whatsapp`) | **Simulado** | Un chat dentro de la app que imita WhatsApp: no hay API real de WhatsApp, ni número real, ni mensajes a teléfonos. El bot (reglas deterministas en el backend) atiende con los datos reales de la base |
| **Call Center** (`/asesor`) | **Simulado** | La consola web del asesor simula la llamada telefónica: el asesor atiende como si Ana llamara, con todo su contexto en pantalla |
| **Clínica / Doctor** (`/clinica`, `/doctor`) | **Simulado** | Las páginas web simulan el mostrador de la clínica: recepción, check-in y atención |
| **Journey** (`/journey`) | Tablero | Solo lectura del event log; no es un canal de atención |

Todos los canales (reales y simulados) pegan a la **misma API** y al
**mismo event log**: por eso el cambio de canal no pierde contexto.
Si Salud Digna diera un sandbox real de WhatsApp, se conectaría al
mismo endpoint (`POST /api/canales/whatsapp/mensaje`).

### Cómo volver al estado inicial para repetir la prueba

```bash
docker compose down -v
docker compose up --build
```

- `down -v` borra el volumen `pgdata` (toda la base de datos,
  incluidos los folios creados a mano durante la prueba).
- Al levantar, Flyway aplica las migraciones y el `DataSeeder` vuelve a
  sembrar los 20 pacientes, los 6 estudios, las 2 clínicas y el
  personal, con las citas relativas otra vez en mañana, +2 días, etc.
- **Sin `-v`** (`docker compose down` normal) los datos sobreviven:
  sirve para reiniciar los contenedores sin perder lo probado.

## Arquitectura en 10 líneas

1. Monolito modular Spring Boot 3.3 + Java 21 + React 18 + Vite +
   PostgreSQL 15. Un solo deploy; módulos por contexto acotado
   (Identidad, Trámite, Eventos, Catálogo, Canales) con límites
   hexagonales para poder partirlo en microservicios después.
2. Columna vertebral: event log append-only (`eventos`: id, tramite_id,
   tipo, canal, actor, payload, timestamp). Todo cambio de estado es un
   evento en la misma transacción; si el evento falla, el cambio se
   revierte.
3. Máquina de estados: INICIADO → DATOS_PENDIENTES → COTIZADO →
   CLINICA_SELECCIONADA → CONFIRMADO → ATENDIDO (terminales:
   NO_PRESENTADO, NO_REALIZADO, CANCELADO).
4. Identidad: **CURP como llave principal** (única, V7), teléfono
   opcional, folio `SD-2026-XXXX` como llave de continuidad.
5. Canales simulados y reales sobre la misma API; si Salud Digna da
   sandbox real, se conecta el mismo endpoint.
6. Seguridad demo: login real contra Postgres (hash PBKDF2, JWT HS256
   de 8 h con `pacienteId`), con fallback `X-Rol: Paciente | Asesor |
   Administrador | Doctor` documentado como simplificación de hackatón,
   no producción. El candado de rol vive en las páginas; la API no
   bloquea por rol en esta demo.
7. Los controladores devuelven **mapas planos** (DTOs), nunca entidades
   JPA con relaciones lazy: así la serialización no depende de ningún
   proxy de Hibernate.
8. Datos 100% sintéticos: pacientes ficticios, catálogo y clínicas
   inventados. Nada de datos reales.
9. Tablero Patient Journey en tiempo real: etapa actual, canales
   recorridos, línea de tiempo unificada con el canal de cada paso.
10. Diagramas (flujo de datos, modelo de datos, secuencia):
    `docs/arquitectura.md` (Mermaid).

## Estructura del repositorio

```
SaludDigna_ITSSNA_Team/
├── README.md                  Este archivo (instalación, credenciales y demo)
├── docker-compose.yml         db (postgres:15) + backend + frontend
├── backend/                   Spring Boot 3.3, Java 21, Maven
│   ├── Dockerfile             Multi-stage: maven:3.9-eclipse-temurin-21 → eclipse-temurin:21-jre
│   ├── pom.xml
│   └── src/main/
│       ├── java/mx/saluddigna/omnicanal/
│       │   ├── identidad/     Pacientes, CURP, teléfono
│       │   ├── tramite/       Trámites, estados, parrilla, Journey
│       │   ├── eventos/       Event log append-only
│       │   ├── catalogo/      Estudios, clínicas, citas
│       │   ├── canales/       WhatsApp (bot) y Call Center
│       │   ├── seguridad/     Login, JWT, PBKDF2, personal
│       │   ├── admin/         Administración de usuarios
│       │   └── config/        DataSeeder (datos de prueba)
│       └── resources/
│           ├── application.properties
│           └── db/migration/  Flyway V1–V12
├── frontend/                  React 18 + Vite + TypeScript
│   ├── Dockerfile             Multi-stage: node:20-alpine → nginx:alpine
│   ├── nginx.conf
│   └── src/                   Páginas (Web, App, WhatsApp, Asesor, Clínica, Doctor, Journey…), api.ts, fechas.ts (DD/MM/AAAA)
└── docs/
    └── arquitectura.md        Diagramas Mermaid (flujo, modelo, secuencia)
```

## Endpoints principales

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/api/health` | Salud del backend (`{"status":"UP"}`) |
| POST | `/api/auth/login` | Login paciente (solo CURP) o staff, devuelve JWT + paciente |
| POST | `/api/auth/registro` | Registro de paciente + auto-login (CURP obligatoria, teléfono opcional; 409 si la CURP ya tiene contraseña) |
| GET | `/api/auth/yo` | Perfil de la sesión (Bearer), `{paciente, usuario, rol}` |
| GET | `/api/pacientes?curp=` | Resolver identidad por CURP |
| GET | `/api/pacientes?telefono=` | Resolver identidad por teléfono |
| GET | `/api/pacientes/buscar?nombre=` | Pacientes por nombre |
| GET | `/api/pacientes/{id}/tramites` | Trámites del paciente (mapas planos) |
| POST | `/api/pacientes` | Registrar paciente (el asesor lo usa sin cerrar su sesión) |
| PUT | `/api/pacientes/{id}` | Editar paciente |
| POST | `/api/pacientes/{id}/restablecer-password` | Restablecer contraseña (asesor/admin; la nueva se devuelve solo en esa respuesta) |
| POST | `/api/tramites` | Crear trámite (acepta `curp` opcional) |
| GET | `/api/tramites/folio/{folio}` | Trámite por folio (ej. `SD-2026-0001`) |
| GET | `/api/tramites?estado=` | Trámites en un estado (ej. CONFIRMADO) |
| POST | `/api/tramites/{id}/cotizar` | Cotizar estudio (409 con el folio existente si ya hay uno activo de ese estudio) |
| POST | `/api/tramites/{id}/seleccionar-clinica` | Elegir clínica |
| POST | `/api/tramites/{id}/confirmar` | Confirmar cita (valida parrilla, futuro y hueco libre) |
| PUT | `/api/tramites/{id}` | Editar reservación (asesor; nota obligatoria) |
| POST | `/api/tramites/{id}/cancelar` | Cancelar trámite (asesor; motivo obligatorio) |
| DELETE | `/api/tramites/{id}` | Eliminar suave (asesor; motivo obligatorio; el historial se conserva) |
| POST | `/api/tramites/{id}/atender` | Registrar atención (CONFIRMADO → ATENDIDO) |
| POST | `/api/tramites/{id}/completar-dato` | Completar dato pendiente |
| GET | `/api/tramites/{id}/timeline` | Línea de tiempo del trámite |
| GET | `/api/journey/{tramiteId}` · `/api/journey/folio/{folio}` | Patient Journey (estado + timeline + siguiente paso) |
| GET | `/api/clinica/citas?estado=` | Citas de Clínica como DTOs planos (CONFIRMADO por defecto) |
| GET | `/api/disponibilidad/parrilla?clinicaId&fecha&estudioId?` | Parrilla 07:00–16:30, slots de 30 min, con ocupados |
| GET | `/api/estudios` · `/api/clinicas` | Catálogo |
| POST | `/api/canales/whatsapp/mensaje` | WhatsApp simulado (`{curp?, mensaje, folio?}`) |
| POST | `/api/canales/call-center/nota` | Nota del asesor |
| GET/POST/PUT/DELETE | `/api/admin/usuarios` · `/api/admin/staff` | Administración de usuarios y de cuentas de personal |

Catálogo de eventos: `paciente.registrado`, `tramite.iniciado`,
`cotizacion.creada`, `clinica.seleccionada`, `identidad.verificada`,
`canal.cambiado`, `dato.pendiente.completado`, `cita.confirmada`,
`atencion.concluida`, `tramite.recuperado`, `password.restablecida`,
`tramite.editado`, `tramite.cancelado`, `tramite.eliminado`,
`mensaje.no_entendido`.

## Solución de problemas

| Síntoma | Causa probable | Arreglo |
|---|---|---|
| `port is already allocated` en 5432 / 8080 / 5173 | Ya hay un Postgres, Java o front corriendo en la PC | Parar ese programa, o cambiar el puerto izquierdo en `docker-compose.yml` (`"5433:5432"`, por ejemplo) |
| `Docker Desktop is not running` | Docker Desktop cerrado | Abrirlo y esperar a "Running" |
| El front abre pero dice "Modo demo sin backend" | El backend no arrancó o aún está compilando | Esperar 1 min; el front reintenta cada 5 s y tiene botón "Reintentar conexión". Si sigue, `docker compose logs --tail=100 backend` |
| `docker compose up` se queda en el backend | Primera compilación Maven, descarga dependencias | Esperar; es normal la primera vez (3–8 min según internet). En reintentos, una capa fallida no se cachea y vuelve a descargar |
| `backend` en `Restarting` o `Exited` | Error de arranque (ver logs) | `docker compose logs --tail=100 backend` (el error dice la línea exacta); `db` debe estar `healthy` antes de que el backend arranque |
| Cambié código y no se refleja | Falta reconstruir | `docker compose up --build` |
| La base quedó con datos raros | Trámites de pruebas viejas | `docker compose down -v` y volver a levantar (resembra todo) |
| `curl` a `/api/health` no responde | Backend caído o puerto ocupado | `docker compose ps` y `docker compose logs --tail=50 backend` |
| `/asesor` o `/clinica` piden login en vez de mostrar datos | No es un fallo: es el control de personal | Entrar como `asesor` / `asesor123` (o `admin` / `admin123`) |
| Al refrescar pide login de nuevo | Se cerró la pestaña sin querer | La sesión vive en `sessionStorage` y solo sobrevive en la misma pestaña. Volver a entrar; los folios están en la base |

Diagnóstico rápido en una terminal, dentro de la carpeta del proyecto:

```bash
docker compose ps
docker compose logs --tail=100 backend
docker compose logs --tail=50 db
curl http://localhost:8080/api/health
# En PowerShell, sin curl: Invoke-RestMethod http://localhost:8080/api/health
```

## Notas

- **Persistencia:** usuarios y folios viven en **Postgres** (volumen
  `pgdata`): sobreviven a reinicios del backend y a
  `docker compose down` normal. **Solo `docker compose down -v` borra
  la base de datos.** Nada se guarda solo en el navegador ni en un mapa
  en memoria.
- **Si el backend no responde**, las escrituras (cotizar, crear folio,
  elegir clínica, confirmar, WhatsApp, completar dato, atender) **no
  inventan datos**: lanzan el error y la página avisa que no se creó ni
  se guardó nada. Solo el catálogo (estudios y clínicas) muestra datos
  locales con el banner "Modo demo sin backend" mientras el backend no
  responde.
- **Nunca edites una migración Flyway ya aplicada** (V1–V12): Flyway
  valida el checksum de cada migración aplicada en la BD y rechaza el
  arranque si cambió. Los cambios de esquema o datos van siempre en una
  migración nueva.
- **Autenticación real contra Postgres** (hash PBKDF2 en
  `password_hash`, JWT con `pacienteId` para pacientes y con rol para
  el staff, tabla `usuarios_staff` sembrada por DataSeeder). El hash
  **nunca** sale en las respuestas JSON. El fallback `X-Rol` es solo
  para el hackatón; no usar en producción.