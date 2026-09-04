# Encargo: módulo Simulaciones Financieras + Calculadora Financiera (backend)

Implementa el módulo `simulaciones` en Quantum-CRM-Backend.

**Lee primero, son la fuente de verdad y ya están cerrados:**
- `docs/reglas_simulaciones.md` — reglas de negocio, fórmulas y casos dorados
- `src/main/resources/db/migration/V40__create_simulaciones.sql` — modelo de datos
- `CLAUDE.md` — convenciones del repo (arquitectura, TDD, ArchUnit, estilo)

No tomes decisiones de diseño: todo está definido. **Si algo te parece ambiguo o
contradictorio, detente y pregunta antes de implementar** — no infieras ni
inventes. Esto es producción con usuarios reales.

---

## Dependencia bloqueante

La V40 referencia `oportunidad_items(id)`, tabla que **aún no existe**. Es parte
de un cambio separado (las oportunidades pasan a aceptar varias unidades
distintas). **No la crees tú ni modifiques `oportunidades`.**

Si al empezar esa tabla no existe todavía: puedes avanzar completo con la fase 1
(el motor no depende de nada de eso) y detenerte antes de la fase 2, avisando.

---

## Orden de trabajo (no lo alteres)

### 1. Motor de cálculo puro — primero y aislado

Antes de tocar entidades JPA, endpoints o cualquier infraestructura: implementa
el motor como **función pura**, sin dependencias de Spring, BD ni framework.

- Entrada: los campos esenciales. Salida: cuota final + cronograma completo.
- `BigDecimal` en todo, con `MathContext`/`RoundingMode` explícitos. **Nunca `Double`.**
- Precisión completa entre meses; redondeo a 2 decimales **solo al exponer**.
- TDD estricto: **los tests de los dos casos dorados de la §3.6 se escriben
  antes que el motor y deben pasar al centavo.** Son el criterio de aceptación
  de esta fase; si no cuadran, no sigas a la fase 2.
- Cubre además: balloon = 0, descuento > 0, plazo distinto de 48, y el caso de
  error donde `|SaldoFinal(n) − valor_residual| ≥ 0.01`.

El punto delicado es `PMT` con convención Excel (`pv` negativo, `fv` positivo,
vencida). La §3.2 trae el equivalente algebraico; úsalo.

**Este motor lo consumen dos flujos** —el módulo que persiste y la Calculadora
que no— así que no puede quedar acoplado al Service de `simulaciones`.

### 2. Persistencia y dominio

Sigue exactamente el patrón de los módulos existentes
(`Entity / Repository / Service / ServiceImpl / Controller / dto`).
Toma `metasventa` o `solicitudes` como referencia estructural.

- El módulo solo conversa con `oportunidad_items`, `modelos` y `empleados`, y
  siempre a través de sus interfaces públicas (ArchUnit lo verifica).
- **`simulaciones` nunca lee `financiadoras`.** Lo que el cliente paga a terceros
  vive en `oportunidad_items.cuota_financiadora` y solo se compone en el DTO de
  oportunidad (§6.2).
- Una simulación es de **una unidad**: cuelga del ítem, no de la oportunidad.
  Con un solo ítem, el usuario nunca ve un selector.
- Registra en `simulacion_log` **todos** los eventos del enum. Solo INSERT.
- Nombre autogenerado (§8.1): se compone al leer cuando `nombre IS NULL`, nunca
  se persiste. El nombre manual es pegajoso: no se regenera al editar.
- Ojo con la escala de `tea`: 1–100 aquí, fraccionaria en `financiadoras`. No las
  mezcles.

### 3. Endpoints y permisos

CRUD + cronograma on demand + historial con diff + restaurar + bifurcar +
marcar principal. Sigue el estilo de `contrato_api.md` y actualízalo.

Centraliza la autorización en **un solo punto de decisión** (§10): el reparto
entre roles va a cambiar, y no quiero condicionales de rol dispersas por los
controllers.

### 4. Calculadora Financiera

Endpoint **stateless** que envuelve el motor: mismo cálculo completo, cero
escrituras en BD. Solo `POST` con parámetros → cuota + cronograma.

`POST /simulaciones` con los mismos parámetros es lo que hace el botón "Enlazar a
Oportunidad"; valida que el ítem pertenezca a una oportunidad del propio vendedor.

### 5. Jobs programados

- Purga: hard delete de simulaciones con `id_oportunidad_item IS NULL` y más de
  30 días. Registra evento `eliminada` con snapshot **antes** de borrar.
- Aviso 3 días antes vía `notificaciones` (requiere valores nuevos en
  `tipo_notificacion_enum` y `entidad_notificacion_enum` — migración aparte).
- Tipo de cambio SUNAT diario (§12), con fallback al último valor guardado.

Sigue el patrón `@Scheduled` de `notificaciones`. **No** escribas ningún job que
purgue `simulacion_log`: es permanente por diseño.

### 6. Documentación

Actualiza `contrato_api.md`, `matriz_permisos.md` y `CLAUDE.md`
(el módulo pasa a formar parte del inventario en producción).

---

## Restricciones que no se negocian

- **No persistas lo derivable:** ni el cronograma, ni el diff del historial, ni
  el nombre autogenerado. Todo se computa al leer.
- **No aceptes `cuota_final` del cliente.** Siempre server-side.
- **No agregues fila extra para el balloon** ni fuerces la última amortización.
  La igualdad con `valor_residual` es consecuencia, no ajuste.
- **La Calculadora no escribe nada.** Ni siquiera auditoría.
- **No toques `financiadoras`, `oportunidades`, `oportunidad_items` ni ninguna
  tabla existente**, salvo la migración de los enums de notificaciones. Si crees
  que hace falta otro cambio en tablas vivas, pregunta primero.
- Mantén el coverage por encima del trinquete del build (85 % global / 84 % dominio).

Al terminar cada fase, para y resume qué hiciste antes de seguir a la siguiente.
