# Quantum CRM — Matriz de Permisos

> Referencia para la implementación de Spring Security. Define qué puede ver y hacer cada rol. Los filtros de visibilidad se aplican automáticamente en el backend — el frontend no puede sobreescribirlos.

---

## Roles del sistema

| Rol | Quién | Descripción |
|---|---|---|
| `admin` | TI / sistema | Acceso total. Gestiona empleados y configuración. |
| `gerencia` | Gustavo | Visibilidad total. No gestiona empleados ni configuración de sistema. |
| `jdv` | Aldo | Jefe de ventas. Visibilidad total del equipo (excepto Cartera Maestra). Reasigna el vendedor de una empresa solo vía solicitud aprobada por `gerencia`; aplica descuentos hasta 7% directo. |
| `vendedor` | Asesores comerciales | Solo ve y opera sobre sus propios registros. |
| `analista` | Analista financiero | **Rol de apoyo: sin cartera propia.** Solo lectura sobre empresas y oportunidades, y únicamente las que colabora vía tarea. No confirma `facturado`, no aplica descuentos, no crea solicitudes. |
| `otro` | Roles de apoyo no comerciales | Mismos permisos que `analista`: sin cartera propia, solo lectura por colaboración. |

---

## 1. Visibilidad de datos

La visibilidad define qué registros devuelven los endpoints de listado y detalle. El backend aplica estos filtros sin excepción.

| Recurso | admin | gerencia | jdv | vendedor | analista | otro |
|---|---|---|---|---|---|---|
| **Empresas** | Todas | Todas (incluida Cartera Maestra) | Todas (excepto Cartera Maestra) | Solo donde `empresas.id_vendedor = yo` (excepto Cartera Maestra) | **Solo donde colabora vía tarea** (`empresas.id ∈ idsEmpresasDondeColabora`, excepto Cartera Maestra — nunca visible para un rol de apoyo) | Igual que analista |
| **Oportunidades** | Todas | Todas | Todas | Solo donde `oportunidades.id_vendedor = yo` | **Solo donde colabora vía tarea** (`oportunidades.id ∈ idsOportunidadesDondeColabora`) | Igual que analista |
| **Tareas** | Todas | Todas | Todas | Solo donde `tareas.id_asignado = yo` o `yo ∈ tarea_responsables` | Solo donde `tareas.id_asignado = yo` o `yo ∈ tarea_responsables` (sin cambios — este plan no tocó el módulo tareas) | Igual que analista |
| **Eventos** | Todos | Todos | Todos | Solo los de sus oportunidades | Solo los de oportunidades donde colabora (hereda de la visibilidad de oportunidades vía `vinculoVisible`, sin cambio de código propio) | Igual que analista |
| **Contactos** | Todos | Todos | Todos | Todos (búsqueda global para vincular) | **Solo los vinculados a empresas donde colabora vía tarea** (`empresa_contactos.id_empresa ∈ idsEmpresasDondeColabora`). El contacto sin empresa (huérfano) no lo alcanza. Excepción: con `?contexto=vincular` alcanza todo el CRM pero solo ve el nombre — ver §2.3 | Igual que analista |
| **Empleados** | Todos | Todos | Todos | Solo `GET /empleados/me` | Solo `GET /empleados/me` | Igual que analista |
| **Financiadoras** | Todas | Todas | Todas | Todas (solo lectura) | Todas (solo lectura) | Igual que analista |
| **Modelos** | Todos | Todos | Todos | Todos (solo lectura) | Todos (solo lectura) | Igual que analista |
| **Catálogo de eventos** | Todos | Todos | Todos | Todos (solo lectura) | Todos (solo lectura) | Igual que analista |
| **Reportes** | Todos | Todos | Todos | Sin acceso | Sin acceso | Igual que analista |
| **Log de estados** | Todos | Todos | Todos | Solo los de sus oportunidades | Solo los de oportunidades donde colabora (mismo mecanismo que Eventos) | Igual que analista |

