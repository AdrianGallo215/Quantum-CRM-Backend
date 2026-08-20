---
name: redactar-requerimiento
description: Usar cuando llega una petición informal de cambio, corrección, idea o nueva funcionalidad para el backend del CRM de Quantum — del dueño del producto, del equipo o agente de frontend, o de operación — y hay que convertirla en un ticket formal antes de que alguien toque el repo. También cuando el usuario dice "formaliza esto", "arma el ticket", "pásalo a JSON".
---

# Redactar requerimiento

## Overview

Convierte una petición informal en un **ticket JSON** que otra sesión pueda triar contra el repo real.

**Principio central:** este ticket registra **lo que se pidió**, no lo que es cierto del código. No verifica nada y no finge haberlo hecho. Verificar es trabajo de `revisar-requerimiento`, que corre después, en el repo, con los archivos delante.

Tu único trabajo es formalizar y preguntar. Un ticket honesto con cinco preguntas abiertas es un éxito. Un ticket que se ve completo porque rellenaste los huecos es un fallo, aunque nadie lo note hasta que se implemente mal.

## Contexto del proyecto (lo mínimo para estructurar bien)

API REST en Kotlin + Spring Boot del CRM de **Quantum Investment**, representante de buses KinWin en Perú. Venta tripartita: Quantum + cliente + financiadora. **Está en producción con usuarios reales.**

- **Regla de oro:** una venta cierra solo cuando la financiadora desembolsa — estado `facturado`. No con contrato ni orden de compra.
- **Dominios:** empresas, contactos, oportunidades, eventos, tareas, empleados, modelos, financiadoras, prospección, reportes, solicitudes, metas de venta, notificaciones, inicio, catálogo de eventos.
- **Roles:** `admin`, `gerencia`, `jdv`, `vendedor`, `analista`, `otro`.
- **Flujos sensibles** (si el pedido los roza, dilo en el ticket): cálculo de `monto_total`, `estado_cartera`, transiciones de estado de oportunidad, paso a `facturado`, límites de descuento y solicitudes de aprobación, visibilidad por rol.
- El backend es dueño del contrato de API y de la matriz de permisos; el frontend es un repo aparte que los consume.

Esto es todo el contexto que necesitas. **No sabes** qué está implementado hoy, qué migraciones existen, ni qué dicen los docs. No lo afirmes.

## Cómo trabajar

1. **Lee la petición y detecta qué falta.** Ver "Qué preguntar siempre".
2. **Pregunta.** Todas las dudas juntas, en una sola pasada si se puede, en lenguaje de negocio. Nunca sigas con una suposición.
3. **Escribe el JSON** con la forma de abajo, adaptada al requerimiento.
4. **Entrega el JSON y el handoff.**

## Qué preguntar siempre

Estas cuatro preguntas son las que, sin respuesta, hacen que un ticket se implemente mal:

| Preguntar | Por qué |
|---|---|
| **El problema de negocio, no solo la solución pedida** | Sin el "para qué", el triage no puede evaluar si hay un camino más corto ni si vale la pena. |
| **El alcance exacto de cada palabra elástica** | "solo lectura", "solo sus registros", "todos", "no debería ver", "simplificar": una palabra así sin acotar es la causa más común de hueco de seguridad o de romper el trabajo diario de alguien. Pregunta qué incluye y qué no — caso por caso, no en general. |
| **Qué es requisito cerrado y qué es decisión pendiente** | Un pedido tipo "confirmar o restringir si X puede seguir haciendo Y" es una pregunta disfrazada de tarea. Si no se resuelve ahí, va a `preguntas_abiertas`. |
| **Qué queda explícitamente fuera** | Evita que el alcance crezca solo durante la implementación. |

## Forma del JSON (guía, no esquema rígido)

Agrega, quita o renombra campos según el requerimiento: un bugfix no necesita `no_alcance`, un cambio de permisos sí necesita detalle por rol. Lo que **no** es opcional:

- `problema` — el por qué de negocio, en una o dos frases.
- `requisitos_cerrados` — lo que se pidió y quedó decidido. Cada uno con su `id` (`R1`, `R2`…) para que el triage los referencie.
- `preguntas_abiertas` — lo que quedó sin decidir. **Siempre presente**; `[]` solo si de verdad todo se resolvió en la conversación.
- Marcadores de procedencia en toda afirmación sobre el estado actual: `segun_solicitante` para lo que te dijeron, `verificar_en_triage: true` para que la siguiente sesión lo compruebe. Nunca escribas un hecho del código sin uno de los dos.

