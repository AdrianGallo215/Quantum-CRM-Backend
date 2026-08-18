---
name: revisar-requerimiento
description: Usar cuando llega un ticket de requerimiento (típicamente el JSON de redactar-requerimiento, o un pedido de cambio ya escrito) para el backend del CRM de Quantum y hay que triarlo contra el estado real del repo antes de planear o implementar. También cuando el usuario dice "revísalo", "tríalo", "esto es suficiente para implementar?", o pide evaluar si un cambio vale la pena o si hay una forma más corta.
---

# Revisar requerimiento

## Overview

Triage de un ticket contra el repo **real**, antes de que exista una línea de código.

**Principio central:** el ticket dice lo que alguien pidió; el repo dice lo que hay. Tu trabajo es cruzar los dos y sacar a la luz lo que no cuadra — antes de implementar, porque esta app está en producción con usuarios reales y cada merge a `main` se despliega solo.

**No implementas nada.** Tu salida es un informe con un veredicto.

**"BLOQUEADO — faltan respuestas" es un resultado exitoso**, no un fracaso ni una falta de iniciativa. Un triage que devuelve tres preguntas bien fundadas vale más que uno que adivina y da luz verde.

## Cómo trabajar

### 1. Lee el ticket sin creerle nada sobre el código

Los campos `segun_solicitante` y `areas_sospechadas` son **declaraciones sin verificar**, por diseño. Trátalos como hipótesis a comprobar, no como contexto dado. Lo mismo si el ticket llegó sin esos marcadores: nada de lo que afirme sobre el estado actual cuenta hasta que lo veas en el repo.

### 2. Verifica contra el repo — docs **y** código

Los docs relevantes según el dominio que toca el pedido:

| Doc | Qué te dice |
|---|---|
| `docs/reglas_negocio.md` | Comportamiento esperado. La fuente de verdad del negocio. |
| `docs/contrato_api.md` | Firmas de endpoints, errores, y §25 el changelog del contrato. |
| `docs/matriz_permisos.md` | Qué rol puede ver y hacer qué. |
| `src/main/resources/db/migration/` | **La verdad del schema.** |
| `CLAUDE.md` | Reglas que no se rompen, y la lista de "fuera del MVP". |
| `docs/DEVOPS-backend.md` | §7 entornos, §9 rollback de código y de datos. |

Y después **el código**, siempre. Trampas reales de este repo, comprobadas:

- **Los docs derivan del código.** `contrato_api.md` describía el rol como `gerente` mucho después de que V25 lo renombrara a `gerencia`. Si doc y código discrepan, **el código es la realidad y la discrepancia es un hallazgo que reportas**.
- **Los docs omiten cosas que existen.** El rol `otro` está en el enum `rol_empleado` de la base y no aparecía en ninguna fila de `matriz_permisos.md`. Que algo no esté documentado no significa que no exista; significa que nadie sabe qué debería hacer.
- **`docs/schema.sql` y `docs/migrations/` van atrasados.** `docs/migrations/` llega a V19 y el repo va muy por delante. Para schema, lee las migraciones reales.
- **"Fuera del MVP" ya no se aplica a ciegas.** La app pasó el MVP y está en producción. Si el pedido cae en esa lista (o en el apéndice de endpoints no implementados del contrato), no lo rechaces: reporta que estaba excluido y pregunta si la exclusión sigue vigente.

### 3. Caza las palabras elásticas

Busca en el ticket términos de alcance sin acotar: "solo lectura", "solo sus registros", "todos", "no debería ver", "simplificar", "igual que X". Por cada uno, resuelve contra el repo hasta dónde llega hoy y **cuáles son las lecturas posibles del pedido**. Si quedan dos lecturas con consecuencias distintas, es pregunta bloqueante — no elijas una.

### 4. Emite tres juicios separados

No los mezcles en una recomendación única y difusa. Son tres preguntas distintas y cada una lleva su propia respuesta:

- **¿Vale la pena?** ¿El problema de negocio justifica el cambio, o el costo/riesgo supera el beneficio?
- **¿Hay un atajo?** ¿Se resuelve ajustando algo que ya existe — un permiso, un filtro, un default, un doc — en vez de construir algo nuevo?
- **¿Falta un complemento?** ¿Hay algo adyacente que, si no entra ahora, genera deuda o un segundo ticket en dos semanas?

Cada uno puede responder "nada que agregar". Lo que no puede es faltar.

### 5. Corre los chequeos de riesgo de este proyecto

- **Visibilidad e IDOR:** ¿el cambio afecta qué registros ve un rol? El filtro va **en la query**, nunca en memoria, y un recurso ajeno responde **404, no 403**.
- **Invariantes protegidas:** ¿toca `monto_total` (se calcula, nunca se acepta como input), `estado_cartera` (solo vía `actualizarEstadoCartera()`), transiciones de estado, o el paso a `facturado`? Si sí, dilo explícito.
- **Fronteras de módulo:** ¿obliga a un módulo a leer tablas o entidades de otro? Eso lo bloquea ArchUnit.
- **Migración:** ¿necesita cambio de schema? Forward-only, y si además **altera o borra datos existentes**, aplica el paso de `DEVOPS-backend.md §7` (restaurar dump de producción en local antes del PR) y el backup manual de §9.2.
- **Contrato:** ¿cambia la forma de un request/response, un código de error, un status, o agrega/quita endpoint? Entonces toca `contrato_api.md` **y** su §25, y hay que clasificarlo breaking / non-breaking para el frontend.
- **Superficie de seguridad:** ¿toca auth, JWT, roles, o expone un campo que hoy no se devuelve?

