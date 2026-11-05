# ADR 0001 — Monolito con paquetes por funcionalidad

Estado: aceptado (revisado en la rama `simple`). **Reemplaza** la versión de F0 «Monolito modular con Spring Modulith» (14 módulos), descartada por el recorte de alcance.

## Contexto
SegurPlus es un portfolio de una persona, con 7 usuarios y un despliegue por `docker compose`. Lo que debe demostrar es la seguridad y el RAG con permisos, no una arquitectura de 14 módulos. Hace falta una estructura que se entienda a primera vista y una política de acceso única que atraviese REST, herramientas de IA y recuperación RAG.

## Decisión
Un único despliegue Spring Boot (Java 21), un único módulo Maven, con **cinco paquetes por funcionalidad** más `shared`:

`auth` (login, JWT, `AccessPolicyService`) · `documents` (metadatos, MinIO, preview, descarga) · `ai` (AiGateway, indexador, chat, conversaciones) · `data` (clientes, pólizas, comisiones, finanzas, objetivos) · `audit` · `shared` (Clock, errores, Money).

Reglas simples ([03-components](../architecture/03-components.md)):
1. Dependencias acíclicas: `ai → documents, data, auth, audit`; `documents` y `data → auth, audit`; `auth → audit`; todos → `shared`.
2. Un paquete solo usa los **servicios públicos** de otro; los repositorios no son públicos.
3. Los controladores devuelven DTOs, no entidades.
4. Sin `Instant.now()`/`LocalDate.now()` fuera de `shared` (ADR 0006).
5. **ArchUnit es opcional** para vigilar 1-4; no hay Spring Modulith.

## Alternativas
| Alternativa | Por qué no |
|---|---|
| Spring Modulith con 14 módulos (versión F0) | Complejidad desproporcionada para 11 tablas y 7 usuarios; el valor del portfolio está en la seguridad |
| Microservicios | Coste operativo sin beneficio; dificulta que una sola política gobierne todo |
| Capas globales (controller/service/repository) | No muestra fronteras funcionales; los ciclos aparecen solos |

## Consecuencias
- (+) Estructura legible; una transacción puede abarcar varias tablas de `data`.
- (+) `ai` se podría extraer: solo depende de servicios públicos.
- (−) La disciplina de fronteras depende de la revisión y, opcionalmente, de ArchUnit.
