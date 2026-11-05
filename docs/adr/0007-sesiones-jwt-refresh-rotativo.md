# ADR 0007 — Sesiones: access JWT corto + refresh token con rotación

Estado: aceptado (rama `simple`). Completa el ADR 0002 (identidad solo del JWT).

## Contexto
La identidad de todas las decisiones de acceso sale del JWT. Un access token de larga vida amplía la ventana de robo; pedir login cada pocos minutos es inviable. El propietario decidió mantener **refresh token** con rotación.

## Decisión
1. **Access token**: JWT firmado, vida **10-15 minutos**, con `sub` (id de usuario) y `role`. Va en la cabecera `Authorization` y el frontend lo guarda solo en memoria.
2. **Refresh token**: valor opaco aleatorio (≥ 256 bits), vida de 7 días, en cookie `HttpOnly; Secure; SameSite=Strict` con `Path=/api/v1/auth`. En BD solo se guarda su **hash SHA-256** (`refresh_tokens.token_hash`, UNIQUE); nunca el token en claro.
3. **Rotación en cada uso**: `POST /api/v1/auth/refresh` valida el token, marca la fila como revocada (`revoked_at`, `replaced_by` = nueva fila), emite un refresh nuevo (misma `family_id`) y un access nuevo. Las dos escrituras van en una transacción.
4. **Detección de reutilización**: si llega un refresh ya rotado o revocado, se **revoca toda la familia** (`UPDATE ... SET revoked_at = now() WHERE family_id = ?`), se responde 401 y se audita `TOKEN_REUSE_DETECTED`. El usuario debe iniciar sesión de nuevo.
5. **Logout**: `POST /api/v1/auth/logout` revoca la familia del refresh recibido y borra la cookie (204 siempre, para no revelar estado). El access token ya emitido caduca solo (≤ 15 min).
6. Un refresh caducado, desconocido o de un usuario inactivo ⇒ 401 y no se emite nada. Al refrescar se relee el rol del usuario en BD (un cambio de rol se aplica como mucho al siguiente refresh).
7. Tiempo: emisión y caducidad usan **tiempo real**, no el `Clock` de negocio (ADR 0006). Las cookies y los tokens no se registran en logs.
8. Auditoría (`audit_logs`): `LOGIN`, `LOGIN_FAILED`, `TOKEN_REFRESH`, `TOKEN_REUSE_DETECTED`, `LOGOUT`; solo metadatos.
9. CSRF: `SameSite=Strict` y `Path` acotado; el endpoint de refresh solo acepta `POST`. No hay otras operaciones autenticadas por cookie.

## Alternativas
| Alternativa | Por qué no |
|---|---|
| Solo access token de vida larga | Ventana de robo grande; sin revocación |
| Refresh sin rotación | Un token robado sirve hasta caducar y no se detecta |
| Sesiones de servidor | Cambia el modelo (JWT es el requisito del brief) |
| Refresh en `localStorage` | Accesible por XSS; la cookie HttpOnly lo evita |

## Consecuencias
- (+) Revocación real (logout, reutilización) con una tabla pequeña; el control de acceso sigue dependiendo solo del JWT.
- (−) Una tabla más (`refresh_tokens`) y una limpieza opcional de filas caducadas (sin job obligatorio).
- (−) Una carrera entre dos pestañas que refrescan a la vez puede parecer reutilización y cerrar la familia; se acepta (nuevo login) en un portfolio de 7 usuarios.
