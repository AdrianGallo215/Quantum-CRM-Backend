package pe.quantum.crm.domain.oportunidades

import jakarta.persistence.criteria.Predicate
import org.springframework.context.event.EventListener
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.nombreCompleto
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.VendedorEmpresaReasignadoEvent
import pe.quantum.crm.domain.empresas.dto.CambioEstadoCartera
import pe.quantum.crm.domain.financiadoras.FinanciadoraService
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.domain.oportunidades.dto.ActualizarOportunidadRequest
import pe.quantum.crm.domain.oportunidades.dto.CambiarEstadoRequest
import pe.quantum.crm.domain.oportunidades.dto.CambioEstadoDto
import pe.quantum.crm.domain.oportunidades.dto.ContactoEnOportunidadDto
import pe.quantum.crm.domain.oportunidades.dto.ContactoVinculoRequest
import pe.quantum.crm.domain.oportunidades.dto.CrearOportunidadRequest
import pe.quantum.crm.domain.oportunidades.dto.LogEstadoDto
import pe.quantum.crm.domain.oportunidades.dto.ModeloEnOportunidadDto
import pe.quantum.crm.domain.oportunidades.dto.OportunidadDto
import pe.quantum.crm.domain.oportunidades.dto.OportunidadFiltros
import pe.quantum.crm.domain.oportunidades.dto.OportunidadRecordatorioDatos
import pe.quantum.crm.domain.oportunidades.dto.OportunidadResumenParaContacto
import pe.quantum.crm.domain.oportunidades.dto.OportunidadVinculo
import pe.quantum.crm.integracion.drive.DriveArchivoSubido
import pe.quantum.crm.integracion.drive.DriveStorageService
import pe.quantum.crm.shared.CamposOrdenables
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.PoliticaDescuento
import pe.quantum.crm.shared.enums.EstadoCartera
import pe.quantum.crm.shared.enums.EstadoOportunidad
import pe.quantum.crm.shared.exception.AprobacionRequeridaException
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.EstadoInvalidoException
import pe.quantum.crm.shared.exception.MontoNoEditableException
import pe.quantum.crm.shared.exception.MotivoCierreRequeridoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal
import java.time.LocalDateTime

