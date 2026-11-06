/**
 * F3: el chat es visible pero NO disponible. Esta pantalla no importa el cliente del API ni realiza ninguna petición
 * (la conexión real con /api/v1/chat llega en F5).
 */
export default function ChatPage() {
  return (
    <section aria-labelledby="chat-title">
      <h1 id="chat-title">Chat</h1>
      <div className="card unavailable" role="status" data-testid="chat-unavailable">
        <h2>Chat no disponible</h2>
        <p>El asistente documental todavía no está disponible. Mientras tanto puedes consultar los documentos desde la sección Documentos.</p>
      </div>
      <form onSubmit={(e) => e.preventDefault()} aria-label="Mensaje al asistente (deshabilitado)">
        <label htmlFor="chat-input">Pregunta al asistente</label>
        <textarea id="chat-input" rows={3} disabled placeholder="No disponible" />
        <button type="submit" disabled>Enviar</button>
      </form>
    </section>
  )
}
