package pe.quantum.crm.domain.oportunidades

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.modelos.dto.ModeloResumen
import pe.quantum.crm.domain.oportunidades.dto.ActualizarOportunidadItemRequest
import pe.quantum.crm.domain.oportunidades.dto.CrearOportunidadItemRequest
import pe.quantum.crm.domain.oportunidades.dto.ModeloEnOportunidadDto
import pe.quantum.crm.domain.oportunidades.dto.OportunidadItemDto
import pe.quantum.crm.domain.oportunidades.dto.OportunidadItemVinculo
import pe.quantum.crm.shared.PoliticaDescuento
import pe.quantum.crm.shared.enums.EstadoOportunidad
import pe.quantum.crm.shared.exception.AprobacionRequeridaException
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * CRUD de los items de una oportunidad (plan-06-migrar-dominio-items.md, B3).
 *
 * Vive en el mismo modulo que `OportunidadServiceImpl`, asi que depende de
 * `OportunidadRepository` y de la entidad `Oportunidad` directamente: ArchUnit
 * solo restringe dependencias ENTRE modulos distintos (CLAUDE.md regla 12).
 */
@Service
@Suppress("TooManyFunctions") // CRUD de items del nucleo del pipeline (B3.x).
class OportunidadItemServiceImpl(
    private val itemRepository: OportunidadItemRepository,
    private val oportunidadRepository: OportunidadRepository,
    private val modeloService: ModeloService,
    private val visibilidad: OportunidadVisibilidad,
) : OportunidadItemService {
    @Transactional(readOnly = true)
    override fun vinculoVisible(
        idItem: Long,
        usuario: UsuarioActual,
    ): OportunidadItemVinculo {
        val (item, oportunidad) = itemVisible(idItem, usuario)
        return OportunidadItemVinculo(
            id = requireNotNull(item.id),
            idOportunidad = item.idOportunidad,
            idEmpresa = oportunidad.idEmpresa,
            descuento = item.descuento,
        )
    }

    @Transactional
    override fun crear(
        idOportunidad: Long,
        request: CrearOportunidadItemRequest,
        usuario: UsuarioActual,
    ): OportunidadItemDto {
        visibilidad.rechazarSiEsApoyo(usuario)
        val oportunidad = oportunidadVisible(idOportunidad, usuario)
        validarLimiteDescuento(request.descuento, usuario)
        val modelo = modeloService.resumen(request.idModelo)
        val ahora = LocalDateTime.now()
        val item =
            OportunidadItem(
                idOportunidad = requireNotNull(oportunidad.id),
                idModelo = modelo.id,
                cantidad = request.cantidad,
                // Igual que `OportunidadServiceImpl.crear()`: sin precio explicito manda el precio base del modelo.
                precioVenta = request.precioVenta ?: modelo.precioBase,
                descuento = request.descuento,
                createdAt = ahora,
                createdBy = usuario.id,
                updatedAt = ahora,
                updatedBy = usuario.id,
            )
        request.cuotaFinanciadora?.let { item.cuotaFinanciadora = it }
        val guardado = itemRepository.save(item)
        sincronizarColumnasViejas(oportunidad, usuario.id)
        return guardado.aDto(modelo)
    }

    @Transactional
    override fun actualizar(
        idItem: Long,
        request: ActualizarOportunidadItemRequest,
        usuario: UsuarioActual,
    ): OportunidadItemDto {
        visibilidad.rechazarSiEsApoyo(usuario)
        val (item, oportunidad) = itemVisible(idItem, usuario)
        validarLimiteDescuento(request.descuento, usuario)
        val advertencias = mutableListOf<String>()

        // reglas_negocio.md §12.2: al cambiar de modelo el precio se pisa con el
        // `precio_base` del modelo nuevo SOLO si el actual seguia siendo el
        // `precio_base` del modelo anterior (nadie lo edito a mano). Si fue editado,
        // se conserva y sale una advertencia. El monto se recalcula solo en `aDto`
        // via `MontoTotal.calcular`.
        val nuevoModeloId = request.idModelo
        if (nuevoModeloId != null && nuevoModeloId != item.idModelo) {
            val modeloNuevo = modeloService.resumen(nuevoModeloId)
            val precioBaseAnterior = modeloService.resumen(item.idModelo).precioBase
            val precioNoEditado =
                item.precioVenta == null ||
                    (precioBaseAnterior != null && item.precioVenta?.compareTo(precioBaseAnterior) == 0)
            if (precioNoEditado) {
                item.precioVenta = modeloNuevo.precioBase
            } else {
                advertencias += "El precio unitario fue editado manualmente y no se actualizó con el nuevo modelo"
            }
            item.idModelo = nuevoModeloId
        }
        request.cantidad?.let { item.cantidad = it }
        request.precioVenta?.let { item.precioVenta = it }
        request.descuento?.let { item.descuento = it }
        request.cuotaFinanciadora?.let { item.cuotaFinanciadora = it }
        item.updatedAt = LocalDateTime.now()
        item.updatedBy = usuario.id

        val guardado = itemRepository.save(item)
        sincronizarColumnasViejas(oportunidad, usuario.id)
        return guardado.aDto(modeloService.resumen(guardado.idModelo), advertencias)
    }

    @Transactional
    override fun eliminar(
        idItem: Long,
        usuario: UsuarioActual,
    ) {
        visibilidad.rechazarSiEsApoyo(usuario)
        val (item, oportunidad) = itemVisible(idItem, usuario)
        // D17: una oportunidad no puede quedarse sin items. Se cuenta ANTES de borrar.
        val items = itemRepository.findByIdOportunidadOrderByIdAsc(item.idOportunidad)
        if (items.size <= 1) {
            throw ConflictoException("ULTIMO_ITEM_NO_ELIMINABLE", "La oportunidad debe tener al menos un ítem")
        }
        itemRepository.delete(item)
        sincronizarColumnasViejas(oportunidad, usuario.id)
    }

    @Transactional(readOnly = true)
    override fun porOportunidades(idsOportunidad: Collection<Long>): Map<Long, List<OportunidadItemDto>> {
        val items =
            if (idsOportunidad.isEmpty()) {
                emptyList()
            } else {
                itemRepository.findByIdOportunidadInOrderByIdAsc(idsOportunidad)
            }
        if (items.isEmpty()) {
            return emptyMap()
        }
        // Modelos por lotes: un `resumen()` por item seria N+1 en el listado paginado.
        val modelos = modeloService.resumenPorIds(items.map { it.idModelo }.distinct())
        return items
            .groupBy { it.idOportunidad }
            .mapValues { (_, deLaOportunidad) -> deLaOportunidad.map { it.aDto(modelos[it.idModelo]) } }
    }

    @Transactional(readOnly = true)
    override fun montoTotalPorOportunidades(idsOportunidad: Collection<Long>): Map<Long, BigDecimal> {
        if (idsOportunidad.isEmpty()) {
            return emptyMap()
        }
        return itemRepository
            .findByIdOportunidadInOrderByIdAsc(idsOportunidad)
            .groupBy { it.idOportunidad }
            .mapNotNull { (idOportunidad, items) ->
                MontoTotal.sumarItems(items)?.let { idOportunidad to it }
            }.toMap()
    }

    @Transactional
    override fun aplicarDescuentoAprobado(
        idItem: Long,
        descuento: BigDecimal,
        idAprobador: Long,
    ) {
        // Sin `itemVisible`/`validarLimiteDescuento`: no hay usuario en sesion y la
        // aprobacion de la solicitud ES la autorizacion (el limite se verifico al
        // crearla). Ver KDoc de `OportunidadItemService.aplicarDescuentoAprobado`.
        val item =
            itemRepository.findById(idItem).orElseThrow {
                ConflictoException("SOLICITUD_NO_APLICABLE", "El ítem de la solicitud ya no existe")
            }
        val oportunidad =
            oportunidadRepository.findById(item.idOportunidad).orElseThrow {
                ConflictoException("SOLICITUD_NO_APLICABLE", "La oportunidad de la solicitud ya no existe")
            }
        if (oportunidad.estado == EstadoOportunidad.cerrado || oportunidad.estado == EstadoOportunidad.facturado) {
            throw ConflictoException(
                "SOLICITUD_NO_APLICABLE",
                "La oportunidad está en ${oportunidad.estado.name}; el descuento ya no aplica",
            )
        }
        item.descuento = descuento
        item.updatedAt = LocalDateTime.now()
        item.updatedBy = idAprobador
        itemRepository.save(item)
        // B12: el bug que cierra esta tarea. Antes se escribia `monto_total` desde
        // las columnas planas (`precioUnitario` es NULL con 2+ items ⇒ monto NULL);
        // ahora se recalcula como la suma real de los items.
        sincronizarColumnasViejas(oportunidad, idAprobador)
    }

    /**
     * CODIGO PUENTE — se retira por completo al cerrar el Plan C
     * (plan-05-mapa-migrar-items.md, decision D21). NO es arquitectura final.
     *
     * Mientras `reportes` e `inicio` sigan leyendo las columnas planas de
     * `oportunidades` (`id_modelo`, `cantidad`, `precio_unitario`, `dcto`,
     * `monto_total`), cada escritura sobre un item las recalcula desde el estado
     * resultante de TODOS los items, dentro de la misma transaccion, para que esos
     * modulos no muestren numeros desactualizados en el hueco entre planes. Cuando
     * lean `oportunidad_items` directamente y las columnas se retiren, esta funcion
     * y sus llamadas desaparecen.
     *
     * Formula exacta de D21:
     * - `cantidad` = suma de las cantidades de los items.
     * - `monto_total` = [MontoTotal.sumarItems].
     * - `precio_unitario` / `dcto` = los del unico item si hay exactamente uno; si
     *   hay dos o mas, NULL: un "precio unitario" no significa nada con varios
     *   modelos, y NULL es honesto — nunca se inventa un promedio que reportes
     *   leeria como si fuera un precio real.
     * - `id_modelo` = el del item de MENOR id (el primero creado, estable ante
     *   ediciones posteriores). Es la unica columna NOT NULL del grupo, y por eso
     *   solo se toca cuando queda al menos un item; el guard de D17 garantiza que
     *   ese sea siempre el caso.
     */
    private fun sincronizarColumnasViejas(
        oportunidad: Oportunidad,
        idUsuario: Long,
    ) {
        val items = itemRepository.findByIdOportunidadOrderByIdAsc(requireNotNull(oportunidad.id))
        val unico = items.singleOrNull()
        oportunidad.cantidad = items.mapNotNull { it.cantidad }.takeIf { it.isNotEmpty() }?.sum()
        oportunidad.montoTotal = MontoTotal.sumarItems(items)
        oportunidad.precioUnitario = unico?.precioVenta
        oportunidad.dcto = unico?.descuento
        items.firstOrNull()?.let { oportunidad.idModelo = it.idModelo }
        oportunidad.updatedAt = LocalDateTime.now()
        oportunidad.updatedBy = idUsuario
        oportunidadRepository.save(oportunidad)
    }

    /** Descuento sobre el limite del rol: 422, el cambio requiere solicitud (mismo criterio que `OportunidadServiceImpl`). */
    private fun validarLimiteDescuento(
        descuento: BigDecimal?,
        usuario: UsuarioActual,
    ) {
        if (PoliticaDescuento.excedeLimite(usuario.rol, descuento)) {
            val limite = requireNotNull(PoliticaDescuento.limitePara(usuario.rol))
            throw AprobacionRequeridaException(
                "Un descuento de ${descuento!!.toPlainString()}% supera tu límite de ${limite.toPlainString()}%; " +
                    "requiere aprobación",
            )
        }
    }

    /** Item + su oportunidad dueña, ambos resueltos con la visibilidad de la oportunidad (IDOR: ajeno → 404). */
    private fun itemVisible(
        idItem: Long,
        usuario: UsuarioActual,
    ): Pair<OportunidadItem, Oportunidad> {
        val item =
            itemRepository.findById(idItem).orElseThrow { NoEncontradoException("El ítem de la oportunidad no existe") }
        val oportunidad = oportunidadVisible(item.idOportunidad, usuario)
        return item to oportunidad
    }

    /**
     * Misma regla que `OportunidadServiceImpl.visible()`: la entidad se resuelve y
     * despues se filtra por [OportunidadVisibilidad.alcanza]. Ajena → 404, nunca
     * 403 (CLAUDE.md regla 14).
     */
    private fun oportunidadVisible(
        idOportunidad: Long,
        usuario: UsuarioActual,
    ): Oportunidad {
        // La FK garantiza que la oportunidad dueña exista; el `orElseThrow` es defensivo.
        val oportunidad =
            oportunidadRepository.findById(idOportunidad).orElseThrow { NoEncontradoException("La oportunidad no existe") }
        if (!visibilidad.alcanza(oportunidad, usuario)) {
            throw NoEncontradoException("La oportunidad no existe")
        }
        return oportunidad
    }

    private fun OportunidadItem.aDto(
        modelo: ModeloResumen?,
        advertencias: List<String> = emptyList(),
    ): OportunidadItemDto =
        OportunidadItemDto(
            id = requireNotNull(id),
            idModelo = idModelo,
            modelo =
                modelo?.let {
                    ModeloEnOportunidadDto(id = it.id, codigo = it.codigo, precioBase = it.precioBase?.toPlainString())
                },
            cantidad = cantidad,
            precioVenta = precioVenta?.toPlainString(),
            descuento = descuento?.toPlainString(),
            cuotaFinanciadora = cuotaFinanciadora.toPlainString(),
            montoItem = MontoTotal.calcular(cantidad, precioVenta, descuento)?.toPlainString(),
            advertencias = advertencias,
        )
}