**Nota (2026-08-18):** el cambio a rol de apoyo cubrió específicamente la escritura y visibilidad de `oportunidades` y `empresas`, y el guard de creación de `solicitudes` (más el filtro de colaboración expuesto por `tareas`). Quedaron sin tocar, y por lo tanto potencialmente desalineados con el nuevo modelo:
- **Contactos:** ~~el módulo no se tocó y `analista`/`otro` listaban, abrían y editaban todos los contactos del CRM~~ — **corregido 2026-08-20**: `GET /contactos`, `GET /contactos/:id` y `PUT /contactos/:id` ya aplican el filtro de colaboración; ver §2.3. El permiso de **vinculación** a empresas sigue siendo el heredado vía `EmpresaService.vinculoVisible` (igual que Eventos, §2.5) y no cambió: un rol de apoyo puede vincular/desvincular contactos en las empresas donde colabora. La vinculación a **oportunidades** sigue bloqueada con 403 (`rechazarSiEsApoyo` en `OportunidadServiceImpl`) — la asimetría de §2.3 sigue vigente y sin decidir.
- **Solicitudes de aprobación (`SolicitudServiceImpl`):** ~~el rol `otro` no está contemplado en el filtro de bandeja~~ — **corregido 2026-08-19**: el filtro ahora usa `usuario.esRolApoyo`, que cubre `analista` y `otro` por igual; ver nota de §2.12.
- **Metas de venta (`MetaVentaServiceImpl`):** ~~el rol `otro` tampoco está contemplado en su filtro de visibilidad del *listado* (`usuario.rol == "vendedor" || usuario.rol == "analista"`) — ve todas las metas del listado sin restricción (el detalle sí está cerrado)~~ — **corregido**: el filtro ahora usa `usuario.esRolApoyo`, mismo criterio que Solicitudes; ver nota de §2.13.
- **Prospección (`ProspeccionServiceImpl`) e Inicio (`InicioService`):** siguen filtrando por `id_vendedor = usuario.id` en vez de por colaboración vía tarea — en la práctica devuelven vacío para un rol de apoyo (fallan cerrado), no una fuga, pero tampoco reflejan el nuevo modelo de visibilidad.

Las fugas de **Solicitudes**, **Metas de venta** y **Contactos** ya se corrigieron. El punto restante es **Prospección e Inicio**, que siguen filtrando por `id_vendedor = usuario.id` en vez de por colaboración: en la práctica devuelven vacío para un rol de apoyo (fallan cerrado), así que no son una fuga, pero tampoco reflejan el modelo de visibilidad vigente — candidato a un ticket aparte vía `redactar-requerimiento`.

**Asignación como dueño vs. colaborador de una tarea:** la visibilidad de un rol de apoyo sobre la empresa/oportunidad vinculada a una tarea depende únicamente de `ids_colaboradores`. Si un supervisor asigna la tarea como **dueño** (`id_asignado`) a un `analista`/`otro` sin agregarlo también como colaborador, ese usuario ve la tarea en `GET /tareas` pero no gana visibilidad de la empresa/oportunidad vinculada (`GET /empresas/:id` u `GET /oportunidades/:id` le siguen devolviendo 404). Esto es intencional, decidido durante este plan — no es un caso sin cubrir.

---

## 2. Operaciones por dominio

### 2.1 Empleados

| Operación | admin | gerencia | jdv | vendedor | analista | otro |
|---|---|---|---|---|---|---|
| Ver lista de empleados | ✓ | ✓ | ✓ | — | — | — |
| Ver perfil propio (`/me`) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Crear empleado | ✓ | — | — | — | — | — |
| Editar empleado | ✓ | — | — | — | — | — |
| Activar / desactivar empleado | ✓ | — | — | — | — | — |

---

### 2.2 Empresas

