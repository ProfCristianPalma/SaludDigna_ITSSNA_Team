# Guía Docker para el equipo — Salud Digna HackaTec 2026

Objetivo: que todos corran **exactamente lo mismo**, sin "en mi máquina sí funciona".
Docker empaqueta Java 21, Node, Postgres 16 y el código en contenedores
idénticos. Tú solo instalas Docker Desktop y corres 2 comandos.

## Qué levanta `docker compose up`

| Servicio | Qué es | Puerto en tu PC |
|---|---|---|
| `db` | PostgreSQL 16 | 5432 |
| `backend` | Spring Boot 3.3 / Java 21 | 8080 |
| `frontend` | React + Vite, servido con nginx | 5173 |

Front: http://localhost:5173 — Back: http://localhost:8080/api/estudios

## 1. Instalar Docker (una sola vez, 10 min)

- **Windows:** descarga Docker Desktop de docker.com, instálalo, reinicia si
  te lo pide. Activa WSL 2 cuando te lo pregunte. Abre Docker Desktop y espera
  a que diga "Running" abajo a la izquierda.
- **Mac:** descarga Docker Desktop, arrástralo a Aplicaciones y ábrelo.
- **Linux:** instala `docker` y `docker compose` de tu distro.

Verifica en una terminal:
```bash
docker --version
docker compose version
```
Si alguno dice "command not found" o "Docker Desktop is not running",
Docker no está listo todavía. No sigas al paso 2 hasta ver las versiones.

## 2. Bajar el proyecto (una sola vez)

```bash
git clone https://github.com/ProfCristianPalma/SaludDigna_ITSSNA_Team.git
cd SaludDigna_ITSSNA_Team
```

## 3. Arrancar todo (la primera vez tarda varios minutos)

```bash
docker compose up --build
```

La primera vez descarga las imágenes (Postgres, Maven/JDK, Node) y compila
el backend y el front. Las siguientes veces es mucho más rápido.
Deja esa terminal abierta. Cuando veas los 3 servicios arriba, abre
http://localhost:5173

Para parar: `Ctrl+C` en esa terminal, y luego:
```bash
docker compose down
```

## 4. Trabajo diario de cada integrante

```bash
# Al empezar el día: trae lo último
git pull origin main

# Si alguien cambió backend o frontend: reconstruye
docker compose up --build

# Si solo cambió el README o docs: basta con
docker compose up
```

### Ver qué está pasando
```bash
docker compose ps            # qué contenedores están arriba
docker compose logs -f backend   # logs del backend en vivo (Ctrl+C para salir)
docker compose logs -f frontend  # logs del front
docker compose logs -f db        # logs de Postgres
```

### Empezar la base de datos desde cero (borra los datos de prueba)
```bash
docker compose down -v
docker compose up --build
```
Ojo: `-v` borra el volumen `pgdata`, así que se pierden los trámites
creados a mano. El seed (Ana, estudios, clínicas) se vuelve a cargar solo.

## 5. Cómo subir cambios a GitHub sin pisarse

Regla de oro: **nadie trabaja directo en `main` todo el día**.

```bash
# 1. Crea tu rama
git checkout -b tu-nombre/lo-que-vas-a-hacer
# ejemplo: git checkout -b ana/whatsapp-chat

# 2. Trabaja, guarda y sube tu rama
git add .
git commit -m "Qué hiciste, en una línea"
git push -u origin tu-nombre/lo-que-vas-a-hacer

# 3. En GitHub: abre un Pull Request a main, otro compañero lo revisa
#    y lo mezcla. Luego todos hacen git pull origin main.
```

Antes de subir, haz `git status` y revisa que no estés subiendo
`node_modules/`, `target/`, `.env` ni archivos personales. El `.gitignore`
del repo ya los excluye, pero vale la pena mirar.

## 6. Docker para desarrollar vs. Docker para la demo

- **Para la demo y para probar que todo integra:** Docker, como arriba.
  Es la versión que el jurado va a ver.
- **Para programar rápido:** es más cómodo correr backend y front fuera
  de Docker (hot reload), y dejar Docker solo para la prueba final:
  `cd backend && mvn spring-boot:run` y `cd frontend && npm run dev`.
  Pero si algo funciona fuera y no dentro de Docker, manda Docker:
  la demo se hace con Docker.

Después de cambiar código, Docker **no** lo ve solo: hay que reconstruir
con `docker compose up --build`. Si no reconstruyes, sigues viendo
la versión vieja y parece que tu cambio "no hizo nada".

## 7. Problemas típicos (y su arreglo)

| Síntoma | Causa probable | Arreglo |
|---|---|---|
| `port is already allocated` en 5432 / 8080 / 5173 | Ya tienes un Postgres, Java o front corriendo en tu PC | Para ese programa, o cambia el puerto izquierdo en `docker-compose.yml` (`"5433:5432"`, por ejemplo) |
| `Docker Desktop is not running` | Docker Desktop cerrado | Ábrelo y espera a "Running" |
| El front abre pero dice "Modo demo sin backend" | El backend no arrancó o aún está compilando | Espera 1 min, revisa `docker compose logs -f backend` |
| `docker compose up` se queda en el backend | Primera compilación Maven, descarga dependencias | Espera, es normal la primera vez (3-8 min según internet) |
| Cambié código y no se refleja | Falta reconstruir | `docker compose up --build` |
| La base quedó con datos raros | Trámites de pruebas viejas | `docker compose down -v` y vuelve a levantar |

## Credenciales de prueba

- Paciente: `ana` / `ana123` — Ana, tel. 2220000001
- Asesor: `asesor` / `asesor123`
- Admin: `admin` / `admin123`
- Trámite demo: folio `SD-2026-0001`, estado COTIZADO
