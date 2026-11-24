import { useState } from 'react';

/**
 * Input de contraseña con ojo para mostrar/ocultar lo tecleado.
 * La contraseña guardada nunca se muestra (es hash PBKDF2 en la BD):
 * esto solo deja ver lo que se está escribiendo en el momento.
 */
export default function PasswordInput({
  value,
  onChange,
  placeholder,
  autoComplete,
  required,
  minLength,
}: {
  value: string;
  onChange: (valor: string) => void;
  placeholder?: string;
  autoComplete?: string;
  required?: boolean;
  minLength?: number;
}) {
  const [visible, setVisible] = useState(false);
  return (
    <span style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
      <input
        type={visible ? 'text' : 'password'}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        autoComplete={autoComplete}
        required={required}
        minLength={minLength}
        style={{ flex: 1 }}
      />
      <button
        type="button"
        className="btn btn-small btn-secondary"
        onClick={() => setVisible((v) => !v)}
        aria-label={visible ? 'Ocultar contraseña' : 'Mostrar contraseña'}
        title={visible ? 'Ocultar contraseña' : 'Mostrar contraseña'}
      >
        {visible ? 'Ocultar' : 'Mostrar'}
      </button>
    </span>
  );
}
