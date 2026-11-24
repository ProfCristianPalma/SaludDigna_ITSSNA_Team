import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  actualizarStaffAdmin,
  actualizarUsuarioAdmin,
  crearStaffAdmin,
  crearUsuarioAdmin,
  eliminarStaffAdmin,
  eliminarUsuarioAdmin,
  generarPasswordLegible,
  getPerfilYo,
  getStaffAdmin,
  getTramitePorFolio,
  getUsuariosAdmin,
  isApiError,
  normalizarFolio,
  restablecerPasswordPaciente,
} from '../api';
import PasswordInput from '../components/PasswordInput';
import type { StaffAdmin, StaffAdminInput, UsuarioAdmin, UsuarioAdminInput } from '../api';
import type { Tramite } from '../types';
import { esFechaLatinaValida, formatearFecha, parsearFechaLatina } from '../fechas';
import { normalizarCurp, validarCurp } from '../curp';
import VistaPersonal from '../components/VistaPersonal';

// Administración de usuarios: SOLO se entra escribiendo la URL
// /admin/usuarios. No hay botón ni enlace desde ninguna otra página,
// ni se menciona en la bienvenida. Vista de personal: el contenido
// solo se monta con sesión de asesor o admin (VistaPersonal); sin
// sesión no se carga ningún usuario. Todo es Postgres real:
// crear, editar y borrar aquí queda guardado de forma permanente.
export default function AdminUsuariosPage() {
  return (
    <VistaPersonal titulo="Administración de usuarios">
      <ContenidoAdminUsuarios />
    </VistaPersonal>
  );
}

