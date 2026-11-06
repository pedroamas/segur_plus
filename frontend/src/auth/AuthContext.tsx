import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { api, setSessionLostHandler } from '../api/client'
import type { UserProfile } from '../api/types'

type Status = 'loading' | 'anonymous' | 'authenticated'

interface AuthState {
  status: Status
  user: UserProfile | null
  /** Aviso mostrado en el login cuando la sesión se perdió sin que el usuario lo pidiera. */
  notice: string | null
  login(username: string, password: string): Promise<void>
  logout(): Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<Status>('loading')
  const [user, setUser] = useState<UserProfile | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const started = useRef(false)

  useEffect(() => {
    setSessionLostHandler(() => {
      setUser(null)
      setStatus('anonymous')
      setNotice('Tu sesión ha caducado. Vuelve a iniciar sesión.')
    })
    return () => setSessionLostHandler(undefined)
  }, [])

  // Al cargar la página se intenta restaurar la sesión con la cookie de refresh (una sola vez, aunque StrictMode monte dos veces).
  useEffect(() => {
    if (started.current) return
    started.current = true
    void (async () => {
      if (await api.restore()) {
        try {
          setUser(await api.getJson<UserProfile>('/users/me'))
          setStatus('authenticated')
          return
        } catch {
          /* cae a anónimo */
        }
      }
      setStatus('anonymous')
    })()
  }, [])

  const login = useCallback(async (username: string, password: string) => {
    await api.login(username, password)
    setUser(await api.getJson<UserProfile>('/users/me'))
    setNotice(null)
    setStatus('authenticated')
  }, [])

  const logout = useCallback(async () => {
    try {
      await api.logout()
    } finally {
      setUser(null)
      setNotice(null)
      setStatus('anonymous')
    }
  }, [])

  const value = useMemo(() => ({ status, user, notice, login, logout }), [status, user, notice, login, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth fuera de AuthProvider')
  return ctx
}
