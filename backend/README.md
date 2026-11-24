# Salud Digna Omnicanal — Backend

HackaTec Nacional 2026 · Reto Salud Digna: "Orquestación Omnicanal de Atención: Un paciente, una sola conversación".

Spring Boot 3.3 · Java 21 · Maven · PostgreSQL (Flyway) · monolito modular hexagonal/DDD.
El log de eventos append-only (`eventos`) es la columna vertebral: ningún cambio de estado de un trámite se escribe sin su evento.

## Correr

### Con PostgreSQL (perfil por defecto)

```bash
# Crea la base (o usa docker compose del repo raíz si existe):
# createdb saluddigna  # usuario/clave: saluddigna / saluddigna
export DATABASE_URL=jdbc:postgresql://localhost:5432/saluddigna
export DB_USER=saluddigna
export DB_PASSWORD=saluddigna
mvn spring-boot:run
```

### Sin base de datos: perfil H2 en memoria

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=h2
# Consola H2: http://localhost:8080/h2-console (jdbc:h2:mem:saluddigna, usuario sa)
```

La API escucha en `http://localhost:8080`.

## Datos demo

Al arrancar se siembran (idempotente): Ana García López (`+52 222 000 0001`, nac. 1990-05-14),
4 estudios (Ultrasonido abdominal $850, Perfil tiroideo $620, Radiografía tórax $450, Análisis sangre $380),
2 clínicas (Puebla Centro y Tehuacán) y un trámite demo de Ana en estado `COTIZADO` con sus eventos.

## Auth demo (NO producción)

`POST /api/auth/login` — usuarios: `ana/ana123` (PACIENTE), `asesor/asesor123` (ASESOR), `admin/admin123` (ADMINISTRADOR).
Devuelve un JWT (8 h). Sin token, el backend acepta el header `X-Rol: Paciente|Asesor|Administrador` como fallback de demo.

## Endpoints principales

| Método | Ruta | Descripción |
|---|---|---|
| POST | `/api/auth/login` | Login demo, devuelve JWT |
| POST / GET | `/api/pacientes` | Registrar / buscar por `?telefono=` (+ `fechaNacimiento=`) |
| GET | `/api/pacientes/{id}` | Paciente por id |
| POST | `/api/tramites` | Crear trámite |
| GET | `/api/tramites/{id}` · `/api/tramites?telefono=` | Trámite / trámites por teléfono |
| POST | `/api/tramites/{id}/eventos` | Registrar evento (append-only) |
| POST | `/api/tramites/{id}/cotizar` `{estudioId}` | Cotizar |
| POST | `/api/tramites/{id}/seleccionar-clinica` `{clinicaId}` | Elegir clínica |
| POST | `/api/tramites/{id}/confirmar` `{fechaCita}` | Confirmar cita |
| POST | `/api/tramites/{id}/completar-dato` `{clave, valor}` | Completar dato pendiente |
| GET | `/api/tramites/{id}/timeline` · `/api/journey/{tramiteId}` | Timeline / journey completo |
| GET | `/api/estudios` · `/api/clinicas` · `/api/clinicas/{id}` | Catálogo |
| POST | `/api/canales/whatsapp/mensaje` `{telefono, mensaje}` | WhatsApp simulado: resuelve/recupera/crea trámite |
| POST | `/api/canales/call-center/nota` `{tramiteId, nota}` | Nota de asesor en el log |

Flujo de estados: `INICIADO → DATOS_PENDIENTES → COTIZADO → CLINICA_SELECCIONADA → CONFIRMADO → ATENDIDO`.
Cada transición valida el estado permitido y registra su evento en la misma transacción.

## Docker

```bash
docker build -t saluddigna-backend .
docker run -p 8080:8080 -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/saluddigna saluddigna-backend
```
