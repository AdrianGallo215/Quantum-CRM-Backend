# Sincronización automática de `id_vendedor` entre `empresas` y `oportunidades`

## Contexto

`reglas_negocio.md` §1.1 establece que ningún dato puede vivir en dos lugares, con una única excepción documentada: el snapshot de `oportunidades.id_vendedor`, copiado de `empresas.id_vendedor` al crear la oportunidad (§8.4). Hasta ahora, ese snapshot solo cambiaba mediante un traspaso manual y explícito por oportunidad individual (`PATCH /oportunidades/:id/vendedor`, §8.3); reasignar el vendedor de la empresa (`PATCH /empresas/:id/vendedor`) no propagaba el cambio a sus oportunidades.

Esto permitía que una empresa y sus oportunidades activas quedaran con vendedores distintos indefinidamente — el propio caso que se reporta como bug. Se decide (con el usuario, sesión 2026-07-10) que esto es un cambio de regla de negocio, no solo un fix: **el vendedor de una empresa y el de todas sus oportunidades activas deben ser siempre el mismo.** El traspaso manual por oportunidad individual queda eliminado; el único punto de entrada para cambiar el vendedor de una oportunidad activa es reasignar la empresa.

Las oportunidades cerradas (`facturado`, `cerrado`) **no** se ven afectadas — conservan el `id_vendedor` que tenían al momento del cierre, sin excepción. Esto ya estaba documentado y no cambia.

## Decisiones tomadas

1. **Cascada automática**, no advertencia ni operación separada. Reasignar `empresas.id_vendedor` actualiza en la misma transacción todas las oportunidades con `estado IN ('evaluacion_calidda', 'documentos_legales')` de esa empresa.
2. **Se elimina** el endpoint/servicio de traspaso manual por oportunidad individual. Ya no tiene sentido: permitir que una oportunidad puntual tenga un vendedor distinto al de su empresa rompería el invariante inmediatamente.
3. **Alcance:** solo este caso (`id_vendedor`). No se audita el resto del schema en esta tarea — se documenta el patrón para que sea reusable si aparece otro caso.
4. **Mecanismo: evento de aplicación, no trigger SQL.** El proyecto no tiene triggers/funciones en ninguna migración; toda la lógica de negocio vive en los servicios Kotlin (p.ej. `actualizarEstadoCartera()` es un método de servicio). Se mantiene esa convención.
5. **Límite de módulos:** `oportunidades` ya depende de `empresas` (`OportunidadServiceImpl` inyecta `EmpresaService`). Si `EmpresaServiceImpl` llamara directamente a `OportunidadService` se crearía una dependencia circular entre beans de Spring e invertiría la capa de dominio ya establecida (empresas es fase 2, oportunidades es fase 3 y depende de empresas, nunca al revés — regla 12 de `CLAUDE.md`, verificada por ArchUnit). Se resuelve con un evento de aplicación: `empresas` no conoce a `oportunidades`.

## Diseño técnico

### Evento

Nueva clase en el módulo `empresas`:

```kotlin
data class VendedorEmpresaReasignadoEvent(
    val idEmpresa: Long,
    val idVendedorNuevo: Long,
    val idActor: Long,
)
```

### Publicación (módulo `empresas`)

`EmpresaServiceImpl.reasignarVendedor()`:
- Recibe `ApplicationEventPublisher` como dependencia nueva del constructor.
- Después de guardar la empresa con el nuevo `idVendedor`, publica `VendedorEmpresaReasignadoEvent(id, idVendedor, usuario.id)`.
- La notificación existente `empresa_asignada` no cambia.

### Escucha y cascada (módulo `oportunidades`)

`OportunidadServiceImpl` gana un método `@EventListener` (sin declarar en la interfaz pública `OportunidadService` — es un detalle de implementación, no parte del contrato del módulo):

```kotlin
@EventListener
@Transactional
fun onVendedorEmpresaReasignado(event: VendedorEmpresaReasignadoEvent) {
    val activas = oportunidadRepository.findByIdEmpresaAndEstadoIn(
        event.idEmpresa,
        listOf(EstadoOportunidad.evaluacion_calidda, EstadoOportunidad.documentos_legales),
    ).filter { it.idVendedor != event.idVendedorNuevo }

    if (activas.isEmpty()) return

    activas.forEach {
        it.idVendedor = event.idVendedorNuevo
        it.updatedAt = LocalDateTime.now()
        it.updatedBy = event.idActor
    }
    oportunidadRepository.saveAll(activas)

    val actor = empleadoService.resumenPorIds(listOf(event.idActor))[event.idActor]
    val empresa = empresaService.resumenPorIds(listOf(event.idEmpresa))[event.idEmpresa]
    activas.forEach {
        notificacionService.notificar(
            destinatarios = setOf(event.idVendedorNuevo),
            idActor = event.idActor,
            tipo = TipoNotificacion.oportunidad_traspasada,
            mensaje = "${actor?.nombreCompleto()} te traspasó la oportunidad de ${empresa?.razonSocial}",
            entidadTipo = EntidadNotificacion.oportunidad,
            entidadId = requireNotNull(it.id),
        )
    }
}
```

