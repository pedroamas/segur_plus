import { describe, expect, it } from 'vitest'
import { SseParser } from './sse'

const wire = 'event: conversation\ndata: {"a":1}\n\nevent: delta\ndata: {"text":"Hola"}\n\nevent: done\ndata: {}\n\n'
const expected = [
  { event: 'conversation', data: '{"a":1}' },
  { event: 'delta', data: '{"text":"Hola"}' },
  { event: 'done', data: '{}' },
]

describe('parser SSE', () => {
  it('parsea un bloque completo', () => {
    expect(new SseParser().push(wire)).toEqual(expected)
  })

  it('da el mismo resultado partiendo el flujo en cualquier punto (todos los cortes de 1 y 2 trozos)', () => {
    for (let i = 0; i <= wire.length; i++) {
      const p = new SseParser()
      expect([...p.push(wire.slice(0, i)), ...p.push(wire.slice(i))]).toEqual(expected)
    }
  })

  it('un carácter por chunk', () => {
    const p = new SseParser()
    const out = [...wire].flatMap((c) => p.push(c))
    expect(out).toEqual(expected)
  })

  it('no emite un evento hasta ver la línea en blanco', () => {
    const p = new SseParser()
    expect(p.push('event: delta\ndata: {"text":"a"}\n')).toEqual([])
    expect(p.push('\n')).toEqual([{ event: 'delta', data: '{"text":"a"}' }])
  })

  it('admite CRLF y CR, también con el \\r\\n partido entre chunks', () => {
    const crlf = 'event: x\r\ndata: 1\r\n\r\n'
    expect(new SseParser().push(crlf)).toEqual([{ event: 'x', data: '1' }])
    const p = new SseParser()
    expect([...p.push('event: x\r'), ...p.push('\ndata: 1\r'), ...p.push('\n\r'), ...p.push('\n')]).toEqual([{ event: 'x', data: '1' }])
    expect(new SseParser().push('event: x\rdata: 2\r\r')).toEqual([{ event: 'x', data: '2' }])
  })

  it('une varias líneas data con \\n, ignora comentarios y quita solo un espacio inicial', () => {
    const out = new SseParser().push(': keep-alive\nevent: m\ndata:  uno\ndata: dos\n\n')
    expect(out).toEqual([{ event: 'm', data: ' uno\ndos' }])
  })

  it('evento sin nombre = message; bloques vacíos no generan eventos', () => {
    expect(new SseParser().push('data: x\n\n\n\n')).toEqual([{ event: 'message', data: 'x' }])
  })

  it('un evento incompleto al cerrar el stream no se entrega', () => {
    expect(new SseParser().push('event: done\ndata: {}')).toEqual([])
  })
})
