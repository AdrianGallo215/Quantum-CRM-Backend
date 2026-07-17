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
| `analista` | Analista financiero | Misma visibilidad que vendedor en MVP. Puede validar paso a Facturado. |

---

## 1. Visibilidad de datos

La visibilidad define qué registros devuelven los endpoints de listado y detalle. El backend aplica estos filtros sin excepción.

| Recurso | admin | gerencia | jdv | vendedor | analista |
|---|---|---|---|---|---|
| **Empresas** | Todas | Todas (incluida Cartera Maestra) | Todas (excepto Cartera Maestra) | Solo donde `empresas.id_vendedor = yo` (excepto Cartera Maestra) | Solo donde `empresas.id_vendedor = yo` (excepto Cartera Maestra) |
| **Oportunidades** | Todas | Todas | Todas | Solo donde `oportunidades.id_vendedor = yo` | Solo donde `oportunidades.id_vendedor = yo` |
| **Tareas** | Todas | Todas | Todas | Solo donde `tareas.id_asignado = yo` | Solo donde `tareas.id_asignado = yo` |
| **Eventos** | Todos | Todos | Todos | Solo los de sus oportunidades | Solo los de sus oportunidades |
| **Contactos** | Todos | Todos | Todos | Todos (búsqueda global para vincular) | Todos |
| **Empleados** | Todos | Todos | Todos | Solo `GET /empleados/me` | Solo `GET /empleados/me` |
| **Financiadoras** | Todas | Todas | Todas | Todas (solo lectura) | Todas (solo lectura) |
| **Modelos** | Todos | Todos | Todos | Todos (solo lectura) | Todos (solo lectura) |
| **Catálogo de eventos** | Todos | Todos | Todos | Todos (solo lectura) | Todos (solo lectura) |
| **Reportes** | Todos | Todos | Todos | Sin acceso | Sin acceso |
| **Log de estados** | Todos | Todos | Todos | Solo los de sus oportunidades | Solo los de sus oportunidades |

---

## 2. Operaciones por dominio

### 2.1 Empleados

| Operación | admin | gerencia | jdv | vendedor | analista |
|---|---|---|---|---|---|
| Ver lista de empleados | ✓ | ✓ | ✓ | — | — |
| Ver perfil propio (`/me`) | ✓ | ✓ | ✓ | ✓ | ✓ |
| Crear empleado | ✓ | — | — | — | — |
| Editar empleado | ✓ | — | — | — | — |
| Activar / desactivar empleado | ✓ | — | — | — | — |

---

### 2.2 Empresas

| Operación | admin | gerencia | jdv | vendedor | analista |
|---|---|---|---|---|---|
| Crear empresa | ✓ | ✓ | ✓ | ✓ | ✓ |
| Editar empresa (datos) | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo las suyas | ✓ Solo las suyas |
| Reasignar vendedor directo | ✓ | ✓ | — (vía solicitud a gerencia) | — | — |
| Cambiar `estado_cartera` manual | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo las suyas | ✓ Solo las suyas |
| Ver check de RUC duplicado | ✓ | ✓ | ✓ | ✓ | ✓ |
| Mover/liberar Cartera Maestra | ✓ | ✓ | — | — | — |
| Eliminar empresa (definitivo, cascada a oportunidades/tareas/eventos) | ✓ | — | — | — | — |

**Nota sobre `estado_cartera` manual:** solo se permiten los estados `no_contactado`, `no_aplica`, `no_interesado`, `prospeccion`. Los estados `oportunidad_activa` y `cliente` son derivados y nunca editables manualmente por ningún rol.

---

### 2.3 Contactos

| Operación | admin | gerencia | jdv | vendedor | analista |
|---|---|---|---|---|---|
| Buscar contactos | ✓ | ✓ | ✓ | ✓ | ✓ |
| Crear contacto | ✓ | ✓ | ✓ | ✓ | ✓ |
| Editar contacto | ✓ | ✓ | ✓ | ✓ | ✓ |
| Eliminar contacto | ✓ | ✓ | ✓ | — | — |
| Vincular contacto a empresa | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo sus empresas | ✓ Solo sus empresas |
| Editar vínculo (cargo / toma_decision) | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo sus empresas | ✓ Solo sus empresas |
| Desvincular contacto de empresa | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo sus empresas | ✓ Solo sus empresas |
| Vincular contacto a oportunidad | ✓ | ✓ | ✓ | ✓ Solo las suyas | ✓ Solo las suyas |
| Editar rol en oportunidad | ✓ | ✓ | ✓ | ✓ Solo las suyas | ✓ Solo las suyas |
| Desvincular de oportunidad | ✓ | ✓ | ✓ | ✓ Solo las suyas | ✓ Solo las suyas |

---

### 2.4 Oportunidades

