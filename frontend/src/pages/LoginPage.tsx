import { useState, type FormEvent } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/AuthContext'

export default function LoginPage() {
  const { status, login, notice } = useAuth()
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  if (status === 'authenticated') return <Navigate to="/" replace />

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await login(username.trim(), password)
      navigate('/', { replace: true })
    } catch (err) {
      setError(err instanceof ApiError && err.status === 401 ? 'Usuario o contraseña incorrectos.' : 'No se pudo iniciar sesión. Inténtalo de nuevo.')
    } finally {
      setBusy(false)
      setPassword('')
    }
  }

  return (
    <main className="login">
      <h1>SegurPlus</h1>
      <form onSubmit={submit} aria-label="Iniciar sesión">
        {notice && <p role="status" className="muted" data-testid="session-notice">{notice}</p>}
        <label htmlFor="username">Usuario (código de empleado)</label>
        <input id="username" name="username" autoComplete="username" required value={username} onChange={(e) => setUsername(e.target.value)} />
        <label htmlFor="password">Contraseña</label>
        <input id="password" name="password" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        {error && <p role="alert" className="error">{error}</p>}
        <button type="submit" disabled={busy}>{busy ? 'Entrando…' : 'Entrar'}</button>
      </form>
    </main>
  )
}