@Service
@Suppress("TooManyFunctions", "LongParameterList") // Nucleo del pipeline (B3.x).
class OportunidadServiceImpl(
    private val oportunidadRepository: OportunidadRepository,
    private val logRepository: OportunidadEstadoLogRepository,
    private val contactoOportunidadRepository: OportunidadContactoRepository,
    private val estadoCarteraService: EstadoCarteraService,
    private val empresaService: EmpresaService,
    private val empleadoService: EmpleadoService,
    private val financiadoraService: FinanciadoraService,
    private val modeloService: ModeloService,
    private val contactoService: ContactoService,
    private val consultas: OportunidadConsultas,
    private val notificacionService: NotificacionService,
    private val driveStorageService: DriveStorageService,
) : OportunidadService {
    /**
     * Creacion transaccional completa (reglas §4.2): snapshot de vendedor,
     * financiadora default, precio del modelo, monto calculado, primer log con
     * `estado_anterior = NULL` y `actualizarEstadoCartera` — todo atomico.
     */
    @Transactional
    @Suppress("LongMethod") // Los 8 pasos de reglas §4.2 son una sola unidad transaccional; partirla ocultaria la atomicidad.
    override fun crear(
        request: CrearOportunidadRequest,
        usuario: UsuarioActual,
    ): OportunidadDto {
        val empresa = empresaService.vinculoVisible(request.idEmpresa, usuario)
        validarLimiteDescuento(request.dcto, usuario)
        // Snapshot del vendedor de la empresa (reglas §8.4). Una empresa sin vendedor
        // solo la ven roles supervisores: quien crea DEBE asignar un vendedor real
        // (gerencia/admin no pueden tener oportunidades propias). Se resuelve ANTES
        // de tocar modelo/financiadora para fallar rapido sin efectos secundarios.
        val idVendedorSnapshot =
            empresa.idVendedor ?: run {
                if (usuario.visibilidadRestringida) {
                    // Inalcanzable en la practica (la empresa seria invisible), pero
                    // el guard mantiene la invariante si la visibilidad cambia.
                    usuario.id
                } else {
                    val destino =
                        request.idVendedor
                            ?: throw ValidacionException(
                                "La empresa no tiene vendedor asignado; id_vendedor es obligatorio",
                                field = "id_vendedor",
                            )
                    if (!empleadoService.esAsignableComoVendedor(destino)) {
                        throw ValidacionException("El destino debe ser un vendedor o jdv activo", field = "id_vendedor")
                    }
                    // Misma transaccion: la empresa queda asignada y notificada.
                    empresaService.reasignarVendedor(empresa.id, destino, usuario)
                    destino
                }
            }
        val modelo = modeloService.resumen(request.idModelo)
        val financiadora =
            request.idFinanciadora?.let { financiadoraService.porId(it) }
                ?: financiadoraService.default()
        val precioUnitario = modelo.precioBase
        val ahora = LocalDateTime.now()
        val oportunidad =
            oportunidadRepository.save(
                Oportunidad(
                    idEmpresa = empresa.id,
                    idVendedor = idVendedorSnapshot,
                    idFinanciadora = financiadora.id,
                    idModelo = modelo.id,
                    estado = EstadoOportunidad.evaluacion_calidda,
                    cantidad = request.cantidad,
                    precioUnitario = precioUnitario,
                    dcto = request.dcto,
                    montoTotal = MontoTotal.calcular(request.cantidad, precioUnitario, request.dcto),
                    fincParalelo = request.fincParalelo,
                    garantia = request.garantia,
                    fichaVenta = request.fichaVenta,
                    notas = request.notas,
                    fechaCierreEstimado = request.fechaCierreEstimado,
                    createdAt = ahora,
                    createdBy = usuario.id,
                    updatedAt = ahora,
                    updatedBy = usuario.id,
                ),
            )
        val idOportunidad = requireNotNull(oportunidad.id)
        // Primer registro del log: estado_anterior = NULL (reglas §4.2 paso 6).
        logRepository.save(
            OportunidadEstadoLog(
                idOportunidad = idOportunidad,
                estadoAnterior = null,
                estadoNuevo = EstadoOportunidad.evaluacion_calidda,
                changedAt = ahora,
                changedBy = usuario.id,
            ),
        )
        request.contactos.orEmpty().forEach { vinculo ->
            if (!contactoService.existe(vinculo.idContacto)) {
                throw NoEncontradoException("El contacto ${vinculo.idContacto} no existe")
            }
            contactoOportunidadRepository.save(
                OportunidadContacto(
                    id = OportunidadContactoId(idOportunidad = idOportunidad, idContacto = vinculo.idContacto),
                    rolEnOportunidad = vinculo.rolEnOportunidad,
                ),
            )
        }
        // Subcarpeta bajo la carpeta de la empresa, ya con el id definitivo.
        // Dentro de la transaccion: si Drive falla, la oportunidad no se crea.
        oportunidad.driveFolderId =
            driveStorageService.crearCarpeta(
                nombre = nombreCarpetaDrive(idOportunidad, modelo.codigo),
                parentFolderId = empresa.driveFolderId ?: empresaService.asegurarCarpetaDrive(empresa.id),
            )
        // Misma transaccion (reglas §3.3): la empresa sube a oportunidad_activa.
        val cambioCartera = estadoCarteraService.actualizar(empresa.id)
        notificarConversionSiAplica(cambioCartera, empresa.id, idOportunidad, usuario)
        return toDto(oportunidad, detalle = true)
    }

    /** Edicion de campos negociables (B3.4). `monto_total` en body → 400. */
    @Transactional
    @Suppress("CyclomaticComplexMethod") // Un `?.let` por campo negociable del PUT; la complejidad es la del DTO, no logica ramificada.
    override fun actualizar(
        id: Long,
        request: ActualizarOportunidadRequest,
        usuario: UsuarioActual,
    ): OportunidadDto {
        if (request.montoTotal != null) {
            throw MontoNoEditableException()
        }
        validarLimiteDescuento(request.dcto, usuario)
        val oportunidad = visible(id, usuario)
        val advertencias = mutableListOf<String>()

        // Cambio de modelo (reglas §12.2): sobreescribe el precio solo si NO fue
        // editado manualmente (== precio_base del modelo anterior).
        val nuevoModeloId = request.idModelo
        if (nuevoModeloId != null && nuevoModeloId != oportunidad.idModelo) {
            val modeloNuevo = modeloService.resumen(nuevoModeloId)
            val precioBaseAnterior = oportunidad.idModelo?.let { modeloService.resumen(it).precioBase }
            val precioNoEditado =
                oportunidad.precioUnitario == null ||
                    (precioBaseAnterior != null && oportunidad.precioUnitario?.compareTo(precioBaseAnterior) == 0)
            if (precioNoEditado) {
                oportunidad.precioUnitario = modeloNuevo.precioBase
            } else {
                advertencias += "El precio unitario fue editado manualmente y no se actualizó con el nuevo modelo"
            }
            oportunidad.idModelo = nuevoModeloId
        }

        request.cantidad?.let { oportunidad.cantidad = it }
        request.precioUnitario?.let { oportunidad.precioUnitario = it }
        request.dcto?.let { oportunidad.dcto = it }
        request.garantia?.let { oportunidad.garantia = it }
        request.fincParalelo?.let { oportunidad.fincParalelo = it }
        request.fichaVenta?.let { oportunidad.fichaVenta = it }
        request.notas?.let { oportunidad.notas = it }
        request.fechaCierreEstimado?.let { oportunidad.fechaCierreEstimado = it }

        // Recalculo SIEMPRE en backend (reglas §7.2).
        oportunidad.montoTotal =
            MontoTotal.calcular(oportunidad.cantidad, oportunidad.precioUnitario, oportunidad.dcto)
        oportunidad.updatedAt = LocalDateTime.now()
        oportunidad.updatedBy = usuario.id
        oportunidadRepository.save(oportunidad)
        return toDto(oportunidad, detalle = true).copy(advertencias = advertencias)
    }

    /**
     * Cambio de estado (B3.5, reglas §4.3): validaciones, log, retroceso,
     * advertencias de eventos recomendados y `actualizarEstadoCartera` — atomico.
     */
    @Transactional
    override fun cambiarEstado(
        id: Long,
        request: CambiarEstadoRequest,
        usuario: UsuarioActual,
    ): CambioEstadoDto {
        val oportunidad = visible(id, usuario)
        val nuevo =
            runCatching { EstadoOportunidad.valueOf(request.estado) }.getOrNull()
                ?: throw EstadoInvalidoException("Estado desconocido: ${request.estado}")
        val anterior = oportunidad.estado

        if (nuevo == anterior) {
            throw EstadoInvalidoException("La oportunidad ya está en el estado ${nuevo.name}")
        }
        // Paso a facturado: solo admin, gerencia, analista (verificado en servicio).
        if (nuevo == EstadoOportunidad.facturado && !usuario.puedeValidarFacturado) {
            throw PermisoInsuficienteException("Solo admin, gerencia o analista pueden validar el paso a facturado")
        }
        // motivo_cierre obligatorio al cerrar (reglas §4.4).
        if (nuevo == EstadoOportunidad.cerrado) {
            if (request.motivoCierre.isNullOrBlank()) {
                throw MotivoCierreRequeridoException()
            }
            oportunidad.motivoCierre = request.motivoCierre
        } else {
            // Para cualquier otro estado, motivo_cierre debe ser NULL; al
            // retroceder desde cerrado se limpia automaticamente (reglas §13.4).
            oportunidad.motivoCierre = null
        }

        val esRetroceso = esRetroceso(anterior, nuevo)
        // Advertencias: eventos recomendados de la etapa actual sin registrar (§5.4).
        val advertencias =
            consultas
                .eventosRecomendadosSinRegistrar(id, anterior)
                .map { "$it no fue registrado" }

        oportunidad.estado = nuevo
        oportunidad.facturadoEn = if (nuevo == EstadoOportunidad.facturado) LocalDateTime.now() else null
        oportunidad.updatedAt = LocalDateTime.now()
        oportunidad.updatedBy = usuario.id
        oportunidadRepository.save(oportunidad)
        logRepository.save(
            OportunidadEstadoLog(
                idOportunidad = id,
                estadoAnterior = anterior,
                estadoNuevo = nuevo,
                changedBy = usuario.id,
            ),
        )
        // Misma transaccion (reglas §3.3, §13.3).
        val cambioCartera = estadoCarteraService.actualizar(oportunidad.idEmpresa)
        notificarConversionSiAplica(cambioCartera, oportunidad.idEmpresa, id, usuario)
        notificarCambioEstado(oportunidad.idEmpresa, id, nuevo, usuario)
        return CambioEstadoDto(estado = nuevo.name, esRetroceso = esRetroceso, advertencias = advertencias)
    }

    /**
     * Cascada de vendedor (reglas §8): al reasignar `empresas.id_vendedor`, todas
     * las oportunidades activas de esa empresa heredan el mismo vendedor. El
     * listener corre sincrono, dentro de la misma transaccion que publico el
     * evento (`EmpresaServiceImpl.reasignarVendedor`) — si falla, tambien se
     * revierte la reasignacion de la empresa (atomicidad, reglas §1.2).
     */
    @EventListener
    @Transactional
    fun onVendedorEmpresaReasignado(event: VendedorEmpresaReasignadoEvent) {
        val activas =
            oportunidadRepository
                .findByIdEmpresaAndEstadoIn(event.idEmpresa, EstadoCarteraService.ESTADOS_ACTIVOS)
                .filter { it.idVendedor != event.idVendedorNuevo }
        if (activas.isEmpty()) {
            return
        }
        val ahora = LocalDateTime.now()
        activas.forEach {
            it.idVendedor = event.idVendedorNuevo
            it.updatedAt = ahora
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

    @Transactional(readOnly = true)
    override fun log(
        id: Long,
        usuario: UsuarioActual,
    ): List<LogEstadoDto> {
        visible(id, usuario)
        val entradas = logRepository.findByIdOportunidadOrderByChangedAtAscIdAsc(id)
        val empleados = empleadoService.resumenPorIds(entradas.map { it.changedBy })
        return entradas.map {
            LogEstadoDto(
                estadoAnterior = it.estadoAnterior?.name,
                estadoNuevo = it.estadoNuevo.name,
                changedAt = it.changedAt,
                changedBy = empleados[it.changedBy],
            )
        }
    }

    @Transactional(readOnly = true)
    override fun listar(
        filtros: OportunidadFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<OportunidadDto> {
        val pageRequest = Paginacion.pageRequest(page, perPage, sort, dir, CAMPOS_ORDENABLES)
        val resultado = oportunidadRepository.findAll(especificacion(filtros, usuario), pageRequest)
        val items = toDtos(resultado.content)
        val meta = Paginacion.meta(pageRequest.pageNumber + 1, pageRequest.pageSize, resultado.totalElements)
        return Paginado(items, meta)
    }

    @Transactional(readOnly = true)
    override fun detalle(
        id: Long,
        usuario: UsuarioActual,
    ): OportunidadDto = toDto(visible(id, usuario), detalle = true)

    @Transactional
    override fun vincularContacto(
        id: Long,
        request: ContactoVinculoRequest,
        usuario: UsuarioActual,
    ): ContactoVinculoRequest {
        visible(id, usuario)
        if (!contactoService.existe(request.idContacto)) {
            throw NoEncontradoException("El contacto no existe")
        }
        contactoOportunidadRepository.save(
            OportunidadContacto(
                id = OportunidadContactoId(idOportunidad = id, idContacto = request.idContacto),
                rolEnOportunidad = request.rolEnOportunidad,
            ),
        )
        return request
    }

    @Transactional
    override fun actualizarContacto(
        id: Long,
        idContacto: Long,
        rolEnOportunidad: String?,
        usuario: UsuarioActual,
    ): ContactoVinculoRequest {
        visible(id, usuario)
        val vinculo =
            contactoOportunidadRepository
                .findById(OportunidadContactoId(idOportunidad = id, idContacto = idContacto))
                .orElseThrow { NoEncontradoException("El contacto no está vinculado a esta oportunidad") }
        vinculo.rolEnOportunidad = rolEnOportunidad
        contactoOportunidadRepository.save(vinculo)
        return ContactoVinculoRequest(idContacto = idContacto, rolEnOportunidad = rolEnOportunidad)
    }

    @Transactional
    override fun desvincularContacto(
        id: Long,
        idContacto: Long,
        usuario: UsuarioActual,
    ) {
        visible(id, usuario)
        val vinculo =
            contactoOportunidadRepository
                .findById(OportunidadContactoId(idOportunidad = id, idContacto = idContacto))
                .orElseThrow { NoEncontradoException("El contacto no está vinculado a esta oportunidad") }
        contactoOportunidadRepository.delete(vinculo)
    }

    @Transactional(readOnly = true)
    override fun tieneOportunidadesActivas(idEmpresa: Long): Boolean =
        oportunidadRepository.existsByIdEmpresaAndEstadoIn(idEmpresa, EstadoCarteraService.ESTADOS_ACTIVOS)

    @Transactional(readOnly = true)
    override fun countPorContacto(idContacto: Long): Int = contactoOportunidadRepository.countByIdIdContacto(idContacto).toInt()

    @Transactional(readOnly = true)
    override fun oportunidadesPorContacto(idContacto: Long): List<OportunidadResumenParaContacto> {
        val vinculos = contactoOportunidadRepository.findByIdIdContacto(idContacto)
        if (vinculos.isEmpty()) {
            return emptyList()
        }
        val idsOportunidad = vinculos.map { it.id.idOportunidad }
        val oportunidades = oportunidadRepository.findAllById(idsOportunidad).associateBy { requireNotNull(it.id) }
        val empresas = empresaService.resumenPorIds(oportunidades.values.map { it.idEmpresa })
        val modelos = modeloService.resumenPorIds(oportunidades.values.mapNotNull { it.idModelo })
        return vinculos.mapNotNull { vinculo ->
            oportunidades[vinculo.id.idOportunidad]?.let { op ->
                OportunidadResumenParaContacto(
                    id = requireNotNull(op.id),
                    empresa = empresas[op.idEmpresa],
                    modelo =
                        op.idModelo?.let { modelos[it] }?.let {
                            ModeloEnOportunidadDto(id = it.id, codigo = it.codigo, precioBase = it.precioBase?.toPlainString())
                        },
                    estado = op.estado.name,
                    montoTotal = op.montoTotal?.toPlainString(),
                    fechaCierreEstimado = op.fechaCierreEstimado,
                    rolEnOportunidad = vinculo.rolEnOportunidad,
                )
            }
        }
    }

    @Transactional(readOnly = true)
    override fun vinculoVisible(
        id: Long,
        usuario: UsuarioActual,
    ): OportunidadVinculo {
        val oportunidad = visible(id, usuario)
        return OportunidadVinculo(
            id = requireNotNull(oportunidad.id),
            idEmpresa = oportunidad.idEmpresa,
            idVendedor = oportunidad.idVendedor,
            estado = oportunidad.estado.name,
        )
    }

    @Transactional
    override fun aplicarDescuentoAprobado(
        id: Long,
        dcto: BigDecimal,
        idAprobador: Long,
    ) {
        val oportunidad =
            oportunidadRepository.findById(id).orElseThrow {
                ConflictoException("SOLICITUD_NO_APLICABLE", "La oportunidad de la solicitud ya no existe")
            }
        if (oportunidad.estado == EstadoOportunidad.cerrado || oportunidad.estado == EstadoOportunidad.facturado) {
            throw ConflictoException(
                "SOLICITUD_NO_APLICABLE",
                "La oportunidad está en ${oportunidad.estado.name}; el descuento ya no aplica",
            )
        }
        oportunidad.dcto = dcto
        oportunidad.montoTotal = MontoTotal.calcular(oportunidad.cantidad, oportunidad.precioUnitario, dcto)
        oportunidad.updatedAt = LocalDateTime.now()
        oportunidad.updatedBy = idAprobador
        oportunidadRepository.save(oportunidad)
    }

    @Transactional(readOnly = true)
    override fun datosRecordatorio(id: Long): OportunidadRecordatorioDatos? =
        oportunidadRepository
            .findById(id)
            .map { OportunidadRecordatorioDatos(idEmpresa = it.idEmpresa, idVendedor = it.idVendedor) }
            .orElse(null)

    @Transactional
    override fun eliminar(id: Long) {
        val oportunidad = entidad(id)
        val idEmpresa = oportunidad.idEmpresa
        oportunidadRepository.delete(oportunidad)
        estadoCarteraService.actualizar(idEmpresa)
    }

    // ── privados ───────────────────────────────────────────────

    /**
     * Retroceso (reglas §13.1): volver de `cerrado` o `facturado` a un estado
     * activo, o retroceder en la secuencia positiva del pipeline.
     */
    private fun esRetroceso(
        anterior: EstadoOportunidad,
        nuevo: EstadoOportunidad,
    ): Boolean =
        when {
            anterior == EstadoOportunidad.cerrado -> true
            nuevo == EstadoOportunidad.cerrado -> false
            else -> nuevo.rango < anterior.rango
        }

    private fun notificarCambioEstado(
        idEmpresa: Long,
        idOportunidad: Long,
        nuevo: EstadoOportunidad,
        usuario: UsuarioActual,
    ) {
        val empresa = empresaService.resumenPorIds(listOf(idEmpresa))[idEmpresa] ?: return
        val actor = empleadoService.resumenPorIds(listOf(usuario.id))[usuario.id] ?: return
        notificacionService.notificar(
            destinatarios = empleadoService.idsSupervisoresActivos().toSet(),
            idActor = usuario.id,
            tipo = TipoNotificacion.oportunidad_cambio_estado,
            mensaje = "${actor.nombreCompleto()} cambió el estado de ${empresa.razonSocial} a ${etiquetaEstado(nuevo)}",
            entidadTipo = EntidadNotificacion.oportunidad,
            entidadId = idOportunidad,
        )
    }

    /**
     * `empresa_convertida`: solo cuando `estadoCarteraService.actualizar` reporta
     * la transicion prospeccion -> oportunidad_activa. Se llama tanto desde
     * `crear` (Task 11) como desde `cambiarEstado` (el retroceso de reglas §13.1
     * puede en teoria producir la misma transicion desde `cambiarEstado`).
     */
    @Suppress("ReturnCount") // Guard clauses de salida temprana; dividir la funcion no mejora la legibilidad.
    private fun notificarConversionSiAplica(
        cambio: CambioEstadoCartera?,
        idEmpresa: Long,
        idOportunidad: Long,
        usuario: UsuarioActual,
    ) {
        if (cambio?.anterior != EstadoCartera.prospeccion || cambio.nuevo != EstadoCartera.oportunidad_activa) {
            return
        }
        val empresa = empresaService.resumenPorIds(listOf(idEmpresa))[idEmpresa] ?: return
        val actor = empleadoService.resumenPorIds(listOf(usuario.id))[usuario.id] ?: return
        notificacionService.notificar(
            destinatarios = empleadoService.idsSupervisoresActivos().toSet(),
            idActor = usuario.id,
            tipo = TipoNotificacion.empresa_convertida,
            mensaje = "${actor.nombreCompleto()} convirtió ${empresa.razonSocial} de prospección a oportunidad",
            entidadTipo = EntidadNotificacion.oportunidad,
            entidadId = idOportunidad,
        )
    }

    private fun etiquetaEstado(estado: EstadoOportunidad): String =
        when (estado) {
            EstadoOportunidad.evaluacion_calidda -> "Evaluación Calidda"
            EstadoOportunidad.documentos_legales -> "Documentos legales"
            EstadoOportunidad.facturado -> "Facturado"
            EstadoOportunidad.cerrado -> "Cerrado"
        }

    /** Descuento sobre el limite del rol: 422, el cambio requiere solicitud (frontend §3.1). */
    private fun validarLimiteDescuento(
        dcto: BigDecimal?,
        usuario: UsuarioActual,
    ) {
        if (PoliticaDescuento.excedeLimite(usuario.rol, dcto)) {
            val limite = requireNotNull(PoliticaDescuento.limitePara(usuario.rol))
            throw AprobacionRequeridaException(
                "Un descuento de ${dcto!!.toPlainString()}% supera tu límite de ${limite.toPlainString()}%; requiere aprobación",
            )
        }
    }

    private fun entidad(id: Long): Oportunidad =
        oportunidadRepository.findById(id).orElseThrow { NoEncontradoException("La oportunidad no existe") }

    /** IDOR: oportunidad ajena para vendedor/analista → 404, no 403. */
    @Transactional
    override fun asegurarCarpetaDrive(
        id: Long,
        usuario: UsuarioActual,
    ): String {
        visible(id, usuario)
        return asegurarCarpetaDriveDe(id)
    }

    @Transactional
    override fun asegurarCarpetaDrive(id: Long): String {
        entidad(id)
        return asegurarCarpetaDriveDe(id)
    }

    /**
     * Bloqueo pesimista de fila (hallazgo Important de la revision final): evita
     * que dos requests concurrentes sobre la misma oportunidad creen dos carpetas.
     */
    private fun asegurarCarpetaDriveDe(id: Long): String {
        val oportunidad = oportunidadRepository.findByIdForUpdate(id) ?: throw NoEncontradoException("La oportunidad no existe")
        oportunidad.driveFolderId?.let { return it }
        val carpetaEmpresa = empresaService.asegurarCarpetaDrive(oportunidad.idEmpresa)
        val codigoModelo = oportunidad.idModelo?.let { modeloService.resumen(it).codigo }
        val carpeta =
            driveStorageService.crearCarpeta(
                nombre = nombreCarpetaDrive(id, codigoModelo),
                parentFolderId = carpetaEmpresa,
            )
        oportunidad.driveFolderId = carpeta
        oportunidadRepository.save(oportunidad)
        return carpeta
    }

    @Transactional(readOnly = true)
    override fun archivosDrive(
        id: Long,
        usuario: UsuarioActual,
    ): List<DriveArchivoSubido> = visible(id, usuario).driveFolderId?.let { driveStorageService.listarArchivos(it) } ?: emptyList()

    @Transactional(readOnly = true)
    override fun idsSinCarpetaDrive(): List<Long> = oportunidadRepository.findIdsSinCarpetaDrive()

    /**
     * `OP-{id} - {codigo modelo}`: identifica la oportunidad dentro de la carpeta de
     * la empresa sin ambiguedad, aunque haya varias del mismo modelo.
     */
    private fun nombreCarpetaDrive(
        idOportunidad: Long,
        codigoModelo: String?,
    ): String = listOfNotNull("OP-$idOportunidad", codigoModelo).joinToString(" - ")

    private fun visible(
        id: Long,
        usuario: UsuarioActual,
    ): Oportunidad {
        val oportunidad = entidad(id)
        if (usuario.visibilidadRestringida && oportunidad.idVendedor != usuario.id) {
            throw NoEncontradoException("La oportunidad no existe")
        }
        return oportunidad
    }

    private fun especificacion(
        filtros: OportunidadFiltros,
        usuario: UsuarioActual,
    ): Specification<Oportunidad> =
        Specification { root, _, cb ->
            val predicados = mutableListOf<Predicate>()
            if (usuario.visibilidadRestringida) {
                predicados += cb.equal(root.get<Long>("idVendedor"), usuario.id)
            } else if (filtros.idVendedor != null) {
                predicados += cb.equal(root.get<Long>("idVendedor"), filtros.idVendedor)
            }
            filtros.estado?.let { estado ->
                runCatching { EstadoOportunidad.valueOf(estado) }.getOrNull()?.let {
                    predicados += cb.equal(root.get<EstadoOportunidad>("estado"), it)
                }
            }
            if (filtros.estado == null && !filtros.incluirCerradas) {
                predicados += cb.notEqual(root.get<EstadoOportunidad>("estado"), EstadoOportunidad.cerrado)
            }
            filtros.idEmpresa?.let { predicados += cb.equal(root.get<Long>("idEmpresa"), it) }
            filtros.idFinanciadora?.let { predicados += cb.equal(root.get<Long>("idFinanciadora"), it) }
            cb.and(*predicados.toTypedArray())
        }

    private fun toDto(
        oportunidad: Oportunidad,
        detalle: Boolean,
    ): OportunidadDto {
        val dto = toDtos(listOf(oportunidad)).first()
        if (!detalle) {
            return dto
        }
        val id = requireNotNull(oportunidad.id)
        val vinculos = contactoOportunidadRepository.findByIdIdOportunidad(id)
        val contactos = contactoService.resumenPorIds(vinculos.map { it.id.idContacto })
        val entradaEtapa = logRepository.findFirstByIdOportunidadOrderByChangedAtDescIdDesc(id)?.changedAt
        return dto.copy(
            contactos =
                vinculos.mapNotNull { vinculo ->
                    contactos[vinculo.id.idContacto]?.let {
                        ContactoEnOportunidadDto(
                            id = it.id,
                            nombres = it.nombres,
                            apellidos = it.apellidos,
                            rolEnOportunidad = vinculo.rolEnOportunidad,
                        )
                    }
                },
            entradaEtapaActual = entradaEtapa,
        )
    }

    /** Ensambla DTOs por lotes: sin N+1 y sin duplicar datos de financiadora. */
    private fun toDtos(oportunidades: List<Oportunidad>): List<OportunidadDto> {
        if (oportunidades.isEmpty()) {
            return emptyList()
        }
        val ids = oportunidades.map { requireNotNull(it.id) }
        val empresas = empresaService.resumenPorIds(oportunidades.map { it.idEmpresa })
        val vendedores = empleadoService.resumenPorIds(oportunidades.map { it.idVendedor })
        // Terminos de la financiadora por JOIN logico, nunca copiados (reglas §9.4).
        val financiadoras = financiadoraService.porIds(oportunidades.map { it.idFinanciadora })
        val modelos = modeloService.resumenPorIds(oportunidades.mapNotNull { it.idModelo })
        val tareasPendientes = consultas.tareasPendientesPorOportunidad(ids)
        val eventosPendientes = consultas.eventosPendientesPorOportunidad(ids)
        return oportunidades.map { op ->
            val opId = requireNotNull(op.id)
            OportunidadDto(
                id = opId,
                idEmpresa = op.idEmpresa,
                empresa = empresas[op.idEmpresa],
                idVendedor = op.idVendedor,
                vendedor = vendedores[op.idVendedor],
                idFinanciadora = op.idFinanciadora,
                financiadora = financiadoras[op.idFinanciadora],
                idModelo = op.idModelo,
                modelo =
                    op.idModelo?.let { modelos[it] }?.let {
                        ModeloEnOportunidadDto(id = it.id, codigo = it.codigo, precioBase = it.precioBase?.toPlainString())
                    },
                estado = op.estado.name,
                cantidad = op.cantidad,
                precioUnitario = op.precioUnitario?.toPlainString(),
                dcto = op.dcto?.toPlainString(),
                montoTotal = op.montoTotal?.toPlainString(),
                garantia = op.garantia,
                fincParalelo = op.fincParalelo,
                fichaVenta = op.fichaVenta,
                driveFolderId = op.driveFolderId,
                notas = op.notas,
                motivoCierre = op.motivoCierre,
                fechaCierreEstimado = op.fechaCierreEstimado,
                tareasPendientesCount = tareasPendientes[opId] ?: 0,
                eventosPendientesCount = eventosPendientes[opId] ?: 0,
                createdAt = op.createdAt,
            )
        }
    }

    private companion object {
        /** Allowlist de `sort` de GET /oportunidades; el primero es el orden por defecto. */
        val CAMPOS_ORDENABLES =
            CamposOrdenables(
                "id",
                "estado",
                "cantidad",
                "precioUnitario",
                "montoTotal",
                "fechaCierreEstimado",
                "createdAt",
                "updatedAt",
            )
    }
}