| Operación | admin | gerencia | jdv | vendedor | analista |
|---|---|---|---|---|---|
| Crear oportunidad | ✓ | ✓ (asigna vendedor si la empresa no tiene) | ✓ | ✓ Solo en sus empresas | ✓ Solo en sus empresas |
| Editar campos negociables | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo las suyas | ✓ Solo las suyas |
| Aplicar descuento directo | Sin límite | Sin límite | Hasta 7% | Hasta 3% | Hasta 3% |
| Solicitar descuento sobre su límite | — | — | ✓ (>7% → gerencia) | ✓ (3–7% → jdv, >7% → gerencia) | ✓ (3–7% → jdv, >7% → gerencia) |
| Cambiar estado (cualquier estado excepto `facturado`) | ✓ | ✓ | ✓ | ✓ Solo las suyas | ✓ Solo las suyas |
| **Confirmar paso a `facturado`** | ✓ | ✓ | — | — | ✓ |
| Ver log de estados | ✓ | ✓ | ✓ | ✓ Solo las suyas | ✓ Solo las suyas |

**Nota sobre el paso a `facturado`:** el vendedor y el JdV no pueden confirmar este paso porque dispara el cálculo de comisiones. Solo lo pueden confirmar `admin`, `gerencia` y `analista`. Esta restricción se aplica en el endpoint `PATCH /oportunidades/:id/estado`.

---

### 2.5 Eventos

| Operación | admin | gerencia | jdv | vendedor | analista |
|---|---|---|---|---|---|
| Ver eventos de una oportunidad | ✓ | ✓ | ✓ | ✓ Solo las suyas | ✓ Solo las suyas |
| Crear evento (del catálogo o personalizado) | ✓ | ✓ | ✓ | ✓ Solo las suyas | ✓ Solo las suyas |
| Marcar evento como ocurrido | ✓ | ✓ | ✓ | ✓ Solo las suyas | ✓ Solo las suyas |
| Marcar evento como descartado | ✓ | ✓ | ✓ | ✓ Solo las suyas | ✓ Solo las suyas |
| Editar evento pendiente | ✓ | ✓ | ✓ | ✓ Solo las suyas | ✓ Solo las suyas |

**Nota:** marcar un evento como ocurrido no ejecuta el cambio de estado — solo devuelve la sugerencia. La confirmación del cambio de estado corre las mismas reglas del punto 2.4.

---

### 2.6 Tareas

| Operación | admin | gerencia | jdv | vendedor | analista |
|---|---|---|---|---|---|
| Ver tareas | ✓ Todas | ✓ Todas | ✓ Todas | ✓ Solo las asignadas a sí mismo | ✓ Solo las asignadas a sí mismo |
| Crear tarea | ✓ | ✓ | ✓ | ✓ | ✓ |
| Asignar tarea a otro empleado | ✓ | ✓ | ✓ | — | — |
| Marcar tarea como completada | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo las asignadas a sí mismo | ✓ Solo las asignadas a sí mismo |
| Marcar tarea como cancelada | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo las asignadas a sí mismo | ✓ Solo las asignadas a sí mismo |
| Editar tarea pendiente | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo las asignadas a sí mismo | ✓ Solo las asignadas a sí mismo |

---

### 2.7 Financiadoras

| Operación | admin | gerencia | jdv | vendedor | analista |
|---|---|---|---|---|---|
| Ver lista | ✓ | ✓ | ✓ | ✓ | ✓ |
| Crear financiadora | ✓ | ✓ | — | — | — |
| Editar financiadora | ✓ | ✓ | — | — | — |

---

### 2.8 Modelos de bus

| Operación | admin | gerencia | jdv | vendedor | analista |
|---|---|---|---|---|---|
| Ver catálogo | ✓ | ✓ | ✓ | ✓ | ✓ |
| Crear modelo (con aplicaciones) | ✓ | ✓ | — | — | — |
| Editar modelo | ✓ | ✓ | — | — | — |

---

### 2.9 Catálogo de eventos

| Operación | admin | gerencia | jdv | vendedor | analista |
|---|---|---|---|---|---|
| Ver catálogo | ✓ | ✓ | ✓ | ✓ | ✓ |
| Crear evento en catálogo | ✓ | — | — | — | — |
| Editar evento del catálogo | ✓ | — | — | — | — |

---

### 2.10 Reportes

| Reporte | admin | gerencia | jdv | vendedor | analista |
|---|---|---|---|---|---|
| Ventas acumuladas | ✓ | ✓ | ✓ | — | — |
| Estado del pipeline | ✓ | ✓ | ✓ | — | — |
| Resumen del equipo | ✓ | ✓ | ✓ | — | — |
| Velocidad por etapa | ✓ | ✓ | ✓ | — | — |
| Embudo de prospección | ✓ | ✓ | ✓ | — | — |
| Mix de descuentos | ✓ | ✓ | ✓ | — | — |

