package pe.quantum.crm.domain.empresas

import jakarta.persistence.criteria.Predicate
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.nombreCompleto
import pe.quantum.crm.domain.empresas.dto.ActualizarEmpresaRequest
import pe.quantum.crm.domain.empresas.dto.CambioEstadoCartera
import pe.quantum.crm.domain.empresas.dto.CarteraMaestraDto
import pe.quantum.crm.domain.empresas.dto.CrearEmpresaRequest
import pe.quantum.crm.domain.empresas.dto.EmpresaDetalleDto
import pe.quantum.crm.domain.empresas.dto.EmpresaFiltros
import pe.quantum.crm.domain.empresas.dto.EmpresaListaDto
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.empresas.dto.EmpresaVinculo
import pe.quantum.crm.domain.empresas.dto.RucCheckDto
import pe.quantum.crm.domain.empresas.dto.toResumen
import pe.quantum.crm.domain.empresas.dto.toVinculo
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.enums.EstadoCartera
import pe.quantum.crm.shared.enums.Segmento
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.EstadoInvalidoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.exception.RucDuplicadoException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime

@Service
class EmpresaServiceImpl(
    private val empresaRepository: EmpresaRepository,
    private val empleadoService: EmpleadoService,
    private val notificacionService: NotificacionService,
    private val eventPublisher: ApplicationEventPublisher,
) : EmpresaService {
    @Transactional(readOnly = true)
    override fun listar(
        filtros: EmpresaFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<EmpresaListaDto> {
        val pageRequest = Paginacion.pageRequest(page, perPage, sort, dir, defaultSort = "id")
        val resultado = empresaRepository.findAll(especificacion(filtros, usuario), pageRequest)
        val vendedores =
            empleadoService.resumenPorIds(resultado.content.mapNotNull { it.idVendedor })
        val items =
            resultado.content.map { empresa ->
                EmpresaListaDto(
                    id = requireNotNull(empresa.id),
                    ruc = empresa.ruc,
                    razonSocial = empresa.razonSocial,
                    estadoSunat = empresa.estadoSunat,
                    condicionSunat = empresa.condicionSunat,
                    estadoCartera = empresa.estadoCartera.name,
                    distrito = empresa.distrito,
                    idVendedor = empresa.idVendedor,
                    vendedor = empresa.idVendedor?.let { vendedores[it] },
                    segmentos = empresa.segmentos.map { it.name }.sorted(),
                    contactosCount = 0,
                    enCarteraMaestra = empresa.enCarteraMaestra,
                )
            }
        val meta = Paginacion.meta(pageRequest.pageNumber + 1, pageRequest.pageSize, resultado.totalElements)
        return Paginado(items, meta)
    }

    @Transactional(readOnly = true)
    override fun detalle(
        id: Long,
        usuario: UsuarioActual,
    ): EmpresaDetalleDto = visible(id, usuario).toDetalle()

    /** Siempre 200; no expone el vendedor dueño (contrato_api.md §8, B2.2). */
    @Transactional(readOnly = true)
    override fun checkRuc(ruc: String): RucCheckDto =
        if (empresaRepository.existsByRuc(ruc)) {
            RucCheckDto(existe = true, mensaje = "Esta empresa ya está registrada en el sistema")
        } else {
            RucCheckDto(existe = false)
        }

    /** Empresa + segmentos en una sola transaccion; RUC validado antes (B2.1/B2.2). */
    @Transactional
    override fun crear(
        request: CrearEmpresaRequest,
        usuario: UsuarioActual,
    ): EmpresaDetalleDto {
        if (empresaRepository.existsByRuc(request.ruc)) {
            throw RucDuplicadoException()
        }
        val ahora = LocalDateTime.now()
        val empresa =
            Empresa(
                ruc = request.ruc,
                razonSocial = request.razonSocial,
                actividadEcon = request.actividadEcon,
                ciiu = request.ciiu,
                sectorIndustrial = request.sectorIndustrial,
                idVendedor = request.idVendedor ?: usuario.id.takeIf { usuario.visibilidadRestringida },
                fileDrive = request.fileDrive,
                sitioWeb = request.sitioWeb,
                notas = request.notas,
                estadoSunat = request.estadoSunat,
                condicionSunat = request.condicionSunat,
                direccionFiscal = request.direccionFiscal,
                ubicacionReal = request.ubicacionReal,
                distrito = request.distrito,
                provincia = request.provincia,
                departamento = request.departamento,
                avalFiador = request.avalFiador,
                origenLead = request.origenLead,
                estadoCartera = EstadoCartera.no_contactado,
                segmentos = request.segmentos.orEmpty().toMutableSet(),
                createdAt = ahora,
                createdBy = usuario.id,
                updatedAt = ahora,
                updatedBy = usuario.id,
            )
        return empresaRepository.save(empresa).toDetalle()
    }

    @Transactional
    override fun actualizar(
        id: Long,
        request: ActualizarEmpresaRequest,
        usuario: UsuarioActual,
    ): EmpresaDetalleDto {
        val empresa = visible(id, usuario)
        request.ruc?.let {
            if (it != empresa.ruc && empresaRepository.existsByRuc(it)) {
                throw RucDuplicadoException()
            }
            empresa.ruc = it
        }
        request.razonSocial?.let { empresa.razonSocial = it }
        request.actividadEcon?.let { empresa.actividadEcon = it }
        request.ciiu?.let { empresa.ciiu = it }
        request.sectorIndustrial?.let { empresa.sectorIndustrial = it }
        request.estadoSunat?.let { empresa.estadoSunat = it }
        request.condicionSunat?.let { empresa.condicionSunat = it }
        request.direccionFiscal?.let { empresa.direccionFiscal = it }
        request.ubicacionReal?.let { empresa.ubicacionReal = it }
        request.distrito?.let { empresa.distrito = it }
        request.provincia?.let { empresa.provincia = it }
        request.departamento?.let { empresa.departamento = it }
        request.avalFiador?.let { empresa.avalFiador = it }
        request.origenLead?.let { empresa.origenLead = it }
        request.fileDrive?.let { empresa.fileDrive = it }
        request.sitioWeb?.let { empresa.sitioWeb = it }
        request.notas?.let { empresa.notas = it }
        // Si `segmentos` viene, reemplaza completamente los actuales (atomico).
        request.segmentos?.let {
            empresa.segmentos.clear()
            empresa.segmentos.addAll(it)
        }
        empresa.updatedAt = LocalDateTime.now()
        empresa.updatedBy = usuario.id
        return empresaRepository.save(empresa).toDetalle()
    }

    /**
     * Solo estados manuales, y solo si el estado actual es manual: el derivado
     * tiene prioridad mientras exista la oportunidad que lo justifica (§3.1).
     */
    @Transactional
    override fun cambiarEstadoCarteraManual(
        id: Long,
        estadoCartera: String,
        usuario: UsuarioActual,
    ): String {
        val empresa = visible(id, usuario)
        val nuevo =
            runCatching { EstadoCartera.valueOf(estadoCartera) }.getOrNull()
                ?: throw EstadoInvalidoException("Estado de cartera desconocido: $estadoCartera")
        if (!nuevo.esManual) {
            throw EstadoInvalidoException("Los estados derivados los establece el sistema, no el usuario")
        }
        if (!empresa.estadoCartera.esManual) {
            throw EstadoInvalidoException(
                "La empresa tiene un estado derivado (${empresa.estadoCartera.name}); no se puede cambiar manualmente",
            )
        }
        empresa.estadoCartera = nuevo
        empresa.updatedAt = LocalDateTime.now()
        empresa.updatedBy = usuario.id
        empresaRepository.save(empresa)
        return nuevo.name
    }

    @Transactional
    override fun reasignarVendedor(
        id: Long,
        idVendedor: Long,
        usuario: UsuarioActual,
    ): Long {
        if (!usuario.puedeReasignarDirecto) {
            throw PermisoInsuficienteException("La reasignación directa es exclusiva de gerencia; envía una solicitud")
        }
        val empresa = entidad(id)
        if (!empleadoService.esAsignableComoVendedor(idVendedor)) {
            throw ValidacionException("El destino debe ser un vendedor o jdv activo", field = "id_vendedor")
        }
        empresa.idVendedor = idVendedor
        empresa.updatedAt = LocalDateTime.now()
        empresaRepository.save(empresa)
        eventPublisher.publishEvent(VendedorEmpresaReasignadoEvent(idEmpresa = id, idVendedorNuevo = idVendedor, idActor = usuario.id))
        val actor = empleadoService.resumenPorIds(listOf(usuario.id))[usuario.id]
        notificacionService.notificar(
            destinatarios = setOf(idVendedor),
            idActor = usuario.id,
            tipo = TipoNotificacion.empresa_asignada,
            mensaje = "${actor?.nombreCompleto()} te asignó la empresa ${empresa.razonSocial}",
            entidadTipo = EntidadNotificacion.empresa,
            entidadId = id,
        )
        return idVendedor
    }

    @Transactional(readOnly = true)
    override fun vinculoVisible(
        id: Long,
        usuario: UsuarioActual,
    ): EmpresaVinculo = visible(id, usuario).toVinculo()

    @Transactional
    @Suppress("ReturnCount") // Guard clauses de salida temprana; dividir la funcion no mejora la legibilidad.
    override fun aplicarEstadoDerivado(
        idEmpresa: Long,
        derivado: EstadoCartera?,
    ): CambioEstadoCartera? {
        val empresa = entidad(idEmpresa)
        val actual = empresa.estadoCartera
        // Guarda de entrada (reglas §3.2 paso 3): sin cambio real no hay write.
        if (derivado == actual) {
            return null
        }
        if (derivado == null && actual.esManual) {
            return null
        }
        // Sin derivado y con estado actual derivado: la empresa vuelve al estado
        // manual base. Sin historial de estados manuales, se baja a prospeccion
        // (la empresa fue trabajada: tuvo oportunidades).
        val nuevo = derivado ?: EstadoCartera.prospeccion
        empresa.estadoCartera = nuevo
        empresa.updatedAt = LocalDateTime.now()
        empresaRepository.save(empresa)
        return CambioEstadoCartera(anterior = actual, nuevo = nuevo)
    }

    @Transactional(readOnly = true)
    override fun resumenPorIds(ids: Collection<Long>): Map<Long, EmpresaResumen> =
        empresaRepository.findAllById(ids.toSet()).associate { requireNotNull(it.id) to it.toResumen() }

    @Transactional(readOnly = true)
    override fun segmentosPorIds(ids: Collection<Long>): Map<Long, List<String>> =
        empresaRepository.findAllById(ids.toSet()).associate { requireNotNull(it.id) to it.segmentos.map { s -> s.name }.sorted() }

    @Transactional(readOnly = true)
    override fun vendedorAsignado(id: Long): Long? = empresaRepository.findById(id).map { it.idVendedor }.orElse(null)

    @Transactional
    override fun cambiarCarteraMaestra(
        id: Long,
        enCarteraMaestra: Boolean,
        idVendedor: Long?,
        usuario: UsuarioActual,
    ): CarteraMaestraDto {
        if (!usuario.puedeVerCarteraMaestra) {
            throw PermisoInsuficienteException("La cartera maestra es exclusiva de gerencia")
        }
        val empresa = entidad(id)
        if (enCarteraMaestra) {
            // El estado derivado delata oportunidades activas sin acoplar este
            // modulo al de oportunidades (seria una dependencia circular).
            if (empresa.estadoCartera == EstadoCartera.oportunidad_activa) {
                throw ConflictoException(
                    "CARTERA_MAESTRA_CON_OPORTUNIDADES",
                    "No se puede reservar una empresa con oportunidades activas",
                )
            }
            empresa.enCarteraMaestra = true
            empresa.idVendedor = null
        } else {
            val destino =
                idVendedor ?: throw ValidacionException("id_vendedor es obligatorio al liberar", field = "id_vendedor")
            if (!empleadoService.esAsignableComoVendedor(destino)) {
                throw ValidacionException("El destino debe ser un vendedor o jdv activo", field = "id_vendedor")
            }
            empresa.enCarteraMaestra = false
            empresa.idVendedor = destino
        }
        empresa.updatedAt = LocalDateTime.now()
        empresa.updatedBy = usuario.id
        empresaRepository.save(empresa)
        if (!enCarteraMaestra) {
            val actor = empleadoService.resumenPorIds(listOf(usuario.id))[usuario.id]
            notificacionService.notificar(
                destinatarios = setOf(requireNotNull(empresa.idVendedor)),
                idActor = usuario.id,
                tipo = TipoNotificacion.empresa_asignada,
                mensaje = "${actor?.nombreCompleto()} te asignó la empresa ${empresa.razonSocial} desde la cartera maestra",
                entidadTipo = EntidadNotificacion.empresa,
                entidadId = id,
            )
        }
        return CarteraMaestraDto(enCarteraMaestra = empresa.enCarteraMaestra, idVendedor = empresa.idVendedor)
    }

    @Transactional
    override fun eliminar(id: Long) {
        val empresa = entidad(id)
        empresaRepository.delete(empresa)
    }

    // ── privados ───────────────────────────────────────────────

    private fun entidad(id: Long): Empresa = empresaRepository.findById(id).orElseThrow { NoEncontradoException("La empresa no existe") }

    /** IDOR: una empresa ajena para vendedor/analista responde 404, no 403. */
    private fun visible(
        id: Long,
        usuario: UsuarioActual,
    ): Empresa {
        val empresa = entidad(id)
        if (empresa.enCarteraMaestra && !usuario.puedeVerCarteraMaestra) {
            throw NoEncontradoException("La empresa no existe")
        }
        if (usuario.visibilidadRestringida && empresa.idVendedor != usuario.id) {
            throw NoEncontradoException("La empresa no existe")
        }
        return empresa
    }

    private fun especificacion(
        filtros: EmpresaFiltros,
        usuario: UsuarioActual,
    ): Specification<Empresa> =
        Specification { root, query, cb ->
            val predicados = mutableListOf<Predicate>()
            if (!usuario.puedeVerCarteraMaestra) {
                predicados += cb.isFalse(root.get("enCarteraMaestra"))
            } else {
                filtros.carteraMaestra?.let {
                    predicados += cb.equal(root.get<Boolean>("enCarteraMaestra"), it)
                }
            }
            if (usuario.visibilidadRestringida) {
                predicados += cb.equal(root.get<Long>("idVendedor"), usuario.id)
            } else if (filtros.idVendedor != null) {
                predicados += cb.equal(root.get<Long>("idVendedor"), filtros.idVendedor)
            }
            filtros.q?.takeIf { it.isNotBlank() }?.let { q ->
                val patron = "%${q.lowercase()}%"
                predicados +=
                    cb.or(
                        cb.like(cb.lower(root.get("razonSocial")), patron),
                        cb.like(root.get("ruc"), "${q.trim()}%"),
                    )
            }
            filtros.estadoCartera?.let { estado ->
                runCatching { EstadoCartera.valueOf(estado) }.getOrNull()?.let {
                    predicados += cb.equal(root.get<EstadoCartera>("estadoCartera"), it)
                }
            }
            filtros.distrito?.takeIf { it.isNotBlank() }?.let {
                predicados += cb.equal(cb.lower(root.get("distrito")), it.lowercase())
            }
            filtros.segmento?.let { segmento ->
                val join = root.joinSet<Empresa, Segmento>("segmentos")
                predicados += cb.equal(join, segmento)
                query?.distinct(true)
            }
            cb.and(*predicados.toTypedArray())
        }

    private fun Empresa.toDetalle(): EmpresaDetalleDto {
        val vendedor = idVendedor?.let { empleadoService.resumenPorIds(listOf(it))[it] }
        return EmpresaDetalleDto(
            id = requireNotNull(id),
            ruc = ruc,
            razonSocial = razonSocial,
            actividadEcon = actividadEcon,
            ciiu = ciiu,
            sectorIndustrial = sectorIndustrial,
            estadoSunat = estadoSunat,
            condicionSunat = condicionSunat,
            direccionFiscal = direccionFiscal,
            ubicacionReal = ubicacionReal,
            distrito = distrito,
            provincia = provincia,
            departamento = departamento,
            avalFiador = avalFiador,
            origenLead = origenLead?.name,
            estadoCartera = estadoCartera.name,
            fileDrive = fileDrive,
            sitioWeb = sitioWeb,
            notas = notas,
            idVendedor = idVendedor,
            vendedor = vendedor,
            segmentos = segmentos.map { it.name }.sorted(),
            contactos = emptyList(),
            enCarteraMaestra = enCarteraMaestra,
            createdAt = createdAt,
            createdBy = createdBy,
        )
    }
}