### 6. Emite el veredicto

Uno de tres, obligatorio:

| Veredicto | Cuándo |
|---|---|
| `BLOQUEADO` | Hay al menos una pregunta cuya respuesta cambia qué se implementa. **Resultado válido y frecuente.** |
| `LISTO_CON_OBSERVACIONES` | Se puede implementar; hay hallazgos, deriva de docs o riesgos que el implementador debe tener presentes. |
| `LISTO` | Alcance cerrado, verificado, sin ambigüedad pendiente. |

## Formato del informe

```
## TRIAGE — [título del ticket]

### VEREDICTO
[BLOQUEADO | LISTO_CON_OBSERVACIONES | LISTO] — [una frase de por qué]

### VERIFICACIÓN CONTRA EL REPO
Por cada requisito del ticket (R1, R2…):
- [R1] Afirmado: "..." → Real: [qué encontraste] ([archivo:línea]) → [confirma | corrige | no existe]

### HALLAZGOS NO PEDIDOS
- Deriva doc/código, cosas indocumentadas, inconsistencias encontradas de paso.
  Cada una: dentro del alcance de este ticket, o ticket aparte.

### PREGUNTAS BLOQUEANTES
Redactadas para que producto las responda sin leer código. Por cada una:
qué se decide, y qué cambia en la implementación según la respuesta.
(Si no hay ninguna, decir "ninguna".)

### LOS TRES JUICIOS
- ¿Vale la pena?: ...
- ¿Hay un atajo?: ...
- ¿Falta un complemento?: ...

### RIESGO Y ALCANCE TÉCNICO
- Visibilidad/IDOR · invariantes protegidas · fronteras de módulo ·
  migración (¿toca datos existentes?) · contrato y §25 · seguridad
- Módulos y docs que habría que tocar.

### SI SE DESBLOQUEA
Orden sugerido de trabajo, a grano grueso, y qué se prueba primero.
No es un plan de implementación.
```

## Nunca

- **Nunca escribas código, ni tests, ni migraciones.** Ni un esqueleto, ni "para ilustrar".
- **Nunca crees la rama ni edites los docs.** El triage no cambia el repo. Los arreglos de deriva que encuentres se reportan y se ejecutan en el PR de implementación.
- **Nunca des `LISTO` con una pregunta abierta sin responder.** Si el ticket traía `preguntas_abiertas` no resueltas, el piso es `BLOQUEADO` salvo que el repo las responda de forma inequívoca — y entonces muestras dónde.
- **Nunca decidas tú una pregunta de producto.** Recomendar sí, y se marca como recomendación dentro de la pregunta. Decidir, no.
- **Nunca te apoyes solo en los docs.** Un triage hecho únicamente sobre `docs/` reproduce la deriva en vez de detectarla.

| Excusa | Realidad |
|---|---|
| "El ticket ya dice qué existe hoy" | Por diseño eso no está verificado. Verificarlo es literalmente tu trabajo. |
| "La duda es menor, elijo la opción conservadora" | En visibilidad de datos, la opción "conservadora" también rompe: de menos deja gente sin su trabajo diario, de más abre un hueco. Pregunta. |
| "Bloquear no ayuda, mejor avanzo" | Avanzar sobre una ambigüedad se paga en producción, con usuarios reales y un deploy automático. |
| "Ya vi el problema, escribo el fix de una" | Sales del triage y nadie revisa el alcance. El fix va en su PR, con TDD y rama propia. |
| "Los docs son la fuente de verdad, con eso basta" | Los docs de este repo tienen deriva comprobada. El código manda. |
| "Es un cambio chico, no hace falta el checklist" | Los cambios chicos de permisos son justo los que abren huecos de visibilidad. |
| "Está fuera del MVP, lo rechazo" | Ya no estamos en MVP. Reporta la exclusión y pregunta si sigue vigente. |

## Señales de que te estás desviando

- Escribiste una firma de función, un `@PreAuthorize` o un SQL.
- Diste `LISTO` sin abrir un solo archivo del repo.
- Tus tres juicios quedaron fundidos en una sola recomendación.
- Repetiste una afirmación del ticket como si fuera un hecho verificado.
- Estás editando `matriz_permisos.md` o `contrato_api.md`.
- No hay ninguna pregunta y tampoco verificaste nada.

**Todas significan: para, vuelve al paso 2, verifica antes de concluir.**

## Después del triage

Con veredicto `LISTO` o `LISTO_CON_OBSERVACIONES`, el trabajo sigue el flujo de `DEVOPS-backend.md §2`: rama propia (`feature/xxx` o `fix/xxx`), TDD, gates locales, PR. Nunca commit directo a `main`.

Con `BLOQUEADO`, entrega las preguntas y detente. No empieces "lo que no depende de la respuesta" — en un ticket de permisos o visibilidad, casi nada es independiente.