Se usa `@EventListener` (síncrono, dentro de la misma transacción que abrió `reasignarVendedor()`), no `@TransactionalEventListener(phase = AFTER_COMMIT)` — si la cascada falla, debe revertirse también la reasignación de la empresa (regla §1.2, atomicidad obligatoria).

Nuevo método en `OportunidadRepository`:

```kotlin
fun findByIdEmpresaAndEstadoIn(idEmpresa: Long, estados: Collection<EstadoOportunidad>): List<Oportunidad>
```

### Eliminación del traspaso manual

Se eliminan por completo:
- `PATCH /oportunidades/:id/vendedor` en `OportunidadController`
- `OportunidadService.traspasar` / `OportunidadServiceImpl.traspasar`
- DTO `TraspasarVendedorRequest`
- Test `` `traspasar notifica al vendedor destino` `` en `OportunidadServiceImplTest`

### Backfill de datos existentes

Nueva migración Flyway `V23__sync_oportunidad_vendedor_activas.sql` que corrige el drift ya existente en producción/datos de prueba, una sola vez:

```sql
UPDATE oportunidades o
SET id_vendedor = e.id_vendedor,
    updated_at = CURRENT_TIMESTAMP
FROM empresas e
WHERE o.id_empresa = e.id
  AND o.estado NOT IN ('facturado', 'cerrado')
  AND e.id_vendedor IS NOT NULL
  AND o.id_vendedor <> e.id_vendedor;
```

`updated_by` no se toca en el backfill (no hay un actor humano real detrás de esta corrección de datos; se deja el `updated_by` existente de cada fila).

### Documentación a actualizar

- `docs/reglas_negocio.md`: §1.1 (aclarar que el snapshot ahora se sincroniza automáticamente en oportunidades activas), §8.2 (quita la mención de "las activas pueden traspasar mediante traspaso explícito"; ahora es automático), §8.3 (se reemplaza la sección de traspaso manual por la descripción del mecanismo de cascada), §8.4 (aclarar que el snapshot inicial se resincroniza automáticamente ante cualquier reasignación posterior de la empresa, mientras la oportunidad esté activa).
- `docs/contrato_api.md`: eliminar la sección `PATCH /oportunidades/:id/vendedor` y la fila correspondiente en la tabla de endpoints; añadir una nota en `PATCH /empresas/:id/vendedor` indicando que cascada a oportunidades activas.
- `docs/matriz_permisos.md`: eliminar la fila "Traspasar oportunidad"; ajustar la descripción de `jdv` ("Puede reasignar y traspasar" → "Puede reasignar; el traspaso de oportunidades activas es automático").

## Testing (TDD obligatorio)

1. `OportunidadServiceImplTest`: nuevo test para `onVendedorEmpresaReasignado` — actualiza solo las activas, ignora facturado/cerrado, no duplica notificación si el vendedor ya coincide, actualiza `updatedBy` al actor del evento.
2. `EmpresaServiceImplTest`: verificar que `reasignarVendedor()` publica el evento con los datos correctos.
3. Test de integración (Testcontainers) end-to-end: `PATCH /empresas/:id/vendedor` → verificar que las oportunidades activas de esa empresa cambiaron y las cerradas no.
4. Verificar que `PATCH /oportunidades/:id/vendedor` ya no existe (404/405) y que el `OportunidadController` no lo expone.
5. Test de la migración de backfill (o verificación manual con Testcontainers) sobre datos sembrados con drift intencional.

## Fuera de alcance

- No se audita el resto del schema buscando otros campos espejo — solo se documenta el patrón evento→listener como molde reusable.
- No se toca el módulo de comisiones (post-MVP); la nota de §8.3 sobre "la comisión corresponde al vendedor actual" se conserva conceptualmente pero ya no depende de un traspaso manual, sino del vendedor vigente en el momento de facturar.
