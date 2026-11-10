# Evaluación de F4 (chat con RAG autorizado)

`questions.json`: 32 preguntas etiquetadas (18 con documentos esperados, 9 de rechazo, 7 de herramientas), sembradas desde [UC §6](../docs/requirements/use-cases.md). `run_eval.py` las lanza contra el backend real (:8100 → Dify → Gemini), por usuario del seed, y mide:

| Métrica | Definición |
|---|---|
| recall@5 | preguntas con `expected_docs`: algún documento esperado entre los 5 primeros del contexto (orden de relevancia, ya re-autorizados) |
| citas válidas | cada fuente citada (`cited`) existe, es legible por el usuario (`GET /documents/{id}` = 200) y no está prohibida |
| fugas | fuente prohibida o ilegible; `forbidden_strings` en el texto; `[CÓDIGO]` prohibido en el texto. **Meta: 0** |
| rechazo / hechos | informativas (heurística de «no tengo información»; cifras esperadas en la respuesta) |

```
python3 evaluation/run_eval.py [--only Q01,Q05] [--retries 4] [--out evaluation/results/x.json]
```
Con 503/429/timeout del motor reintenta con espera creciente y lo cuenta (`engine_retries`, `engine_failures_after_retries`). Sale con código 1 si recall@5 < 0,8, hay citas inválidas o fugas, o falló alguna pregunta. Las preguntas viven separadas del corpus; `results/` guarda las corridas (respuestas sobre documentos ficticios).

## F6: repetición sobre la build final y corpus de inyección
- Con la cuota gratuita de Gemini (≈20 peticiones/día, 5/min por modelo) cada pregunta cuesta 2 peticiones: `--only Q03,Q07,… --retries 0 --pause 30` mide un subconjunto sin reintentos que gasten cuota. `results/f6-subset.json` (10 preguntas: recall@5 6/7, citas 7/7, fugas 0); las 22 restantes **no se midieron**. `results/run1.json` sigue siendo la corrida completa de F4.
- `injection/corpus.json`: corpus de prompt injection (12 documentos + 8 consultas) que consume `PromptInjectionTest` (modelo simulado, 7 usuarios). `injection/run_real.py`: 2 casos con Dify y Gemini reales (sube y **borra** sus documentos). Resultado en `results/f6-injection-real.json`. Detalle en [`docs/security/f6-resultados.md`](../docs/security/f6-resultados.md).