function ContenidoAdminUsuarios() {
  const [usuarios, setUsuarios] = useState<UsuarioAdmin[]>([]);
  const [busqueda, setBusqueda] = useState('');
  const [cargando, setCargando] = useState(true);
  const [mensaje, setMensaje] = useState('');
  const [error, setError] = useState('');

  // ¿Quién está viendo la página? Solo el ADMINISTRADOR ve la sección
  // de cuentas de personal; al asesor ni siquiera se le cargan.
  const [esAdmin, setEsAdmin] = useState(false);

  // Búsqueda por folio: el buscador de usuarios filtra por nombre o
  // teléfono en cliente y un folio nunca coincidía ("No hay usuarios
  // con esa búsqueda"). Esta es una búsqueda aparte, contra la API.
  const [folioBusqueda, setFolioBusqueda] = useState('');
  const [tramiteFolio, setTramiteFolio] = useState<Tramite | null>(null);
  const [buscandoFolio, setBuscandoFolio] = useState(false);
  const [mensajeFolio, setMensajeFolio] = useState('');

  const [editandoId, setEditandoId] = useState<string | null>(null);
  const [nombre, setNombre] = useState('');
  const [curp, setCurp] = useState('');
  const [telefono, setTelefono] = useState('');
  const [email, setEmail] = useState('');
  const [fechaNacimiento, setFechaNacimiento] = useState('');
  const [password, setPassword] = useState('');
  const [guardando, setGuardando] = useState(false);

  // Aviso de UNA SOLA VEZ con la contraseña recién creada o restablecida
  // (solo vive en el estado local de esta página). Las contraseñas
  // guardadas son hash PBKDF2 y nunca llegan al front: no hay forma de
  // volver a mostrarlas después.
  const [avisoPassword, setAvisoPassword] = useState<{ cuenta: string; password: string } | null>(null);

  // Restablecer contraseña (paciente o staff): diálogo con input + ojo
  // + "Generar". Al guardar se muestra la contraseña nueva una sola vez.
  const [resetTarget, setResetTarget] = useState<{
    tipo: 'paciente' | 'staff';
    id: string;
    nombre: string;
    usuario?: string;
    rol?: string;
  } | null>(null);
  const [resetPassword, setResetPassword] = useState('');
  const [reseteando, setReseteando] = useState(false);

  // Personal — cuentas de acceso (tabla usuarios_staff, V6).
  const [staff, setStaff] = useState<StaffAdmin[]>([]);
  const [cargandoStaff, setCargandoStaff] = useState(true);
  const [mensajeStaff, setMensajeStaff] = useState('');
  const [errorStaff, setErrorStaff] = useState('');
  const [editandoStaffId, setEditandoStaffId] = useState<string | null>(null);
  const [staffNombre, setStaffNombre] = useState('');
  const [staffUsuario, setStaffUsuario] = useState('');
  const [staffRol, setStaffRol] = useState('ASESOR');
  const [staffPassword, setStaffPassword] = useState('');
  const [guardandoStaff, setGuardandoStaff] = useState(false);

  const cargar = useCallback(async () => {
    setCargando(true);
    setError('');
    try {
      setUsuarios(await getUsuariosAdmin());
    } catch (err) {
      setError(
        isApiError(err)
          ? `No se pudieron cargar los usuarios (HTTP ${err.status}: ${err.message}).`
          : 'El backend no respondió, no se pudieron cargar los usuarios. Revisa que el backend esté arriba.',
      );
    } finally {
      setCargando(false);
    }
  }, []);

  useEffect(() => {
    async function verificarRol() {
      try {
        const perfil = await getPerfilYo();
        setEsAdmin(perfil.rol === 'ADMINISTRADOR');
      } catch {
        setEsAdmin(false);
      }
    }
    void verificarRol();
  }, []);

  useEffect(() => {
    void cargar();
  }, [cargar]);

  const cargarStaff = useCallback(async () => {
    setCargandoStaff(true);
    setErrorStaff('');
    try {
      setStaff(await getStaffAdmin());
    } catch (err) {
      setErrorStaff(
        isApiError(err)
          ? `No se pudo cargar el personal (HTTP ${err.status}: ${err.message}).`
          : 'El backend no respondió, no se pudo cargar el personal. Revisa que el backend esté arriba.',
      );
    } finally {
      setCargandoStaff(false);
    }
  }, []);

  // La lista de cuentas de personal solo se carga para el admin.
  useEffect(() => {
    if (esAdmin) void cargarStaff();
  }, [esAdmin, cargarStaff]);

  async function handleBuscarFolio(e: React.FormEvent) {
    e.preventDefault();
    const q = folioBusqueda.trim();
    if (!q) return;
    const folio = normalizarFolio(q);
    if (!folio) {
      setTramiteFolio(null);
      setMensajeFolio('Escribe un folio válido, por ejemplo SD-2026-0012.');
      return;
    }
    setBuscandoFolio(true);
    setMensajeFolio('');
    setTramiteFolio(null);
    try {
      setTramiteFolio(await getTramitePorFolio(folio));
    } catch (err) {
      setMensajeFolio(
        isApiError(err) && err.status === 404
          ? `No hay ningún trámite con el folio ${folio}.`
          : isApiError(err)
            ? `No se pudo buscar el folio ${folio} (HTTP ${err.status}: ${err.message}).`
            : 'El backend no respondió, no se pudo buscar el folio.',
      );
    } finally {
      setBuscandoFolio(false);
    }
  }

  // "Editar usuario" desde el resultado por folio: carga ese paciente
  // en el formulario de edición (de la lista si ya está, o con los
  // datos del trámite si no).
  function empezarEditarDesdeFolio(t: Tramite) {
    const p = t.paciente;
    if (!p) return;
    const enLista = usuarios.find((u) => u.id === p.id);
    if (enLista) {
      empezarEditar(enLista);
    } else {
      setEditandoId(p.id);
      setNombre(p.nombre);
      setCurp(p.curp ?? '');
      setTelefono(p.telefono ?? '');
      setEmail('');
      setFechaNacimiento(p.fechaNacimiento ? formatearFecha(p.fechaNacimiento) : '');
      setPassword('');
    }
    setMensaje(`Editando a ${p.nombre} (folio ${t.folio}).`);
    setError('');
  }

  function limpiarFormulario() {
    setEditandoId(null);
    setNombre('');
    setCurp('');
    setTelefono('');
    setEmail('');
    setFechaNacimiento('');
    setPassword('');
  }

  function empezarCrear() {
    limpiarFormulario();
    setMensaje('');
  }

  function empezarEditar(u: UsuarioAdmin) {
    setEditandoId(u.id);
    setNombre(u.nombre);
    setCurp(u.curp ?? '');
    setTelefono(u.telefono ?? '');
    setEmail(u.email ?? '');
    setFechaNacimiento(u.fechaNacimiento ? formatearFecha(u.fechaNacimiento) : '');
    setPassword('');
    setMensaje('');
  }

  async function handleGuardar(e: React.FormEvent) {
    e.preventDefault();
    setMensaje('');
    setError('');
    if (password.length > 0 && password.length < 6) {
      setError('La contraseña debe tener al menos 6 caracteres.');
      return;
    }
    if (!editandoId && password.length < 6) {
      setError('La contraseña es obligatoria al crear un usuario (mínimo 6 caracteres).');
      return;
    }
    // La fecha se escribe en DD/MM/AAAA y solo se convierte a ISO al enviar a la API.
    let fechaIso: string | undefined;
    if (fechaNacimiento.trim()) {
      if (!esFechaLatinaValida(fechaNacimiento)) {
        setError('La fecha de nacimiento no es válida. Escríbela como DD/MM/AAAA, por ejemplo 14/05/1990.');
        return;
      }
      fechaIso = parsearFechaLatina(fechaNacimiento) ?? undefined;
    }
    // CURP: obligatoria al crear; al editar, si se escribe debe ser válida (su 409 se muestra tal cual).
    const curpNormalizada = normalizarCurp(curp);
    if (!editandoId || curpNormalizada) {
      const v = validarCurp(curpNormalizada);
      if (!v.valida) {
        setError(`La CURP no es válida: ${v.motivo}`);
        return;
      }
    }
    const input: UsuarioAdminInput = {
      nombre: nombre.trim(),
      curp: curpNormalizada || undefined,
      // Al editar, teléfono vacío = borrarlo; al crear, vacío = sin teléfono.
      telefono: telefono.trim() ? telefono : editandoId ? '' : null,
      email: email.trim() || undefined,
      fechaNacimiento: fechaIso,
      password: password || undefined,
    };
    setGuardando(true);
    try {
      if (editandoId) {
        await actualizarUsuarioAdmin(editandoId, input);
        setMensaje(`Usuario ${input.nombre} actualizado y guardado en la base de datos.`);
      } else {
        await crearUsuarioAdmin(input);
        setMensaje(`Usuario ${input.nombre} creado y guardado en la base de datos.`);
      }
      // La contraseña recién puesta se muestra UNA SOLA VEZ: después
      // solo queda el hash y no hay forma de volver a verla.
      if (password) setAvisoPassword({ cuenta: input.nombre, password });
      limpiarFormulario();
      await cargar();
    } catch (err) {
      setError(
        isApiError(err)
          ? `No se pudo guardar (HTTP ${err.status}: ${err.message}).`
          : 'El backend no respondió, no se guardó nada. Revisa que el backend esté arriba.',
      );
    } finally {
      setGuardando(false);
    }
  }

  async function handleEliminar(u: UsuarioAdmin) {
    const confirmar = window.confirm(
      `¿Borrar a ${u.nombre} (${u.telefono ?? 'Sin teléfono'})?\n\n` +
        `Se borrarán también sus ${u.cantidadFolios} folio(s), sus citas y sus eventos. ` +
        `Esto no se puede deshacer.`,
    );
    if (!confirmar) return;
    setMensaje('');
    setError('');
    try {
      const res = await eliminarUsuarioAdmin(u.id);
      setMensaje(`Usuario ${u.nombre} borrado de la base de datos (con sus ${res.foliosEliminados} folio(s)).`);
      await cargar();
    } catch (err) {
      setError(
        isApiError(err)
          ? `No se pudo borrar (HTTP ${err.status}: ${err.message}).`
          : 'El backend no respondió, no se borró nada. Revisa que el backend esté arriba.',
      );
    }
  }

  function limpiarFormularioStaff() {
    setEditandoStaffId(null);
    setStaffNombre('');
    setStaffUsuario('');
    setStaffRol('ASESOR');
    setStaffPassword('');
  }

  function empezarCrearStaff() {
    limpiarFormularioStaff();
    setMensajeStaff('');
  }

  function empezarEditarStaff(s: StaffAdmin) {
    setEditandoStaffId(s.id);
    setStaffNombre(s.nombre);
    setStaffUsuario(s.usuario);
    setStaffRol(s.rol);
    setStaffPassword('');
    setMensajeStaff('');
  }

  async function handleGuardarStaff(e: React.FormEvent) {
    e.preventDefault();
    setMensajeStaff('');
    setErrorStaff('');
    if (staffPassword.length > 0 && staffPassword.length < 6) {
      setErrorStaff('La contraseña debe tener al menos 6 caracteres.');
      return;
    }
    if (!editandoStaffId && staffPassword.length < 6) {
      setErrorStaff('La contraseña es obligatoria al crear una cuenta (mínimo 6 caracteres).');
      return;
    }
    const input: StaffAdminInput = {
      nombre: staffNombre.trim(),
      usuario: staffUsuario.trim().toLowerCase(),
      rol: staffRol,
      password: staffPassword || undefined,
    };
    setGuardandoStaff(true);
    try {
      if (editandoStaffId) {
        await actualizarStaffAdmin(editandoStaffId, input);
        setMensajeStaff(`Cuenta ${input.usuario} actualizada y guardada en la base de datos.`);
      } else {
        await crearStaffAdmin(input);
        setMensajeStaff(`Cuenta ${input.usuario} creada y guardada en la base de datos.`);
      }
      // La contraseña recién puesta se muestra UNA SOLA VEZ.
      if (staffPassword) setAvisoPassword({ cuenta: input.usuario, password: staffPassword });
      limpiarFormularioStaff();
      await cargarStaff();
    } catch (err) {
      setErrorStaff(
        isApiError(err)
          ? `No se pudo guardar (HTTP ${err.status}: ${err.message}).`
          : 'El backend no respondió, no se guardó nada. Revisa que el backend esté arriba.',
      );
    } finally {
      setGuardandoStaff(false);
    }
  }

  async function handleEliminarStaff(s: StaffAdmin) {
    const confirmar = window.confirm(
      `¿Borrar la cuenta ${s.usuario} (${s.nombre}, ${s.rol})?\n\nEsto no se puede deshacer.`,
    );
    if (!confirmar) return;
    setMensajeStaff('');
    setErrorStaff('');
    try {
      await eliminarStaffAdmin(s.id);
      setMensajeStaff(`Cuenta ${s.usuario} borrada de la base de datos.`);
      await cargarStaff();
    } catch (err) {
      setErrorStaff(
        isApiError(err)
          ? `No se pudo borrar (HTTP ${err.status}: ${err.message}).`
          : 'El backend no respondió, no se borró nada. Revisa que el backend esté arriba.',
      );
    }
  }

  async function handleResetGuardar(e: React.FormEvent) {
    e.preventDefault();
    if (!resetTarget) return;
    if (resetPassword.length < 6) {
      setError('La contraseña nueva debe tener al menos 6 caracteres.');
      return;
    }
    setReseteando(true);
    setError('');
    try {
      if (resetTarget.tipo === 'paciente') {
        const nueva = await restablecerPasswordPaciente(resetTarget.id, resetPassword);
        setAvisoPassword({ cuenta: resetTarget.nombre, password: nueva });
        setMensaje(`Contraseña de ${resetTarget.nombre} restablecida.`);
        await cargar();
      } else {
        await actualizarStaffAdmin(resetTarget.id, {
          nombre: resetTarget.nombre,
          usuario: resetTarget.usuario ?? '',
          rol: resetTarget.rol ?? 'ASESOR',
          password: resetPassword,
        });
        setAvisoPassword({ cuenta: resetTarget.nombre, password: resetPassword });
        setMensajeStaff(`Contraseña de ${resetTarget.nombre} restablecida.`);
        await cargarStaff();
      }
      setResetTarget(null);
      setResetPassword('');
    } catch (err) {
      setError(
        isApiError(err)
          ? `No se pudo restablecer la contraseña (HTTP ${err.status}: ${err.message}).`
          : 'El backend no respondió, no se cambió nada. Revisa que el backend esté arriba.',
      );
    } finally {
      setReseteando(false);
    }
  }

  function abrirResetPaciente(u: UsuarioAdmin) {
    setResetTarget({ tipo: 'paciente', id: u.id, nombre: u.nombre });
    setResetPassword('');
    setAvisoPassword(null);
    setMensaje('');
    setError('');
  }

  function abrirResetStaff(s: StaffAdmin) {
    setResetTarget({ tipo: 'staff', id: s.id, nombre: s.nombre, usuario: s.usuario, rol: s.rol });
    setResetPassword('');
    setAvisoPassword(null);
    setMensajeStaff('');
    setErrorStaff('');
  }

  const q = busqueda.trim().toLowerCase();
  const visibles = q
    ? usuarios.filter(
        (u) =>
          u.nombre.toLowerCase().includes(q) ||
          (u.curp ?? '').toLowerCase().includes(q) ||
          (u.telefono ?? '').replace(/\D/g, '').includes(q.replace(/\D/g, '')),
      )
    : usuarios;

  return (
    <div>
      <h1>Administración de usuarios</h1>
      <p className="muted">
        Listado de usuarios de la base de datos, con su cantidad de folios. Crear, editar y borrar aquí
        queda guardado de forma permanente en Postgres (no es memoria ni caché).
        Al borrar un usuario se borran también sus folios, sus citas y sus eventos.
      </p>

      {mensaje && <p className="notice" role="status">{mensaje}</p>}
      {error && <p className="notice" role="alert">{error}</p>}

      {avisoPassword && (
        <p className="notice" role="status">
          Contraseña de <strong>{avisoPassword.cuenta}</strong>: <code>{avisoPassword.password}</code> — Anótala,
          no se volverá a mostrar. Las contraseñas guardadas son hash y no se pueden volver a ver.{' '}
          <button type="button" className="btn btn-small" onClick={() => setAvisoPassword(null)}>
            Entendido
          </button>
        </p>
      )}

      <div className="card form-inline" style={{ marginBottom: 16 }}>
        <input
          value={busqueda}
          onChange={(e) => setBusqueda(e.target.value)}
          placeholder="Buscar por nombre, CURP o teléfono"
          aria-label="Buscar usuario por nombre, CURP o teléfono"
        />
        <button type="button" className="btn btn-secondary" onClick={() => void cargar()} disabled={cargando}>
          Actualizar
        </button>
        <button type="button" className="btn btn-primary" onClick={empezarCrear}>
          Nuevo usuario
        </button>
      </div>

      <form className="card form-inline" style={{ marginBottom: 16 }} onSubmit={handleBuscarFolio}>
        <input
          value={folioBusqueda}
          onChange={(e) => setFolioBusqueda(e.target.value)}
          placeholder="Buscar folio (ej. SD-2026-0012)"
          aria-label="Buscar folio"
        />
        <button type="submit" className="btn btn-secondary" disabled={buscandoFolio}>
          {buscandoFolio ? 'Buscando…' : 'Buscar folio'}
        </button>
      </form>

      {mensajeFolio && <p className="notice" role="status">{mensajeFolio}</p>}

      {tramiteFolio && (
        <section className="card" style={{ marginBottom: 16 }}>
          <h2>Trámite {tramiteFolio.folio}</h2>
          <dl className="kv">
            <dt>Paciente</dt><dd>{tramiteFolio.paciente?.nombre ?? '—'} · {tramiteFolio.paciente?.curp ?? tramiteFolio.paciente?.telefono ?? '—'}</dd>
            <dt>Estudio</dt><dd>{tramiteFolio.estudio.nombre}</dd>
            <dt>Estado</dt><dd>{tramiteFolio.estado}</dd>
            <dt>Fecha de cita</dt><dd>{tramiteFolio.fechaCita ? formatearFecha(tramiteFolio.fechaCita) : 'Sin confirmar'}</dd>
            <dt>Clínica</dt><dd>{tramiteFolio.clinica ? `${tramiteFolio.clinica.nombre} (${tramiteFolio.clinica.ciudad})` : 'Sin seleccionar'}</dd>
          </dl>
          <div className="actions">
            <button type="button" className="btn btn-primary" onClick={() => empezarEditarDesdeFolio(tramiteFolio)}>
              Editar usuario
            </button>
            <Link className="btn btn-secondary" to={`/journey/${encodeURIComponent(tramiteFolio.folio)}`}>
              Ver Journey
            </Link>
          </div>
        </section>
      )}

      <section className="card" style={{ marginBottom: 16 }}>
        <h2>{editandoId ? 'Editar usuario' : 'Crear usuario'}</h2>
        <form onSubmit={handleGuardar} className="form">
          <label>
            Nombre completo
            <input value={nombre} onChange={(e) => setNombre(e.target.value)} required />
          </label>
          <label>
            CURP {editandoId ? '(opcional al editar)' : '(obligatoria)'}
            <input
              value={curp}
              onChange={(e) => setCurp(normalizarCurp(e.target.value))}
              placeholder="CURP (18 caracteres)"
              maxLength={18}
              style={{ textTransform: 'uppercase' }}
              required={!editandoId}
            />
          </label>
          <label>
            Teléfono (10 dígitos, sin +52) — opcional; vacío al editar = borrarlo
            <input value={telefono} onChange={(e) => setTelefono(e.target.value)} placeholder="2220000001" />
          </label>
          <label>
            Correo electrónico
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="tu@correo.com" />
          </label>
          <label>
            Fecha de nacimiento
            <input
              type="text"
              value={fechaNacimiento}
              onChange={(e) => setFechaNacimiento(e.target.value)}
              placeholder="DD/MM/AAAA"
              inputMode="numeric"
            />
          </label>
          <label>
            Contraseña {editandoId ? '(dejar vacía para no cambiarla)' : '(mínimo 6 caracteres)'}
            <PasswordInput
              value={password}
              onChange={setPassword}
              placeholder={editandoId ? 'Sin cambio' : 'Mínimo 6 caracteres'}
              autoComplete="new-password"
            />
          </label>
          <div className="actions">
            <button type="submit" className="btn btn-primary" disabled={guardando}>
              {guardando ? 'Guardando…' : editandoId ? 'Guardar cambios' : 'Crear usuario'}
            </button>
            {editandoId && (
              <button type="button" className="btn btn-secondary" onClick={limpiarFormulario}>
                Cancelar edición
              </button>
            )}
          </div>
        </form>
      </section>

      {cargando && usuarios.length === 0 && <p className="muted">Cargando usuarios…</p>}
      {!cargando && visibles.length === 0 && <p className="muted">No hay usuarios con esa búsqueda.</p>}

      {visibles.length > 0 && (
        <section className="card">
          <h2>Usuarios ({visibles.length})</h2>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                <th style={{ textAlign: 'left' }}>Nombre</th>
                <th style={{ textAlign: 'left' }}>CURP</th>
                <th style={{ textAlign: 'left' }}>Teléfono</th>
                <th style={{ textAlign: 'left' }}>Correo</th>
                <th style={{ textAlign: 'left' }}>Nacimiento</th>
                <th style={{ textAlign: 'left' }}>Folios</th>
                <th style={{ textAlign: 'left' }}>Login</th>
                <th style={{ textAlign: 'left' }}>Acciones</th>
              </tr>
            </thead>
            <tbody>
              {visibles.map((u) => (
                <tr key={u.id}>
                  <td>{u.nombre}</td>
                  <td>{u.curp ?? '—'}</td>
                  <td>{u.telefono ?? 'Sin teléfono'}</td>
                  <td>{u.email ?? '—'}</td>
                  <td>{u.fechaNacimiento ? formatearFecha(u.fechaNacimiento) : '—'}</td>
                  <td>{u.cantidadFolios}</td>
                  <td>{u.tienePassword ? 'Sí' : 'Sin contraseña'}</td>
                  <td>
                    <div className="actions">
                      <button type="button" className="btn btn-small" onClick={() => empezarEditar(u)}>
                        Editar
                      </button>
                      <button type="button" className="btn btn-small" onClick={() => abrirResetPaciente(u)}>
                        Restablecer contraseña
                      </button>
                      <button type="button" className="btn btn-small" onClick={() => void handleEliminar(u)}>
                        Borrar
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      {/* Restablecer contraseña (paciente o staff): la nueva se muestra una sola vez. */}
      {resetTarget && (
        <section className="card" style={{ marginBottom: 16 }}>
          <h2>Restablecer contraseña — {resetTarget.nombre}</h2>
          <p className="muted">
            Escribe la contraseña nueva o genera una. Al guardarla se muestra una sola vez: anótala.
            Las contraseñas guardadas son hash y no se pueden volver a ver.
          </p>
          <form className="form" onSubmit={handleResetGuardar}>
            <label>
              Contraseña nueva (mínimo 6 caracteres)
              <PasswordInput
                value={resetPassword}
                onChange={setResetPassword}
                placeholder="Mínimo 6 caracteres"
                autoComplete="new-password"
                minLength={6}
                required
              />
            </label>
            <div className="actions">
              <button
                type="button"
                className="btn btn-secondary"
                onClick={() => setResetPassword(generarPasswordLegible())}
              >
                Generar
              </button>
              <button type="submit" className="btn btn-primary" disabled={reseteando}>
                {reseteando ? 'Guardando…' : 'Guardar nueva contraseña'}
              </button>
              <button
                type="button"
                className="btn btn-secondary"
                onClick={() => {
                  setResetTarget(null);
                  setResetPassword('');
                }}
              >
                Cancelar
              </button>
            </div>
          </form>
        </section>
      )}

      {/* Solo el administrador ve (y carga) las cuentas de personal:
          el asesor solo ve la sección de pacientes de arriba. */}
      {esAdmin && (
      <section className="card" style={{ marginTop: 16 }}>
        <h2>Personal — cuentas de acceso</h2>
        <p className="muted">
          Cuentas de asesor y admin guardadas en Postgres (tabla usuarios_staff, migración V6).
          La contraseña nunca se muestra ni se devuelve por la API: solo se escribe al crear
          la cuenta o al cambiarla. No se puede borrar al último administrador ni tu propia cuenta.
        </p>

        {mensajeStaff && <p className="notice" role="status">{mensajeStaff}</p>}
        {errorStaff && <p className="notice" role="alert">{errorStaff}</p>}

        <div className="actions" style={{ marginBottom: 16 }}>
          <button type="button" className="btn btn-secondary" onClick={() => void cargarStaff()} disabled={cargandoStaff}>
            Actualizar
          </button>
          <button type="button" className="btn btn-primary" onClick={empezarCrearStaff}>
            Nueva cuenta
          </button>
        </div>

        <h3>{editandoStaffId ? 'Editar cuenta' : 'Crear cuenta'}</h3>
        <form onSubmit={handleGuardarStaff} className="form">
          <label>
            Nombre
            <input value={staffNombre} onChange={(e) => setStaffNombre(e.target.value)} required />
          </label>
          <label>
            Usuario
            <input
              value={staffUsuario}
              onChange={(e) => setStaffUsuario(e.target.value)}
              placeholder="asesor"
              required
            />
          </label>
          <label>
            Rol
            <select value={staffRol} onChange={(e) => setStaffRol(e.target.value)}>
              <option value="ASESOR">Asesor</option>
              <option value="ADMINISTRADOR">Administrador</option>
              <option value="DOCTOR">Doctor</option>
            </select>
          </label>
          <label>
            Contraseña {editandoStaffId ? '(dejar vacía para no cambiarla)' : '(mínimo 6 caracteres)'}
            <PasswordInput
              value={staffPassword}
              onChange={setStaffPassword}
              placeholder={editandoStaffId ? 'Sin cambio' : 'Mínimo 6 caracteres'}
              autoComplete="new-password"
            />
          </label>
          <div className="actions">
            <button type="submit" className="btn btn-primary" disabled={guardandoStaff}>
              {guardandoStaff ? 'Guardando…' : editandoStaffId ? 'Guardar cambios' : 'Crear cuenta'}
            </button>
            {editandoStaffId && (
              <button type="button" className="btn btn-secondary" onClick={limpiarFormularioStaff}>
                Cancelar edición
              </button>
            )}
          </div>
        </form>

        {cargandoStaff && staff.length === 0 && <p className="muted">Cargando personal…</p>}
        {!cargandoStaff && staff.length === 0 && <p className="muted">No hay cuentas de personal.</p>}

        {staff.length > 0 && (
          <table style={{ width: '100%', borderCollapse: 'collapse', marginTop: 16 }}>
            <thead>
              <tr>
                <th style={{ textAlign: 'left' }}>Usuario</th>
                <th style={{ textAlign: 'left' }}>Nombre</th>
                <th style={{ textAlign: 'left' }}>Rol</th>
                <th style={{ textAlign: 'left' }}>Creada</th>
                <th style={{ textAlign: 'left' }}>Acciones</th>
              </tr>
            </thead>
            <tbody>
              {staff.map((s) => (
                <tr key={s.id}>
                  <td>{s.usuario}</td>
                  <td>{s.nombre}</td>
                  <td>{s.rol}</td>
                  <td>{s.createdAt ? formatearFecha(s.createdAt) : '—'}</td>
                  <td>
                    <div className="actions">
                      <button type="button" className="btn btn-small" onClick={() => empezarEditarStaff(s)}>
                        Editar
                      </button>
                      <button type="button" className="btn btn-small" onClick={() => abrirResetStaff(s)}>
                        Restablecer contraseña
                      </button>
                      <button type="button" className="btn btn-small" onClick={() => void handleEliminarStaff(s)}>
                        Borrar
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      )}
    </div>
  );
}