Ningún rol `vendedor` ni `analista` tiene acceso a reportes en el MVP.

---

### 2.11 Vistas de navegación

| Vista | admin | gerencia | jdv | vendedor | analista |
|---|---|---|---|---|---|
| Inicio (`GET /inicio`) | ✓ Sus datos | ✓ Sus datos | ✓ Sus datos | ✓ Sus datos | ✓ Sus datos |
| Prospección (`GET /prospeccion`) | ✓ Todas | ✓ Todas | ✓ Todas | ✓ Solo las suyas | ✓ Solo las suyas |
| Pipeline | ✓ Todas | ✓ Todas | ✓ Todas | ✓ Solo las suyas | ✓ Solo las suyas |
| Cartera (listado de empresas) | ✓ Todas | ✓ Todas | ✓ Todas | ✓ Solo las suyas | ✓ Solo las suyas |
| Gerencia (bandeja de solicitudes) | ✓ Todas las bandejas | ✓ Su bandeja | ✓ Su bandeja + propias | — | — |
| Cartera Maestra | ✓ | ✓ | — | — | — |

---

### 2.12 Solicitudes de aprobación

| Operación | admin | gerencia | jdv | vendedor | analista |
|---|---|---|---|---|---|
| Crear solicitud de descuento (sobre su límite) | — (aplica directo) | — (aplica directo) | ✓ (>7%) | ✓ (>3%) | ✓ (>3%) |
| Crear solicitud de reasignación de cliente | — (reasigna directo) | — (reasigna directo) | ✓ | — | — |
| Ver bandeja de aprobación | ✓ Todas | ✓ Las dirigidas a gerencia | ✓ Las dirigidas a jdv | — | — |
| Ver solicitudes propias | ✓ | ✓ | ✓ | ✓ | ✓ |
| Aprobar / denegar | ✓ Cualquiera | ✓ Su bandeja | ✓ Su bandeja | — | — |
| Ver / gestionar cartera maestra | ✓ | ✓ | — | — | — |

**Notas:**
- El aprobador lo deriva el backend al crear la solicitud; nunca lo elige el solicitante (`gerencia_solicitudes_modelo_datos.md §3.4`).
- Al aprobar, el cambio se aplica en la misma transacción que resuelve la solicitud (descuento: recalcula `monto_total`; reasignación: reutiliza `reasignarVendedor` con su cascada existente).
- `gerencia` y `admin` nunca son destino de asignación de vendedor (no tienen cartera propia).

---

## 3. Reglas de implementación en Spring Security

### 3.1 Estructura recomendada

Implementar con `@PreAuthorize` a nivel de método de servicio, no solo a nivel de controlador. Esto garantiza que la seguridad se aplique aunque se llame al servicio desde otro contexto.

```java
// Ejemplo en el servicio de oportunidades
@PreAuthorize("hasAnyRole('ADMIN', 'GERENTE', 'JDV') or " +
              "(hasAnyRole('VENDEDOR', 'ANALISTA') and @ownershipChecker.isOwner(#id, authentication))")
public OportunidadDTO getOportunidad(Long id) { ... }
```

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
            if (!auth.tieneRol(ADMIN, GERENTE, ANALISTA)) {
                throw new PermisoInsuficienteException(
                    "Solo admin, gerencia o analista pueden confirmar el paso a Facturado");
            }
        }
        // ... resto de la lógica
    }
}
```

---

## 4. Casos especiales

### 4.1 Empresa sin vendedor asignado

Si `empresas.id_vendedor = NULL`, la empresa solo es visible para `admin`, `gerencia` y `jdv`. Ningún `vendedor` ni `analista` puede verla.

### 4.2 Tareas sin asignar

Si `tareas.id_asignado = NULL`, la tarea la puede ver y completar cualquier `vendedor` o `analista` que sea el vendedor de la oportunidad o empresa vinculada. Si ninguno aplica, solo la ven admin/gerencia/jdv.

### 4.3 Analista financiero en fases futuras

En el MVP el `analista` tiene visibilidad de vendedor con el único privilegio adicional de confirmar el paso a `facturado`. En fases posteriores, cuando se implemente el módulo financiero, el analista necesitará acceso a campos que hoy no existen. El panel de administración de permisos (post-MVP) permitirá configurar esto sin una nueva migración de roles.

### 4.4 Reasignación y visibilidad histórica

Cuando una empresa se reasigna de un vendedor a otro, el vendedor anterior pierde visibilidad sobre la empresa y sus oportunidades activas. Sin embargo, las oportunidades en `facturado` o `cerrado` donde él era el vendedor snapshot siguen siendo visibles para él en una vista de historial (filtro `incluir_cerradas=true` en `/oportunidades`). Esto garantiza que pueda consultar su historial de operaciones para comisiones futuras.
