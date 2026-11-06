import { Navigate, Outlet, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import Layout from './components/Layout'
import ChatPage from './pages/ChatPage'
import DashboardPage from './pages/DashboardPage'
import DocumentDetailPage from './pages/DocumentDetailPage'
import DocumentsPage from './pages/DocumentsPage'
import LoginPage from './pages/LoginPage'
import NotFoundPage from './pages/NotFoundPage'

/** Solo decide qué pantalla mostrar (login o contenido): los permisos los aplica siempre el API. */
function RequireSession() {
  const { status } = useAuth()
  if (status === 'loading') return <p role="status" className="center">Cargando…</p>
  if (status === 'anonymous') return <Navigate to="/login" replace />
  return (
    <Layout>
      <Outlet />
    </Layout>
  )
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireSession />}>
        <Route index element={<DashboardPage />} />
        <Route path="documentos" element={<DocumentsPage />} />
        <Route path="documentos/:id" element={<DocumentDetailPage />} />
        <Route path="chat" element={<ChatPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