| Operación | admin | gerencia | jdv | vendedor | analista | otro |
|---|---|---|---|---|---|---|
| Crear empresa | ✓ | ✓ | ✓ | ✓ | — | — |
| Editar empresa (datos) | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo las suyas | — | — |
| Reasignar vendedor directo | ✓ | ✓ | — (vía solicitud a gerencia) | — | — | — |
| Cambiar `estado_cartera` manual | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo las suyas | — | — |
| Ver archivos en Drive (`GET /empresas/:id/archivos`) | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo las suyas | ✓ Solo donde colabora | ✓ Solo donde colabora |
| Subir archivos / crear carpeta de Drive (`POST /empresas/:id/archivos`, `POST /empresas/:id/carpeta-drive`) | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo las suyas | — | — |
| Ver check de RUC duplicado | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Mover/liberar Cartera Maestra | ✓ | ✓ | — | — | — | — |
| Eliminar empresa (definitivo, cascada a oportunidades/tareas/eventos) | ✓ | — | — | — | — | — |

**Nota sobre roles de apoyo (2026-08-18):** `analista`/`otro` no tienen cartera propia — toda operación de escritura de esta tabla les está vedada, incluida la subida de archivos a Drive (escribe `empresas.drive_folder_id`). La visibilidad de lectura (`Editar`, `Ver archivos`, etc. cuando aplica) es únicamente sobre empresas donde el usuario colabora vía una tarea (`ids_colaboradores`), nunca por cartera propia.

**Nota sobre `estado_cartera` manual:** solo se permiten los estados `no_contactado`, `no_aplica`, `no_interesado`, `prospeccion`. Los estados `oportunidad_activa` y `cliente` son derivados y nunca editables manualmente por ningún rol.

---

### 2.3 Contactos

> **Guard propio desde 2026-08-20.** El módulo dejó de heredar toda su visibilidad de otros: `ContactoServiceImpl` resuelve por su cuenta qué contactos alcanza un rol de apoyo (los vinculados a empresas donde colabora vía tarea, consultando `TareaService.idsEmpresasDondeColabora`) y lo aplica dentro de la query, en la búsqueda, el detalle y la edición. Las operaciones de **vinculación** siguen resolviendo visibilidad vía `EmpresaService.vinculoVisible`/`OportunidadService.vinculoVisible`, que significan cosas distintas para empresas y para oportunidades — ver cada fila y la nota final.

| Operación | admin | gerencia | jdv | vendedor | analista | otro |
|---|---|---|---|---|---|---|
| Buscar contactos (`GET /contactos`) | ✓ Todos | ✓ Todos | ✓ Todos | ✓ Todos | ✓ Solo donde colabora; con `?contexto=vincular` busca en todo el CRM pero solo recibe el nombre | Igual que analista |
| Ver detalle de contacto (`GET /contactos/:id`) | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo donde colabora (fuera de alcance → **404**); con `?contexto=vincular` cualquiera, recortado al nombre | Igual que analista |
| Crear contacto | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Editar contacto (`PUT /contactos/:id`) | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo donde colabora (fuera de alcance → **403**, no 404) | Igual que analista |
| Eliminar contacto | ✓ | ✓ | ✓ | — | — | — |
| Vincular contacto a empresa | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo sus empresas | — (bloqueado, 403 — corregido 2026-08-20) | Igual que analista |
| Editar vínculo (cargo / toma_decision) | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo sus empresas | — (bloqueado, 403 — corregido 2026-08-20) | Igual que analista |
| Desvincular contacto de empresa | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo sus empresas | — (bloqueado, 403 — corregido 2026-08-20) | Igual que analista |
| Vincular contacto a oportunidad | ✓ | ✓ | ✓ | ✓ Solo las suyas | — (bloqueado por `rechazarSiEsApoyo`, 403) | Igual que analista |
| Editar rol en oportunidad | ✓ | ✓ | ✓ | ✓ Solo las suyas | — (bloqueado, 403) | Igual que analista |
| Desvincular de oportunidad | ✓ | ✓ | ✓ | ✓ Solo las suyas | — (bloqueado, 403) | Igual que analista |

**Nota sobre el 403 de editar (2026-08-20):** `PUT /contactos/:id` responde **403** y no 404 sobre un contacto fuera de alcance, al revés que el `GET`. Es deliberado y aprobado por producto: en `?contexto=vincular` ese mismo usuario ve legítimamente el contacto por su nombre, así que su existencia no es secreta para él y esconderlo al editar mentiría sobre algo que el sistema ya le mostró. Es el mismo razonamiento que `EmpresaServiceImpl.rechazarSiEsApoyo` documenta para empresas. El criterio IDOR general (recurso ajeno → 404) sigue vigente en todo lo demás.

