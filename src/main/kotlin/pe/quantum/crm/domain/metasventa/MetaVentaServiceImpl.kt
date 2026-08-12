package pe.quantum.crm.domain.metasventa

import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.RolEmpleado
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empleados.dto.nombreCompleto
import pe.quantum.crm.domain.metasventa.dto.CrearMetaVentaRequest
import pe.quantum.crm.domain.metasventa.dto.EditarMetaVentaRequest
import pe.quantum.crm.domain.metasventa.dto.MetaVentaDto
import pe.quantum.crm.domain.metasventa.dto.MetaVentaFiltros
import pe.quantum.crm.domain.metasventa.dto.MetaVentaResumen
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.shared.CamposOrdenables
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.comoInstanteUtc
import pe.quantum.crm.shared.enums.EstadoMeta
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime

@Service
@Suppress("TooManyFunctions") // Modulo de metas de venta: crear/editar/aprobar/rechazar/listar/detalle + privados.
class MetaVentaServiceImpl(
    private val metaVentaRepository: MetaVentaRepository,
    private val empleadoService: EmpleadoService,
    private val notificacionService: NotificacionService,
) : MetaVentaService {
    @Transactional
    override fun crear(
        request: CrearMetaVentaRequest,
        usuario: UsuarioActual,
    ): MetaVentaDto {
        if (usuario.rol !in ROLES_PROPONENTES) {
            throw PermisoInsuficienteException("Solo jdv, gerencia o admin pueden proponer metas de venta")
        }
        val idEmpleado = requireNotNull(request.idEmpleado)
        val anio = requireNotNull(request.anio)
        if (!empleadoService.esAsignableComoVendedor(idEmpleado)) {
            throw ValidacionException("id_empleado debe ser un vendedor o jdv activo", field = "id_empleado")
        }
        val esGerenciaOAdmin = usuario.rol == "gerencia" || usuario.rol == "admin"
        val existente = metaVentaRepository.findByIdEmpleadoAndAnio(idEmpleado, anio)
        if (existente != null && existente.estado != EstadoMeta.rechazada && !esGerenciaOAdmin) {
            throw ConflictoException(
                "META_YA_EXISTE",
                "Ya existe una meta ${existente.estado.name} para ese empleado y año; usa PATCH para modificarla",
            )
        }
        // Se resuelve ANTES de guardar: despues, `existente` y `meta` son la misma fila.
        val esNueva = existente == null
        val meta = existente ?: MetaVenta(idEmpleado = idEmpleado, anio = anio, idPropuestoPor = usuario.id)
        meta.establecerMeses(request.meses())

        if (esGerenciaOAdmin) {
            aprobarDirecto(meta, usuario)
            val guardada = metaVentaRepository.save(meta)
            // Una meta que no existia no se "modifico". El mensaje va al vendedor y a
            // quien la propuso: decir lo que de verdad paso no es cosmetica.
            if (esNueva) {
                notificarResolucion(guardada, usuario, TipoNotificacion.meta_aprobada, "estableció")
            } else {
                notificarResolucion(guardada, usuario, TipoNotificacion.meta_modificada, "modificó")
            }
            return toDtos(listOf(guardada)).first()
        }
        meta.idPropuestoPor = usuario.id
        meta.estado = EstadoMeta.propuesta
        meta.idResolutor = null
        meta.motivoRechazo = null
        meta.resolvedAt = null
        val guardada = metaVentaRepository.save(meta)
        notificarPropuesta(guardada, usuario)
        return toDtos(listOf(guardada)).first()
    }

    @Transactional
    @Suppress("MagicNumber") // Indices posicionales de los 12 meses; nombrarlos uno a uno no aporta.
    override fun editar(
        id: Long,
        request: EditarMetaVentaRequest,
        usuario: UsuarioActual,
    ): MetaVentaDto {
        requireGerenciaOAdmin(usuario)
        val meta = entidad(id)
        if (meta.estado == EstadoMeta.rechazada) {
            throw ConflictoException("META_RECHAZADA", "No se puede editar una meta rechazada; debe volver a proponerse")
        }
        val valores = meta.meses().toMutableList()
        request.metaEnero?.let { valores[0] = it }
        request.metaFebrero?.let { valores[1] = it }
        request.metaMarzo?.let { valores[2] = it }
        request.metaAbril?.let { valores[3] = it }
        request.metaMayo?.let { valores[4] = it }
        request.metaJunio?.let { valores[5] = it }
        request.metaJulio?.let { valores[6] = it }
        request.metaAgosto?.let { valores[7] = it }
        request.metaSeptiembre?.let { valores[8] = it }
        request.metaOctubre?.let { valores[9] = it }
        request.metaNoviembre?.let { valores[10] = it }
        request.metaDiciembre?.let { valores[11] = it }
        meta.establecerMeses(valores)
        aprobarDirecto(meta, usuario)
        metaVentaRepository.save(meta)
        notificarResolucion(meta, usuario, TipoNotificacion.meta_modificada, "modificó")
        return toDtos(listOf(meta)).first()
    }

    @Transactional
    override fun aprobar(
        id: Long,
        usuario: UsuarioActual,
    ): MetaVentaDto {
        requireGerenciaOAdmin(usuario)
        val meta = pendienteParaResolver(id)
        aprobarDirecto(meta, usuario)
        metaVentaRepository.save(meta)
        notificarResolucion(meta, usuario, TipoNotificacion.meta_aprobada, "aprobó")
        return toDtos(listOf(meta)).first()
    }

    @Transactional
    override fun rechazar(
        id: Long,
        motivo: String,
        usuario: UsuarioActual,
    ): MetaVentaDto {
        if (motivo.isBlank()) {
            throw ValidacionException("El motivo del rechazo es obligatorio", field = "motivo")
        }
        requireGerenciaOAdmin(usuario)
        val meta = pendienteParaResolver(id)
        meta.estado = EstadoMeta.rechazada
        meta.idResolutor = usuario.id
        meta.motivoRechazo = motivo
        meta.resolvedAt = LocalDateTime.now()
        meta.updatedAt = LocalDateTime.now()
        metaVentaRepository.save(meta)
        notificarResolucion(meta, usuario, TipoNotificacion.meta_rechazada, "rechazó")
        return toDtos(listOf(meta)).first()
    }

    @Transactional(readOnly = true)
    override fun listar(
        filtros: MetaVentaFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<MetaVentaDto> {
        val pageRequest = Paginacion.pageRequest(page, perPage, sort, dir, CAMPOS_ORDENABLES)
        val resultado = metaVentaRepository.findAll(especificacion(filtros, usuario), pageRequest)
        val meta = Paginacion.meta(pageRequest.pageNumber + 1, pageRequest.pageSize, resultado.totalElements)
        return Paginado(toDtos(resultado.content), meta)
    }

    @Transactional(readOnly = true)
    override fun detalle(
        id: Long,
        usuario: UsuarioActual,
    ): MetaVentaDto = toDtos(listOf(visible(id, usuario))).first()

    @Transactional(readOnly = true)
    override fun aprobadasPorEmpleadosYAnio(
        idsEmpleado: Collection<Long>,
        anio: Int,
    ): Map<Long, MetaVentaResumen> {
        if (idsEmpleado.isEmpty()) return emptyMap()
        return metaVentaRepository
            .findByIdEmpleadoInAndAnioAndEstado(idsEmpleado, anio, EstadoMeta.aprobada)
            .associate {
                it.idEmpleado to
                    MetaVentaResumen(idEmpleado = it.idEmpleado, anio = it.anio, metaAnual = it.metaAnual, metaPorMes = it.meses())
            }
    }

    // ── privados ───────────────────────────────────

    private fun aprobarDirecto(
        meta: MetaVenta,
        usuario: UsuarioActual,
    ) {
        meta.estado = EstadoMeta.aprobada
        meta.idResolutor = usuario.id
        meta.resolvedAt = LocalDateTime.now()
        meta.motivoRechazo = null
        meta.updatedAt = LocalDateTime.now()
    }

    private fun requireGerenciaOAdmin(usuario: UsuarioActual) {
        if (usuario.rol != "gerencia" && usuario.rol != "admin") {
            throw PermisoInsuficienteException("Solo gerencia o admin pueden resolver metas de venta")
        }
    }

    private fun pendienteParaResolver(id: Long): MetaVenta {
        val meta = metaVentaRepository.findByIdForUpdate(id) ?: throw NoEncontradoException("La meta de venta no existe")
        if (meta.estado != EstadoMeta.propuesta) {
            throw ConflictoException("META_YA_RESUELTA", "La meta ya fue resuelta")
        }
        return meta
    }

    private fun entidad(id: Long): MetaVenta =
        metaVentaRepository.findById(id).orElseThrow {
            NoEncontradoException("La meta de venta no existe")
        }

    /** IDOR: meta ajena para vendedor/analista → 404, no 403. */
    private fun visible(
        id: Long,
        usuario: UsuarioActual,
    ): MetaVenta {
        val meta = entidad(id)
        val alcanzable =
            when (usuario.rol) {
                "admin", "gerencia", "jdv" -> true // ven todo el equipo (unico jdv, sin sub-equipos)
                else -> meta.idEmpleado == usuario.id
            }
        if (!alcanzable) throw NoEncontradoException("La meta de venta no existe")
        return meta
    }

    private fun especificacion(
        filtros: MetaVentaFiltros,
        usuario: UsuarioActual,
    ): Specification<MetaVenta> =
        Specification { root, _, cb ->
            val predicados = mutableListOf<Predicate>()
            if (usuario.rol == "vendedor" || usuario.rol == "analista") {
                predicados += cb.equal(root.get<Long>("idEmpleado"), usuario.id)
            }
            filtros.idEmpleado?.let { predicados += cb.equal(root.get<Long>("idEmpleado"), it) }
            filtros.anio?.let { predicados += cb.equal(root.get<Int>("anio"), it) }
            filtros.estado?.let { estado ->
                runCatching { EstadoMeta.valueOf(estado) }.getOrNull()?.let {
                    predicados += cb.equal(root.get<EstadoMeta>("estado"), it)
                }
            }
            cb.and(*predicados.toTypedArray())
        }

    private fun notificarPropuesta(
        meta: MetaVenta,
        usuario: UsuarioActual,
    ) {
        val (actorNombre, empleadoNombre) = nombres(usuario.id, meta.idEmpleado)
        notificacionService.notificar(
            destinatarios = empleadoService.idsActivosPorRol(RolEmpleado.gerencia).toSet(),
            idActor = usuario.id,
            tipo = TipoNotificacion.meta_propuesta,
            mensaje = "$actorNombre propuso la meta de venta ${meta.anio} de $empleadoNombre",
            entidadTipo = EntidadNotificacion.meta_venta,
            entidadId = requireNotNull(meta.id),
        )
    }

    private fun notificarResolucion(
        meta: MetaVenta,
        usuario: UsuarioActual,
        tipo: TipoNotificacion,
        verbo: String,
    ) {
        val (actorNombre, empleadoNombre) = nombres(usuario.id, meta.idEmpleado)
        val sufijo = meta.motivoRechazo?.let { ": $it" } ?: ""
        notificacionService.notificar(
            destinatarios = setOf(meta.idPropuestoPor, meta.idEmpleado),
            idActor = usuario.id,
            tipo = tipo,
            mensaje = "$actorNombre $verbo la meta de venta ${meta.anio} de $empleadoNombre$sufijo",
            entidadTipo = EntidadNotificacion.meta_venta,
            entidadId = requireNotNull(meta.id),
        )
    }

    private fun nombres(
        idActor: Long,
        idEmpleado: Long,
    ): Pair<String, String> {
        val resumenes = empleadoService.resumenPorIds(listOf(idActor, idEmpleado))
        return (resumenes[idActor]?.nombreCompleto() ?: "Alguien") to (resumenes[idEmpleado]?.nombreCompleto() ?: "un empleado")
    }

    private fun toDtos(metas: List<MetaVenta>): List<MetaVentaDto> {
        if (metas.isEmpty()) return emptyList()
        val idsEmpleados = (metas.map { it.idEmpleado } + metas.map { it.idPropuestoPor } + metas.mapNotNull { it.idResolutor }).distinct()
        val empleados = empleadoService.resumenPorIds(idsEmpleados)
        return metas.map { m -> m.toDto(empleados) }
    }

    private fun MetaVenta.toDto(empleados: Map<Long, EmpleadoResumen>) =
        MetaVentaDto(
            id = requireNotNull(id),
            idEmpleado = idEmpleado,
            empleado = empleados[idEmpleado],
            anio = anio,
            metaEnero = metaEnero,
            metaFebrero = metaFebrero,
            metaMarzo = metaMarzo,
            metaAbril = metaAbril,
            metaMayo = metaMayo,
            metaJunio = metaJunio,
            metaJulio = metaJulio,
            metaAgosto = metaAgosto,
            metaSeptiembre = metaSeptiembre,
            metaOctubre = metaOctubre,
            metaNoviembre = metaNoviembre,
            metaDiciembre = metaDiciembre,
            metaAnual = metaAnual,
            estado = estado.name,
            propuestoPor = empleados[idPropuestoPor],
            resolutor = idResolutor?.let { empleados[it] },
            motivoRechazo = motivoRechazo,
            resolvedAt = resolvedAt?.comoInstanteUtc(),
            createdAt = createdAt.comoInstanteUtc(),
        )

    private companion object {
        val ROLES_PROPONENTES = setOf("jdv", "gerencia", "admin")

        /** Allowlist de `sort` de GET /metas-venta; el primero es el orden por defecto. */
        val CAMPOS_ORDENABLES =
            CamposOrdenables("anio", "id", "estado", "metaAnual", "createdAt", "updatedAt")
    }
}
