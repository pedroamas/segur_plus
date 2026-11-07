import type { ReactNode } from 'react'
import { NavLink, useNavigate } from 'react-router-dom'
import { ROLE_LABEL } from '../api/labels'
import { useAuth } from '../auth/AuthContext'

export default function Layout({ children }: { children: ReactNode }) {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  return (
    <>
      <header className="topbar">
        <strong className="brand">SegurPlus</strong>
        <nav aria-label="Principal">
          <NavLink to="/" end>Panel</NavLink>
          <NavLink to="/documentos">Documentos</NavLink>
          <NavLink to="/chat">Chat <span className="badge">No disponible</span></NavLink>
        </nav>
        <div className="user">
          <span data-testid="user-name">{user?.fullName}</span>
          <span className="muted" data-testid="user-role">{user ? (ROLE_LABEL[user.role] ?? user.role) : ''}</span>
          <button
            type="button"
            onClick={async () => {
              await logout()
              navigate('/login', { replace: true })
            }}
          >
            Cerrar sesión
          </button>
        </div>
      </header>
      <main>{children}</main>
    </>
  )
}
