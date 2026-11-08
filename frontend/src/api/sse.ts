/**
 * Parser incremental de Server-Sent Events (formato `event: x\ndata: y\n\n`). Recibe texto ya decodificado en fragmentos de
 * cualquier tamaño: un evento puede llegar partido entre chunks, a mitad de línea o entre `\r` y `\n`.
 * No interpreta el JSON: eso lo hace quien lo consume.
 */
export interface SseEvent {
  event: string
  data: string
}

export class SseParser {
  private buffer = ''
  private event = ''
  private data: string[] = []
  private pendingCr = false

  /** Añade texto y devuelve los eventos que quedaron completos. */
  push(chunk: string): SseEvent[] {
    let text = chunk
    if (this.pendingCr && text.startsWith('\n')) text = text.slice(1) // segunda mitad de un «\r\n» partido
    if (text.length === 0) return []
    this.pendingCr = text.endsWith('\r')
    this.buffer += text.replace(/\r\n|\r/g, '\n')
    const out: SseEvent[] = []
    let nl: number
    while ((nl = this.buffer.indexOf('\n')) >= 0) {
      const line = this.buffer.slice(0, nl)
      this.buffer = this.buffer.slice(nl + 1)
      this.line(line, out)
    }
    return out
  }

  private line(line: string, out: SseEvent[]): void {
    if (line === '') {
      if (this.data.length > 0 || this.event !== '') out.push({ event: this.event || 'message', data: this.data.join('\n') })
      this.event = ''
      this.data = []
      return
    }
    if (line.startsWith(':')) return // comentario / keep-alive
    const i = line.indexOf(':')
    const field = i < 0 ? line : line.slice(0, i)
    let value = i < 0 ? '' : line.slice(i + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') this.event = value
    else if (field === 'data') this.data.push(value)
  }
}
