package pe.quantum.crm.domain.oportunidades

import jakarta.persistence.criteria.Predicate
import org.springframework.context.event.EventListener
import org.springframework.data.domain.PageRequest
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
import pe.quantum.crm.domain.oportunidades.dto.CrearOportunidadItemRequest
import pe.quantum.crm.domain.oportunidades.dto.CrearOportunidadRequest
import pe.quantum.crm.domain.oportunidades.dto.LogEstadoDto
import pe.quantum.crm.domain.oportunidades.dto.OportunidadDto
import pe.quantum.crm.domain.oportunidades.dto.OportunidadFiltros
import pe.quantum.crm.domain.oportunidades.dto.OportunidadRecordatorioDatos
import pe.quantum.crm.domain.oportunidades.dto.OportunidadVinculo
import pe.quantum.crm.integracion.drive.DriveArchivoSubido
import pe.quantum.crm.integracion.drive.DriveStorageService
import pe.quantum.crm.shared.CamposOrdenables
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.PoliticaDescuento
import pe.quantum.crm.shared.comoInstanteUtc
import pe.quantum.crm.shared.enums.EstadoCartera
import pe.quantum.crm.shared.enums.EstadoOportunidad
import pe.quantum.crm.shared.exception.AprobacionRequeridaException
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.EstadoInvalidoException
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
    private val visibilidad: OportunidadVisibilidad,
    private val oportunidadItemService: OportunidadItemService,
    // Solo para la rama de orden por agregado del listado (D29); todo lo demas
    // del servicio sigue pasando por JPA.
    private val listadoDao: OportunidadListadoDao,
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
        visibilidad.rechazarSiEsApoyo(usuario)
        val empresa = empresaService.vinculoVisible(request.idEmpresa, usuario)
        validarLimiteDescuento(request.descuento, usuario)
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
        // Valida que el modelo exista antes de crear nada (falla rapido con 404).
        // Ya no se guarda en la oportunidad: el modelo vive en el item.
        modeloService.resumen(request.idModelo)
        val financiadora =
            request.idFinanciadora?.let { financiadoraService.porId(it) }
                ?: financiadoraService.default()
        val ahora = LocalDateTime.now()
        // Los montos y el modelo no se guardan aqui: viven en `oportunidad_items`,
        // que se crea unos pasos mas abajo en esta misma transaccion.
        val oportunidad =
            oportunidadRepository.save(
                Oportunidad(
                    idEmpresa = empresa.id,
                    idVendedor = idVendedorSnapshot,
                    idFinanciadora = financiadora.id,
                    estado = EstadoOportunidad.evaluacion_calidda,
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
        // Primer item, ya con el id definitivo de la oportunidad (D17: nunca cero items).
        // Se delega en `OportunidadItemService` para no duplicar aqui el precio por defecto
        // ni el recalculo de montos; su `@Transactional` es REQUIRED, asi que se une a esta
        // misma transaccion y los 8 pasos siguen siendo atomicos.
        oportunidadItemService.crear(
            idOportunidad,
            CrearOportunidadItemRequest(
                idModelo = request.idModelo,
                cantidad = request.cantidad,
                // `precioVenta` null a proposito: el item lo rellena con el precio base del modelo.
                precioVenta = null,
                descuento = request.descuento,
            ),
            usuario,
        )
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
                nombre = nombreCarpetaDrive(idOportunidad),
                parentFolderId = empresa.driveFolderId ?: empresaService.asegurarCarpetaDrive(empresa.id),
            )
        // Misma transaccion (reglas §3.3): la empresa sube a oportunidad_activa.
        val cambioCartera = estadoCarteraService.actualizar(empresa.id)
        notificarConversionSiAplica(cambioCartera, empresa.id, idOportunidad, usuario)
        return toDto(oportunidad, detalle = true)
    }

    /** Edicion de campos negociables (B3.4). Los campos de item (modelo/cantidad/precio/dcto)
     * se editan via OportunidadItemService (B3); este metodo ya no los toca (B7). */
    @Transactional
    override fun actualizar(
        id: Long,
        request: ActualizarOportunidadRequest,
        usuario: UsuarioActual,
    ): OportunidadDto {
        visibilidad.rechazarSiEsApoyo(usuario)
        val oportunidad = visible(id, usuario)
        val advertencias = mutableListOf<String>()

        request.garantia?.let { oportunidad.garantia = it }
        request.fincParalelo?.let { oportunidad.fincParalelo = it }
        request.fichaVenta?.let { oportunidad.fichaVenta = it }
        request.notas?.let { oportunidad.notas = it }
        request.fechaCierreEstimado?.let { oportunidad.fechaCierreEstimado = it }

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
        visibilidad.rechazarSiEsApoyo(usuario)
        val oportunidad = visibleBloqueando(id, usuario)
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
                changedAt = it.changedAt.comoInstanteUtc(),
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
        // El PageRequest se construye igual para las dos ramas: normaliza page/per_page
        // y valida `sort` contra la allowlist en un solo sitio. De el sale tambien el
        // campo ya resuelto y la direccion, que es lo que decide la rama.
        val pageRequest = Paginacion.pageRequest(page, perPage, sort, dir, CAMPOS_ORDENABLES)
        val orden = pageRequest.sort.first()
        val estado = estadoFiltro(filtros.estado)
        if (orden.property in CAMPOS_AGREGADOS) {
            return listarOrdenandoPorAgregado(filtros, estado, usuario, pageRequest, orden.property, orden.isAscending)
        }
        val resultado = oportunidadRepository.findAll(especificacion(filtros, estado, usuario), pageRequest)
        val items = toDtos(resultado.content)
        val meta = Paginacion.meta(pageRequest.pageNumber + 1, pageRequest.pageSize, resultado.totalElements)
        return Paginado(items, meta)
    }

    /**
     * Rama de consulta nativa del listado (D29 de `plan-07-mapa-retirar-columnas.md`):
     * `cantidad` y `monto_total` ya no son columnas de `oportunidades` mantenidas al
     * dia por la sincronizacion de D21, son agregados de `oportunidad_items`, y su
     * orden lo resuelve [OportunidadListadoDao] en SQL nativo.
     *
     * Aqui solo se rehidratan por JPA los ids que devolvio, REORDENADOS segun ese
     * orden (`findAllById` no lo garantiza), y se pasan por el mismo `toDtos()` de
     * siempre: la construccion del DTO no se duplica.
     */
    private fun listarOrdenandoPorAgregado(
        filtros: OportunidadFiltros,
        estado: EstadoOportunidad?,
        usuario: UsuarioActual,
        pageRequest: PageRequest,
        campo: String,
        ascendente: Boolean,
    ): Paginado<OportunidadDto> {
        val pagina =
            listadoDao.paginaOrdenadaPorAgregado(
                filtros = filtros,
                estado = estado,
                usuario = usuario,
                campo = campo,
                ascendente = ascendente,
                limite = pageRequest.pageSize,
                desplazamiento = pageRequest.offset,
            )
        val meta = Paginacion.meta(pageRequest.pageNumber + 1, pageRequest.pageSize, pagina.total)
        val porId =
            if (pagina.ids.isEmpty()) {
                emptyMap()
            } else {
                oportunidadRepository.findAllById(pagina.ids).associateBy { requireNotNull(it.id) }
            }
        return Paginado(toDtos(pagina.ids.map { porId.getValue(it) }), meta)
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
        visibilidad.rechazarSiEsApoyo(usuario)
        visible(id, usuario)
        if (!contactoService.existe(request.idContacto)) {
            throw NoEncontradoException("El contacto no existe")
        }
        val clave = OportunidadContactoId(idOportunidad = id, idContacto = request.idContacto)
        // `save` sobre una clave existente es un UPDATE: vincular dos veces
        // sobreescribia el rol anterior en silencio y devolvia 201 como si fuera un
        // vinculo nuevo. Cambiar el rol es otra operacion, con su propio endpoint.
        if (contactoOportunidadRepository.existsById(clave)) {
            throw ConflictoException(
                "CONTACTO_YA_VINCULADO",
                "El contacto ya está vinculado a esta oportunidad; usa PUT para cambiar su rol",
            )
        }
        contactoOportunidadRepository.save(
            OportunidadContacto(id = clave, rolEnOportunidad = request.rolEnOportunidad),
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
        visibilidad.rechazarSiEsApoyo(usuario)
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
        visibilidad.rechazarSiEsApoyo(usuario)
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
    override fun asegurarCarpetaDrive(
        id: Long,
        usuario: UsuarioActual,
    ): String {
        visibilidad.rechazarSiEsApoyo(usuario)
        return asegurarCarpetaDriveDe(visible(id, usuario))
    }

    override fun asegurarCarpetaDrive(id: Long): String = asegurarCarpetaDriveDe(entidad(id))

    /**
     * SIN `@Transactional` en los metodos publicos a proposito. Esta era la ruta
     * peor: bloqueaba la fila de la oportunidad, entraba en `empresaService` (que
     * al ser REQUIRED se unia a la misma transaccion y bloqueaba tambien la fila de
     * la empresa) y encadenaba hasta DOS llamadas a Drive con ambas filas y la
     * conexion retenidas.
     *
     * La no duplicacion la garantiza ahora el UPDATE condicional, que es atomico:
     * `WHERE drive_folder_id IS NULL` solo casa una vez, asi que la oportunidad
     * nunca guarda dos carpetas distintas. Ver EmpresaServiceImpl para el detalle.
     */
    private fun asegurarCarpetaDriveDe(oportunidad: Oportunidad): String {
        oportunidad.driveFolderId?.let { return it }
        val id = requireNotNull(oportunidad.id)
        val carpetaEmpresa = empresaService.asegurarCarpetaDrive(oportunidad.idEmpresa)
        val carpeta =
            driveStorageService.crearCarpeta(
                nombre = nombreCarpetaDrive(id),
                parentFolderId = carpetaEmpresa,
            )
        val ganada = oportunidadRepository.asignarCarpetaDriveSiFalta(id, carpeta) > 0
        val definitiva = if (ganada) carpeta else oportunidadRepository.findDriveFolderId(id) ?: carpeta
        // Coherencia del contexto de persistencia si hay transaccion llamante.
        oportunidad.driveFolderId = definitiva
        return definitiva
    }

    /** Sin `@Transactional`: el listado de Drive no debe retener conexion del pool. */
    override fun archivosDrive(
        id: Long,
        usuario: UsuarioActual,
    ): List<DriveArchivoSubido> = visible(id, usuario).driveFolderId?.let { driveStorageService.listarArchivos(it) } ?: emptyList()

    @Transactional(readOnly = true)
    override fun idsSinCarpetaDrive(): List<Long> = oportunidadRepository.findIdsSinCarpetaDrive()

    /**
     * `OP-{id}`: identifica la oportunidad dentro de la carpeta de la empresa.
     * Ya no incluye el codigo de modelo: con varios modelos por oportunidad (B),
     * un unico codigo dejo de identificar el contenido de la carpeta.
     */
    private fun nombreCarpetaDrive(idOportunidad: Long): String = "OP-$idOportunidad"

    private fun visible(
        id: Long,
        usuario: UsuarioActual,
    ): Oportunidad {
        val oportunidad = entidad(id)
        if (!visibilidad.alcanza(oportunidad, usuario)) {
            throw NoEncontradoException("La oportunidad no existe")
        }
        return oportunidad
    }

    /**
     * Igual que [visible] pero tomando el lock de la fila. Cambiar de estado es la
     * unica operacion que lee el estado actual y escribe una fila de log derivada de
     * el: necesita serializarse contra otro PATCH simultaneo. La regla de visibilidad
     * se repite tal cual (IDOR: ajena → 404, no 403).
     */
    private fun visibleBloqueando(
        id: Long,
        usuario: UsuarioActual,
    ): Oportunidad {
        val oportunidad =
            oportunidadRepository.findByIdBloqueando(id)
                ?: throw NoEncontradoException("La oportunidad no existe")
        if (!visibilidad.alcanza(oportunidad, usuario)) {
            throw NoEncontradoException("La oportunidad no existe")
        }
        return oportunidad
    }

    /**
     * `?estado=` fuera del enum es un error del cliente (400), no un filtro que se
     * ignora: responder 200 con TODAS las oportunidades —cerradas incluidas— ante un
     * typo es peor que fallar. Mismo criterio que `cambiarEstado`, que ya valida
     * este mismo enum. Un valor en blanco se trata como ausencia de filtro.
     */
    private fun estadoFiltro(estado: String?): EstadoOportunidad? {
        val pedido = estado?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { EstadoOportunidad.valueOf(pedido) }.getOrNull()
            ?: throw ValidacionException(
                "El estado '$pedido' no es válido. Estados permitidos: " +
                    EstadoOportunidad.values().joinToString(", ") { it.name },
                field = "estado",
            )
    }

    private fun especificacion(
        filtros: OportunidadFiltros,
        estado: EstadoOportunidad?,
        usuario: UsuarioActual,
    ): Specification<Oportunidad> {
        // Resuelto ANTES de construir la Specification, no dentro de su lambda:
        // Spring Data JPA evalua `toPredicate` dos veces por pagina (contenido y
        // conteo), y `idsColaboracion` es una consulta, no un `equal` gratis como
        // el resto de predicados.
        val idsColaboracion = visibilidad.idsColaboracion(usuario)
        return Specification { root, _, cb ->
            val predicados = mutableListOf<Predicate>()
            val predicadoVisibilidad = visibilidad.predicadoVisibilidad(root, cb, idsColaboracion, usuario)
            if (predicadoVisibilidad != null) {
                predicados += predicadoVisibilidad
            } else if (filtros.idVendedor != null) {
                predicados += cb.equal(root.get<Long>("idVendedor"), filtros.idVendedor)
            }
            estado?.let { predicados += cb.equal(root.get<EstadoOportunidad>("estado"), it) }
            if (estado == null && !filtros.incluirCerradas) {
                predicados += cb.notEqual(root.get<EstadoOportunidad>("estado"), EstadoOportunidad.cerrado)
            }
            filtros.idEmpresa?.let { predicados += cb.equal(root.get<Long>("idEmpresa"), it) }
            filtros.idFinanciadora?.let { predicados += cb.equal(root.get<Long>("idFinanciadora"), it) }
            cb.and(*predicados.toTypedArray())
        }
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
            entradaEtapaActual = entradaEtapa?.comoInstanteUtc(),
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
        // Los items resuelven sus propios modelos por lotes; la oportunidad ya no tiene modelo propio.
        val itemsPorOportunidad = oportunidadItemService.porOportunidades(ids)
        val montosPorOportunidad = oportunidadItemService.montoTotalPorOportunidades(ids)
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
                estado = op.estado.name,
                items = itemsPorOportunidad[opId].orEmpty(),
                // Derivado de los items, no de la columna plana `oportunidades.monto_total` (D15/D21).
                montoTotal = montosPorOportunidad[opId]?.toPlainString(),
                garantia = op.garantia,
                fincParalelo = op.fincParalelo,
                fichaVenta = op.fichaVenta,
                driveFolderId = op.driveFolderId,
                notas = op.notas,
                motivoCierre = op.motivoCierre,
                fechaCierreEstimado = op.fechaCierreEstimado,
                tareasPendientesCount = tareasPendientes[opId] ?: 0,
                eventosPendientesCount = eventosPendientes[opId] ?: 0,
                createdAt = op.createdAt.comoInstanteUtc(),
            )
        }
    }

    private companion object {
        /**
         * Allowlist de `sort` de GET /oportunidades; el primero es el orden por defecto.
         *
         * `precioUnitario` salio de la allowlist en B10 (D9 de
         * `plan-03-mapa-oportunidad-items.md`): con varios modelos por oportunidad no
         * significa nada, y ya no es ni siquiera una columna de `oportunidades`
         * (retirada por V46) — vive por item en `oportunidad_items`.
         *
         * `cantidad` y `montoTotal` si se quedan, pero ya NO son columnas de
         * `oportunidades`: al retirarse la sincronizacion de D21 pasan a ser agregados
         * de `oportunidad_items` y se ordenan por la rama de consulta nativa
         * ([CAMPOS_AGREGADOS], D29 de `plan-07-mapa-retirar-columnas.md`).
         */
        val CAMPOS_ORDENABLES =
            CamposOrdenables(
                "id",
                "estado",
                "cantidad",
                "montoTotal",
                "fechaCierreEstimado",
                "createdAt",
                "updatedAt",
            )

        /**
         * Campos de [CAMPOS_ORDENABLES] que NO son una columna de `oportunidades` sino
         * un agregado de sus items: pedirlos desvia el listado a la rama de consulta
         * nativa. El resto sigue por `Specification` + `Sort` de Spring Data.
         */
        val CAMPOS_AGREGADOS = setOf("cantidad", "montoTotal")
    }
}