**Nota sobre la asimetría de vinculación (resuelta 2026-08-20):** hasta el 2026-08-20, `EmpresaServiceImpl.vinculoVisible` no tenía guard de escritura sobre la vinculación de contactos, a diferencia de `OportunidadServiceImpl`, que sí bloqueaba con `rechazarSiEsApoyo` en sus métodos de vinculación de contacto — un `analista` podía vincular/desvincular contactos de una empresa donde colabora, pero no de una oportunidad donde colabora. Combinado con el `contexto=vincular` de `GET /contactos` (que busca deliberadamente en todo el CRM), esto abría un camino de escalada: buscar cualquier contacto por nombre → vincularlo a una empresa donde colabora → verlo completo por `GET /contactos/:id`. **Corregido**: la vinculación de contactos a empresas ahora se comporta igual que la de oportunidades — bloqueada con 403 para `analista`/`otro`, sin excepción. La asimetría ya no existe.

---

### 2.4 Oportunidades

| Operación | admin | gerencia | jdv | vendedor | analista | otro |
|---|---|---|---|---|---|---|
| Crear oportunidad | ✓ | ✓ (asigna vendedor si la empresa no tiene) | ✓ | ✓ Solo en sus empresas | — | — |
| Editar campos negociables | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo las suyas | — | — |
| Ver archivos en Drive (`GET /oportunidades/:id/archivos`) | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo las suyas | ✓ Solo donde colabora | ✓ Solo donde colabora |
| Subir archivos / crear carpeta de Drive (`POST /oportunidades/:id/archivos`, `POST /oportunidades/:id/carpeta-drive`) | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo las suyas | — | — |
| Aplicar descuento directo | Sin límite | Sin límite | Hasta 7% | Hasta 3% | Sin margen (ninguna vía) | Sin margen (ninguna vía) |
| Solicitar descuento sobre su límite | — | — | ✓ (>7% → gerencia) | ✓ (3–7% → jdv, >7% → gerencia) | — | — |
| Cambiar estado (cualquier estado excepto `facturado`) | ✓ | ✓ | ✓ | ✓ Solo las suyas | — | — |
| **Confirmar paso a `facturado`** | ✓ | ✓ | — | — | — | — |
| Ver log de estados | ✓ | ✓ | ✓ | ✓ Solo las suyas | ✓ Solo donde colabora | ✓ Solo donde colabora |
| Eliminar oportunidad (definitivo, cascada a tareas/eventos/log) | ✓ | — | — | — | — | — |

**Nota sobre el paso a `facturado` (corregida 2026-08-18):** el vendedor y el JdV no pueden confirmar este paso porque dispara el cálculo de comisiones. Desde este cambio, **solo `admin` y `gerencia`** — `analista` era rol de apoyo antes de la Fase de módulo financiero (§4.3) y ya no confirma facturación. Esta restricción se aplica en el endpoint `PATCH /oportunidades/:id/estado`.

**Nota sobre roles de apoyo:** `analista`/`otro` no tienen cartera propia ni margen de descuento por ninguna vía (ni directo ni por solicitud — ver §2.12). Solo ven (nunca editan) las oportunidades donde colaboran vía una tarea.

---

### 2.5 Eventos

> **Sin cambios de código en este módulo (2026-08-18):** las operaciones de escritura siguen sin un guard de rol de apoyo propio; lo que sí cambió es la visibilidad, porque `EventoService` resuelve la oportunidad vía `OportunidadService.vinculoVisible`, que ya aplica la regla de colaboración (§2.4). Un `analista`/`otro` puede, en teoría, seguir creando/editando eventos de una oportunidad en la que colabora — no fue evaluado si eso debería bloquearse también; ver nota de §1.

