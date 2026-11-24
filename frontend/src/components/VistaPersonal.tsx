import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getPerfilYo, getToken, isApiError } from '../api';

// Puerta de personal para Clínica, Asesor, Admin y Doctor: solo los
// roles indicados (por defecto ASESOR o ADMINISTRADOR). Sin sesión, o
// con sesión de paciente, NO se carga ningún dato: la página misma
// pide el login de personal (o avisa que es vista de personal). Mismo
// estilo de mensaje y botones que AppPage/HistorialPage usan para el
// estado sin sesión.
type Estado = 'cargando' | 'sin-sesion' | 'paciente' | 'personal' | 'error';

const ROLES_POR_DEFECTO = ['ASESOR', 'ADMINISTRADOR'];
const ETIQUETA_POR_DEFECTO = 'el personal de Salud Digna (asesor o admin)';

export default function VistaPersonal({
  titulo,
  children,
  roles = ROLES_POR_DEFECTO,
  etiqueta = ETIQUETA_POR_DEFECTO,
}: {
  titulo: string;
  children: React.ReactNode;
  roles?: string[];
  etiqueta?: string;
}) {
  const [estado, setEstado] = useState<Estado>('cargando');

  useEffect(() => {
    async function verificar() {
      if (!getToken()) {
        setEstado('sin-sesion');
        return;
      }
      try {
        const perfil = await getPerfilYo();
        if (perfil.paciente || perfil.rol === 'PACIENTE') {
          setEstado('paciente');
        } else if (roles.includes(perfil.rol)) {
          setEstado('personal');
        } else {
          setEstado('sin-sesion');
        }
      } catch (err) {
        if (isApiError(err) && err.status === 401) setEstado('sin-sesion');
        else setEstado('error');
      }
    }
    void verificar();
    // roles es estable en cada uso (literal en el JSX): no se re-verifica por él.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (estado === 'cargando') {
    return (
      <div>
        <h1>{titulo}</h1>
        <p className="muted">Verificando tu sesión…</p>
      </div>
    );
  }

  if (estado === 'sin-sesion') {
    // El texto de "quién puede entrar" sale de los roles: asesor o
    // admin por defecto; doctor o admin en la vista del doctor.
    const quien = roles
      .map((r) => (r === 'ADMINISTRADOR' ? 'admin' : r.toLowerCase()))
      .join(' o ');
    return (
      <div>
        <h1>{titulo}</h1>
        <p className="muted">
          Esta vista es solo para {etiqueta}. Inicia sesión como {quien}.
        </p>
        <div className="actions">
          <Link className="btn btn-primary" to="/login">
            Iniciar sesión
          </Link>
          <Link className="btn btn-secondary" to="/">
            Volver a inicio
          </Link>
        </div>
      </div>
    );
  }

  if (estado === 'paciente') {
    return (
      <div>
        <h1>{titulo}</h1>
        <p className="muted">
          Tu sesión es de paciente: esta vista es del personal. Ve a tu App o a tu Historial.
        </p>
        <div className="actions">
          <Link className="btn btn-primary" to="/app">
            Ir a mi App
          </Link>
          <Link className="btn btn-secondary" to="/historial">
            Ver mi historial
          </Link>
        </div>
      </div>
    );
  }

  if (estado === 'error') {
    return (
      <div>
        <h1>{titulo}</h1>
        <p className="notice" role="status">
          No se pudo verificar tu sesión. Revisa que el backend esté arriba.
        </p>
        <div className="actions">
          <Link className="btn btn-primary" to="/login">
            Iniciar sesión
          </Link>
          <Link className="btn btn-secondary" to="/">
            Volver a inicio
          </Link>
        </div>
      </div>
    );
  }

  return <>{children}</>;
}