```json
{
  "titulo": "Roles analista y otro pasan a solo lectura en oportunidades y empresas",
  "origen": "producto, vía equipo de frontend",
  "fecha": "2026-08-18",
  "problema": "Analista y otro no deben poder modificar oportunidades ni empresas. Excepción: deben poder ver, sin editar, los registros donde el dueño los agregó como colaborador en una tarea.",
  "requisitos_cerrados": [
    {
      "id": "R1",
      "pedido": "Retirar a analista de la autorización para confirmar el paso a facturado.",
      "segun_solicitante": "hoy lo permite junto con admin y gerencia",
      "verificar_en_triage": true
    },
    {
      "id": "R2",
      "pedido": "Todo descuento de analista debe requerir aprobación; pierde el límite directo propio.",
      "segun_solicitante": "hoy tiene un límite directo de 3%",
      "verificar_en_triage": true
    },
    {
      "id": "R3",
      "pedido": "El filtro de visibilidad por colaborador se aplica en el backend, no solo en el frontend.",
      "nota_del_solicitante": "sin esto cualquier restricción en el cliente es cosmética, no seguridad"
    }
  ],
  "preguntas_abiertas": [
    {
      "id": "P1",
      "pregunta": "¿'solo lectura' reemplaza la visibilidad que estos roles tienen hoy sobre sus propios registros, o solo les quita el permiso de editar?",
      "por_que_bloquea": "Si la reemplaza, un analista deja de ver la cartera que gestiona a diario. Son dos implementaciones y dos niveles de riesgo distintos.",
      "decide": "producto"
    },
    {
      "id": "P2",
      "pregunta": "¿Pierden también el permiso de crear empresas y oportunidades nuevas, o solo de editar las existentes?",
      "decide": "producto"
    },
    {
      "id": "P3",
      "pregunta": "¿Siguen pudiendo crear solicitudes de aprobación bajo el nuevo modelo?",
      "planteado_por_el_solicitante_como": "confirmar o restringir",
      "decide": "producto"
    }
  ],
  "areas_sospechadas": {
    "advertencia": "sospecha del solicitante, sin verificar contra el repo",
    "endpoints": ["PATCH /oportunidades/:id/estado", "GET /oportunidades", "GET /empresas"],
    "docs": ["matriz_permisos.md", "contrato_api.md"]
  },
  "impacto_esperado_en_frontend": "Cambia qué devuelve el listado para estos roles. El solicitante lo considera breaking.",
  "no_alcance": [
    "No se toca la visibilidad de admin, gerencia, jdv ni vendedor.",
    "No se toca el módulo de reportes."
  ],
  "urgencia": "normal — sin fecha límite; la app está en producción, no se apura el deploy"
}
```

## Nunca

- **Nunca rellenes un campo con una suposición.** Si no lo sabes, pregunta. Si preguntaste y no hubo respuesta, va a `preguntas_abiertas`.
- **Nunca cierres una pregunta abierta decidiéndola tú.** Proponer una recomendación está bien, y va marcada como propuesta dentro de la pregunta — pero la pregunta sigue abierta.
- **Nunca afirmes estado actual del código como hecho.** Ni siquiera si el solicitante lo afirmó: eso es `segun_solicitante` + `verificar_en_triage`.
- **Nunca audites el repo para "completar" el ticket.** No es tu trabajo, y un ticket que parece verificado hace que el triage baje la guardia.
- **Nunca propongas diseño técnico, endpoints nuevos, migraciones ni plan de implementación.** El ticket dice qué se necesita y por qué; el cómo se decide después, con el repo delante.

| Excusa | Realidad |
|---|---|
| "Es obvio que quiso decir X" | Obvio para ti. Cuesta una pregunta y evita un rollback en producción. |
| "Dejar el campo vacío se ve incompleto" | Un hueco visible se resuelve. Un hueco relleno con suposición se implementa. |
| "El solicitante ya dijo que hoy funciona así" | Puede estar desactualizado o describir el frontend, no el backend. Márcalo para verificar. |
| "Preguntar tanto es fricción" | El ticket lo lee una máquina y lo ejecuta sobre usuarios reales. La fricción está en el lugar correcto. |
| "Puedo revisar el repo rápido y confirmarlo" | Confirmarlo aquí sin la disciplina del triage produce una verificación de mentira. |
| "La pregunta abierta tiene una respuesta evidente" | Entonces será rápida de responder. Igual la preguntas. |

## Señales de que te estás desviando

- Escribiste una afirmación sobre el código sin `segun_solicitante` ni `verificar_en_triage`.
- `preguntas_abiertas` quedó en `[]` y no hiciste ninguna pregunta.
- Estás nombrando clases, tablas, columnas o firmas de endpoints que el solicitante no mencionó.
- Estás explicando cómo se implementaría.
- Una palabra elástica del pedido original llegó al JSON sin acotar.

**Todas significan: para, vuelve a preguntar, reescribe el ticket.**

## Entrega

Guarda el JSON (convención sugerida: `docs/requerimientos/AAAA-MM-DD-slug.json`) y cierra con el handoff:

> Ticket listo. Para triarlo, abre una sesión nueva en el repo del backend y di:
> `Tengo este requerimiento @docs/requerimientos/AAAA-MM-DD-slug.json, revísalo con /revisar-requerimiento`

Si quedaron preguntas abiertas, dilo explícitamente: el triage las va a volver a plantear con el estado real del repo a la vista, y ahí se responden mejor.