| Operación | admin | gerencia | jdv | vendedor | analista | otro |
|---|---|---|---|---|---|---|
| Ver eventos de una oportunidad | ✓ | ✓ | ✓ | ✓ Solo las suyas | ✓ Solo donde colabora | Igual que analista |
| Crear evento (del catálogo o personalizado) | ✓ | ✓ | ✓ | ✓ Solo las suyas | ✓ Solo donde colabora | Igual que analista |
| Marcar evento como ocurrido | ✓ | ✓ | ✓ | ✓ Solo las suyas | ✓ Solo donde colabora | Igual que analista |
| Marcar evento como descartado | ✓ | ✓ | ✓ | ✓ Solo las suyas | ✓ Solo donde colabora | Igual que analista |
| Editar evento pendiente | ✓ | ✓ | ✓ | ✓ Solo las suyas | ✓ Solo donde colabora | Igual que analista |

**Nota:** marcar un evento como ocurrido no ejecuta el cambio de estado — solo devuelve la sugerencia. La confirmación del cambio de estado corre las mismas reglas del punto 2.4 (hoy solo `admin`/`gerencia`).

---

### 2.6 Tareas

| Operación | admin | gerencia | jdv | vendedor | analista | otro |
|---|---|---|---|---|---|---|
| Ver tareas | ✓ Todas | ✓ Todas | ✓ Todas | ✓ Solo donde es dueño o colaborador | ✓ Solo donde es dueño o colaborador | Igual que analista |
| Crear tarea | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Asignar tarea (dueño) a otro empleado | ✓ | ✓ | ✓ | — | — | — |
| Agregar colaborador a otro empleado | ✓ | ✓ | ✓ | — | — | — |
| Marcar tarea como completada | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo donde es dueño o colaborador | ✓ Solo donde es dueño o colaborador | Igual que analista |
| Marcar tarea como cancelada | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo donde es dueño o colaborador | ✓ Solo donde es dueño o colaborador | Igual que analista |
| Editar tarea pendiente | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo donde es dueño o colaborador | ✓ Solo donde es dueño o colaborador | Igual que analista |

---

### 2.7 Financiadoras

| Operación | admin | gerencia | jdv | vendedor | analista | otro |
|---|---|---|---|---|---|---|
| Ver lista | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Crear financiadora | ✓ | ✓ | — | — | — | — |
| Editar financiadora | ✓ | ✓ | — | — | — | — |

---

### 2.8 Modelos de bus

| Operación | admin | gerencia | jdv | vendedor | analista | otro |
|---|---|---|---|---|---|---|
| Ver catálogo | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Crear modelo (con aplicaciones) | ✓ | ✓ | — | — | — | — |
| Editar modelo | ✓ | ✓ | — | — | — | — |

---

### 2.9 Catálogo de eventos

| Operación | admin | gerencia | jdv | vendedor | analista | otro |
|---|---|---|---|---|---|---|
| Ver catálogo | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Crear evento en catálogo | ✓ | — | — | — | — | — |
| Editar evento del catálogo | ✓ | — | — | — | — | — |

---

### 2.10 Reportes

| Reporte | admin | gerencia | jdv | vendedor | analista | otro |
|---|---|---|---|---|---|---|
| Ventas acumuladas | ✓ | ✓ | ✓ | — | — | — |
| Estado del pipeline | ✓ | ✓ | ✓ | — | — | — |
| Resumen del equipo | ✓ | ✓ | ✓ | — | — | — |
| Velocidad por etapa | ✓ | ✓ | ✓ | — | — | — |
| Embudo de prospección | ✓ | ✓ | ✓ | — | — | — |
| Mix de descuentos | ✓ | ✓ | ✓ | — | — | — |

Ningún rol `vendedor`, `analista` ni `otro` tiene acceso a reportes en el MVP.

---

### 2.11 Vistas de navegación

