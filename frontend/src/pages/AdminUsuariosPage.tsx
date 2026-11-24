import { useCallback, useEffect, useState } from 'react';
import {
  actualizarStaffAdmin,
  actualizarUsuarioAdmin,
  crearStaffAdmin,
  crearUsuarioAdmin,
  eliminarStaffAdmin,
  eliminarUsuarioAdmin,
  getStaffAdmin,
  getUsuariosAdmin,
  isApiError,
  normalizarTelefono,
} from '../api';
import type { StaffAdmin, StaffAdminInput, UsuarioAdmin, UsuarioAdminInput } from '../api';
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

  const [editandoId, setEditandoId] = useState<string | null>(null);
  const [nombre, setNombre] = useState('');
  const [telefono, setTelefono] = useState('');
  const [email, setEmail] = useState('');
  const [fechaNacimiento, setFechaNacimiento] = useState('');
  const [password, setPassword] = useState('');
  const [guardando, setGuardando] = useState(false);

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

  useEffect(() => {
    void cargarStaff();
  }, [cargarStaff]);

  function limpiarFormulario() {
    setEditandoId(null);
    setNombre('');
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
    setTelefono(u.telefono);
    setEmail(u.email ?? '');
    setFechaNacimiento(u.fechaNacimiento ?? '');
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
    const input: UsuarioAdminInput = {
      nombre: nombre.trim(),
      telefono: normalizarTelefono(telefono),
      email: email.trim() || undefined,
      fechaNacimiento: fechaNacimiento || undefined,
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
      `¿Borrar a ${u.nombre} (${u.telefono})?\n\n` +
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

  const q = busqueda.trim().toLowerCase();
  const visibles = q
    ? usuarios.filter(
        (u) =>
          u.nombre.toLowerCase().includes(q) ||
          u.telefono.replace(/\D/g, '').includes(q.replace(/\D/g, '')),
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

      <div className="card form-inline" style={{ marginBottom: 16 }}>
        <input
          value={busqueda}
          onChange={(e) => setBusqueda(e.target.value)}
          placeholder="Buscar por nombre o teléfono"
          aria-label="Buscar usuario"
        />
        <button type="button" className="btn btn-secondary" onClick={() => void cargar()} disabled={cargando}>
          Actualizar
        </button>
        <button type="button" className="btn btn-primary" onClick={empezarCrear}>
          Nuevo usuario
        </button>
      </div>

      <section className="card" style={{ marginBottom: 16 }}>
        <h2>{editandoId ? 'Editar usuario' : 'Crear usuario'}</h2>
        <form onSubmit={handleGuardar} className="form">
          <label>
            Nombre completo
            <input value={nombre} onChange={(e) => setNombre(e.target.value)} required />
          </label>
          <label>
            Teléfono (10 dígitos, sin +52)
            <input value={telefono} onChange={(e) => setTelefono(e.target.value)} placeholder="2220000001" required />
          </label>
          <label>
            Correo electrónico
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="tu@correo.com" />
          </label>
          <label>
            Fecha de nacimiento
            <input type="date" value={fechaNacimiento} onChange={(e) => setFechaNacimiento(e.target.value)} />
          </label>
          <label>
            Contraseña {editandoId ? '(dejar vacía para no cambiarla)' : '(mínimo 6 caracteres)'}
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
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
                  <td>{u.telefono}</td>
                  <td>{u.email ?? '—'}</td>
                  <td>{u.fechaNacimiento ?? '—'}</td>
                  <td>{u.cantidadFolios}</td>
                  <td>{u.tienePassword ? 'Sí' : 'Sin contraseña'}</td>
                  <td>
                    <div className="actions">
                      <button type="button" className="btn btn-small" onClick={() => empezarEditar(u)}>
                        Editar
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
            </select>
          </label>
          <label>
            Contraseña {editandoStaffId ? '(dejar vacía para no cambiarla)' : '(mínimo 6 caracteres)'}
            <input
              type="password"
              value={staffPassword}
              onChange={(e) => setStaffPassword(e.target.value)}
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
                  <td>{s.createdAt ? s.createdAt.slice(0, 10) : '—'}</td>
                  <td>
                    <div className="actions">
                      <button type="button" className="btn btn-small" onClick={() => empezarEditarStaff(s)}>
                        Editar
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
    </div>
  );
}