| Vista | admin | gerencia | jdv | vendedor | analista | otro |
|---|---|---|---|---|---|---|
| Inicio (`GET /inicio`) | ✓ Sus datos | ✓ Sus datos | ✓ Sus datos | ✓ Sus datos | ✓ Sus datos (sin cambios de código — sigue filtrando por `usuario.id`, vacío en la práctica sin cartera propia) | Igual que analista |
| Prospección (`GET /prospeccion`) | ✓ Todas | ✓ Todas | ✓ Todas | ✓ Solo las suyas | ✓ Sin cambios de código — `ProspeccionServiceImpl` sigue filtrando por `id_vendedor = usuario.id`, no por colaboración; vacío en la práctica | Igual que analista |
| Pipeline (vista sobre `GET /oportunidades`) | ✓ Todas | ✓ Todas | ✓ Todas | ✓ Solo las suyas | ✓ Solo donde colabora | Igual que analista |
| Cartera (`GET /empresas`) | ✓ Todas | ✓ Todas | ✓ Todas | ✓ Solo las suyas | ✓ Solo donde colabora | Igual que analista |
| Gerencia (bandeja de solicitudes) | ✓ Todas las bandejas | ✓ Su bandeja | ✓ Su bandeja + propias | — | — | — |
| Cartera Maestra | ✓ | ✓ | — | — | — | — |

---

### 2.12 Solicitudes de aprobación

| Operación | admin | gerencia | jdv | vendedor | analista | otro |
|---|---|---|---|---|---|---|
| Crear solicitud de descuento (sobre su límite) | — (aplica directo) | — (aplica directo) | ✓ (>7%) | ✓ (>3%) | — | — |
| Crear solicitud de reasignación de cliente | — (reasigna directo) | — (reasigna directo) | ✓ | — | — | — |
| Ver bandeja de aprobación | ✓ Todas | ✓ Las dirigidas a gerencia | ✓ Las dirigidas a jdv | — | — | — |
| Ver solicitudes propias (históricas) | ✓ | ✓ | ✓ | ✓ | ✓ (no genera nuevas, conserva las que ya tenía) | ✓ Solo las propias, igual que analista |
| Aprobar / denegar | ✓ Cualquiera | ✓ Su bandeja | ✓ Su bandeja | — | — | — |
| Ver / gestionar cartera maestra | ✓ | ✓ | — | — | — | — |

**Notas:**
- El aprobador lo deriva el backend al crear la solicitud; nunca lo elige el solicitante (`gerencia_solicitudes_modelo_datos.md §3.4`).
- Al aprobar, el cambio se aplica en la misma transacción que resuelve la solicitud (descuento: recalcula `monto_total`; reasignación: reutiliza `reasignarVendedor` con su cascada existente).
- `gerencia` y `admin` nunca son destino de asignación de vendedor (no tienen cartera propia).
- **Roles de apoyo (2026-08-18):** `analista`/`otro` ya no pueden crear ninguna solicitud — no tienen margen de descuento por ninguna vía (§2.4) y no reasignan clientes. Ambos conservan la visibilidad de lectura solo sobre las solicitudes que ellos mismos crearon (filtro por `esRolApoyo` en `SolicitudServiceImpl.especificacion()`, corregido 2026-08-19 — ver nota siguiente).
- **Corregido (2026-08-19):** el filtro de bandeja de `SolicitudServiceImpl.especificacion()` no tenía ninguna rama para el rol `otro` — caía fuera de `admin`, `gerencia`, `jdv` y de la condición `filtros.mias || rol == "vendedor" || rol == "analista"`, así que no se le agregaba ningún predicado de alcance y `GET /solicitudes` le devolvía *todas* las solicitudes de la empresa, incluidos montos de descuento y motivos de reasignación de otros vendedores. Se corrigió reemplazando la comparación de string por `usuario.esRolApoyo`, que cubre `analista` y `otro` por igual. El detalle (`GET /solicitudes/:id`) ya estaba cerrado desde antes.

---

### 2.13 Metas de venta

| Operación | admin | gerencia | jdv | vendedor | analista | otro |
|---|---|---|---|---|---|---|
| Proponer meta de un vendedor o de sí mismo | — (crea directo) | — (crea directo) | ✓ | — | — | — |
| Crear/modificar meta directo (queda aprobada) | ✓ | ✓ | — | — | — | — |
| Aprobar / rechazar propuesta | ✓ | ✓ | — | — | — | — |
| Ver metas propias | ✓ | ✓ | ✓ | ✓ | — (no aplica, no tiene meta) | — (no aplica) |
| Ver metas del equipo (todos los vendedores) | ✓ | ✓ | ✓ | — | — | — |
| Ver medidor de cumplimiento en Inicio | — (no vende) | — (no vende) | ✓ (propio + equipo) | ✓ (propio) | — | — |

**Notas:**
- La meta es en unidades vendidas, no en monto. Un vendedor solo aparece en la tabla como `id_empleado`, nunca `gerencia`/`admin` (no tienen cartera propia, igual que en reasignación de vendedor).
- Las unidades de una oportunidad cuentan para el cumplimiento del vendedor únicamente mientras esté en estado `facturado` (`oportunidades.facturado_en`); al cancelarse (retroceder de estado) o eliminarse estando facturada, dejan de contar sin acción manual.
- **Corregido:** el filtro de `MetaVentaServiceImpl.especificacion()` (el que usa el **listado**, `GET /metas-venta`) restringía por `idEmpleado = usuario.id` solo si `usuario.rol == "vendedor" || usuario.rol == "analista"`. El rol `otro` no caía en esa condición, así que el listado le devolvía todas las metas sin restricción, como si fuera supervisor. El detalle (`GET /metas-venta/:id`) ya estaba cerrado (`visible()`, `else -> meta.idEmpleado == usuario.id`) — la fuga era solo del listado. Se corrigió reemplazando la comparación de string por `usuario.esRolApoyo`, mismo criterio que Solicitudes (§2.12).

---

### 2.14 Mantenimiento

| Operación | admin | gerencia | jdv | vendedor | analista | otro |
|---|---|---|---|---|---|---|
| Backfill de carpetas de Drive (`POST /mantenimiento/carpetas-drive`) | ✓ | — | — | — | — | — |

---

## 3. Reglas de implementación en Spring Security

### 3.1 Estructura recomendada

Implementar con `@PreAuthorize` a nivel de método de servicio, no solo a nivel de controlador. Esto garantiza que la seguridad se aplique aunque se llame al servicio desde otro contexto.

```java
// Ejemplo en el servicio de oportunidades
@PreAuthorize("hasAnyRole('ADMIN', 'GERENCIA', 'JDV') or " +
              "(hasAnyRole('VENDEDOR', 'ANALISTA', 'OTRO') and @ownershipChecker.isOwner(#id, authentication))")
public OportunidadDTO getOportunidad(Long id) { ... }
```

**Nota (2026-08-18):** `GERENTE` era el nombre anterior a la migración V25, corregido a `GERENCIA`. Esta §3 completa es pseudocódigo aspiracional de diseño temprano — la implementación real no usa `@PreAuthorize`/`OwnershipChecker`, sino los predicados de `UsuarioActual` (`esRolApoyo`, `visibilidadRestringida`, etc.) evaluados dentro de cada `ServiceImpl`. El ejemplo tampoco refleja que `ANALISTA`/`OTRO` ya no tienen ownership por cartera propia sino por colaboración vía tarea — esta sección quedó desactualizada desde antes de este cambio y su reescritura completa está fuera de alcance acá.

### 3.2 OwnershipChecker

Implementar un componente `OwnershipChecker` que centralice las validaciones de pertenencia. Evita duplicar la lógica en cada método:

```java
@Component("ownershipChecker")
public class OwnershipChecker {

    // Verifica que la oportunidad pertenece al usuario autenticado
    public boolean isOportunidadOwner(Long oportunidadId, Authentication auth) {
        // SELECT id_vendedor FROM oportunidades WHERE id = oportunidadId
        // AND id_vendedor = auth.getEmpleadoId()
    }

    // Verifica que la empresa está asignada al usuario autenticado
    public boolean isEmpresaOwner(Long empresaId, Authentication auth) {
        // SELECT id_vendedor FROM empresas WHERE id = empresaId
        // AND id_vendedor = auth.getEmpleadoId()
    }

    // Verifica que la tarea está asignada al usuario autenticado
    public boolean isTareaOwner(Long tareaId, Authentication auth) {
        // SELECT id_asignado FROM tareas WHERE id = tareaId
        // AND id_asignado = auth.getEmpleadoId()
    }
}
```

### 3.3 Filtro automático en queries

Para los endpoints de listado, el filtro por visibilidad debe aplicarse en la query, no en memoria. Usar Spring Data JPA Specifications o queries condicionales:

```java
// En el repositorio de oportunidades
public Page<Oportunidad> findAll(Specification<Oportunidad> spec, Pageable pageable);

// En el servicio, construir el Specification según el rol
// (esVendedorOAnalista es ilustrativo de una epoca donde ANALISTA/OTRO tenian
// ownership por cartera propia; hoy es esRolApoyo + colaboracion via tarea,
// no cb.equal(idVendedor) — ver nota de §3.1)
if (esVendedorOAnalista(rol)) {
    spec = spec.and((root, query, cb) ->
        cb.equal(root.get("idVendedor"), empleadoId));
}
```

### 3.4 Validación del paso a Facturado

Este es el único caso donde el permiso no es por rol de lectura/escritura general sino por una operación específica dentro de un endpoint compartido:

```java
@Service
public class OportunidadService {

    public OportunidadDTO cambiarEstado(Long id, EstadoRequest request, EmpleadoDetails auth) {

        if (request.getEstado() == EstadoOp.FACTURADO) {
            if (!auth.tieneRol(ADMIN, GERENCIA)) {
                throw new PermisoInsuficienteException(
                    "Solo admin o gerencia pueden confirmar el paso a Facturado");
            }
        }
        // ... resto de la lógica
    }
}
```

---

## 4. Casos especiales

### 4.1 Empresa sin vendedor asignado

Si `empresas.id_vendedor = NULL`, la empresa solo es visible para `admin`, `gerencia` y `jdv` por la vía de cartera. Ningún `vendedor` puede verla por esa vía. **Actualizado 2026-08-18:** un `analista`/`otro` tampoco la ve por cartera (no tiene), pero si un supervisor lo agrega como colaborador en una tarea vinculada a esa empresa, sí la vería vía colaboración — la visibilidad de rol de apoyo no depende de `id_vendedor`.

### 4.2 Tareas sin asignar

Si `tareas.id_asignado = NULL`, la tarea la puede ver y completar cualquier `vendedor` que sea el vendedor de la oportunidad o empresa vinculada, o `analista`/`otro` que colabore en ella. Si ninguno aplica, solo la ven admin/gerencia/jdv. (Este módulo no se tocó en el cambio del 2026-08-18; la redacción se ajustó aquí solo para no seguir describiendo a `analista` como si tuviera cartera propia.)

### 4.3 Analista financiero — rol de apoyo (actualizado 2026-08-18)

`analista` (y `otro`, con los mismos permisos) dejó de tener visibilidad de vendedor y el privilegio de confirmar `facturado`. Es ahora un **rol de apoyo sin cartera propia**: solo lectura sobre empresas y oportunidades, únicamente donde colabora vía una tarea (`ids_colaboradores`); no crea ni edita empresas/oportunidades, no aplica descuentos por ninguna vía, no crea solicitudes de aprobación. El cambio se hizo porque producto identificó que la restricción de solo lectura solo se cumplía en el frontend, no en el backend (sin seguridad real).

Cuando se implemente el módulo financiero, el analista necesitará acceso a campos que hoy no existen — eso sigue pendiente y no está resuelto por este cambio. El panel de administración de permisos (post-MVP) permitirá configurar esto sin una nueva migración de roles.

### 4.4 Reasignación y visibilidad histórica

Cuando una empresa se reasigna de un vendedor a otro, el vendedor anterior pierde visibilidad sobre la empresa y sus oportunidades activas. Sin embargo, las oportunidades en `facturado` o `cerrado` donde él era el vendedor snapshot siguen siendo visibles para él en una vista de historial (filtro `incluir_cerradas=true` en `/oportunidades`). Esto garantiza que pueda consultar su historial de operaciones para comisiones futuras.
