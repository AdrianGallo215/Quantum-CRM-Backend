package pe.quantum.crm.domain.empresas

import jakarta.persistence.criteria.Predicate
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Lazy
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.nombreCompleto
import pe.quantum.crm.domain.empresas.dto.ActualizarEmpresaRequest
import pe.quantum.crm.domain.empresas.dto.AltaEmpresaResultado
import pe.quantum.crm.domain.empresas.dto.CambioEstadoCartera
import pe.quantum.crm.domain.empresas.dto.CarteraMaestraDto
import pe.quantum.crm.domain.empresas.dto.ContactoDeEmpresaDto
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
import pe.quantum.crm.integracion.drive.DriveArchivoSubido
import pe.quantum.crm.integracion.drive.DriveStorageService
import pe.quantum.crm.shared.CamposOrdenables
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.comoInstanteUtc
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
// TooManyFunctions: cartera, cascadas y Drive; igual que su interfaz.
// LongParameterList: son colaboradores inyectados por constructor (CLAUDE.md regla 8),
// no argumentos de una llamada; mismo caso ya asumido en OportunidadServiceImpl.
@Suppress("TooManyFunctions", "LongParameterList")
class EmpresaServiceImpl(
    private val empresaRepository: EmpresaRepository,
    private val empleadoService: EmpleadoService,
    private val notificacionService: NotificacionService,
    private val eventPublisher: ApplicationEventPublisher,
    private val driveStorageService: DriveStorageService,
    // Solo la interfaz publica de contactos (regla 12): empresas nunca toca
    // `empresa_contactos` ni la entidad Contacto. `@Lazy` porque contactos ya
    // depende de EmpresaService (vinculoVisible/resumenPorIds) y Spring Boot 3
    // rechaza los ciclos de constructor; el proxy corta el ciclo al arrancar.
    @Lazy private val contactoService: ContactoService,
    // Transaccion explicita para el alta. `@Transactional` en `crear` obligaria a
    // llamar a Drive con la transaccion ya abierta; asi la red ocurre fuera y la
    // escritura entra despues, en una transaccion propia y corta.
    private val transactionTemplate: TransactionTemplate,
) : EmpresaService {
    private val log = LoggerFactory.getLogger(EmpresaServiceImpl::class.java)

    @Transactional(readOnly = true)
    override fun listar(
        filtros: EmpresaFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<EmpresaListaDto> {
        val pageRequest = Paginacion.pageRequest(page, perPage, sort, dir, CAMPOS_ORDENABLES)
        val estadoCartera = estadoCarteraFiltro(filtros.estadoCartera)
        val resultado = empresaRepository.findAll(especificacion(filtros, estadoCartera, usuario), pageRequest)
        val vendedores =
            empleadoService.resumenPorIds(resultado.content.mapNotNull { it.idVendedor })
        // Ensamblado por lotes (igual que OportunidadServiceImpl.toDtos): un solo
        // viaje a contactos para toda la pagina, no uno por fila.
        val ids = resultado.content.mapNotNull { it.id }
        val contactosCount = ids.takeIf { it.isNotEmpty() }?.let { contactoService.countPorEmpresas(it) }.orEmpty()
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
                    contactosCount = contactosCount[empresa.id] ?: 0,
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
    ): EmpresaDetalleDto = visible(id, usuario).conContactos()

    /** Siempre 200; no expone el vendedor dueño (contrato_api.md §8, B2.2). */
    @Transactional(readOnly = true)
    override fun checkRuc(ruc: String): RucCheckDto =
        if (empresaRepository.existsByRuc(ruc)) {
            RucCheckDto(existe = true, mensaje = "Esta empresa ya está registrada en el sistema")
        } else {
            RucCheckDto(existe = false)
        }

    override fun crear(
        request: CrearEmpresaRequest,
        usuario: UsuarioActual,
    ): AltaEmpresaResultado = alta(request, usuario, conCarpetaDrive = true, reutilizarDelMismoVendedor = true)

    override fun crearSinCarpetaDrive(
        request: CrearEmpresaRequest,
        usuario: UsuarioActual,
    ): EmpresaDetalleDto = alta(request, usuario, conCarpetaDrive = false, reutilizarDelMismoVendedor = false).empresa

    /**
     * Alta de empresa. SIN `@Transactional` a proposito: la llamada a Drive ocurre
     * ANTES de abrir la transaccion de escritura.
     *
     * El contrato (contrato_api.md §8) sigue cumpliendose tal cual — si Drive no
     * responde se lanza aqui y no se llega a insertar nada, asi que la empresa no
     * se crea y el endpoint devuelve 502 con `drive_folder_id` ya resuelto en la
     * respuesta del caso feliz. Lo que cambia es que la latencia de Drive (hasta
     * `app.drive.folder-read-timeout-ms`) ya no transcurre con una conexion de
     * Hikari retenida: con 10 conexiones en el pool, eso era lo que tumbaba la API
     * entera —login incluido— en cuanto Drive se degradaba.
     *
     * El RUC se comprueba antes de tocar Drive para no dejar carpetas huerfanas en
     * los duplicados, que es el caso frecuente (reintento de un CSV, doble submit).
     */
    private fun alta(
        request: CrearEmpresaRequest,
        usuario: UsuarioActual,
        conCarpetaDrive: Boolean,
        reutilizarDelMismoVendedor: Boolean,
    ): AltaEmpresaResultado {
        rechazarSiEsApoyo(usuario)
        val idVendedor = vendedorAlCrear(request.idVendedor, usuario)
        val existente = empresaRepository.findByRuc(request.ruc)
        if (existente != null) {
            // Del mismo vendedor: no es un error, ya la tiene en su cartera. Se
            // devuelve tal cual, sin insertar ni crear carpeta de Drive.
            if (reutilizarDelMismoVendedor && existente.idVendedor == idVendedor) {
                return AltaEmpresaResultado(existente.conContactos(), creada = false)
            }
            throw RucDuplicadoException()
        }
        val carpeta =
            if (conCarpetaDrive) {
                driveStorageService.crearCarpeta(nombreCarpetaDrive(request.ruc, request.razonSocial))
            } else {
                null
            }
        return AltaEmpresaResultado(
            requireNotNull(transactionTemplate.execute { persistirAlta(request, usuario, idVendedor, carpeta) }),
            creada = true,
        )
    }

    /** Empresa + segmentos en una sola transaccion (B2.1/B2.2). */
    private fun persistirAlta(
        request: CrearEmpresaRequest,
        usuario: UsuarioActual,
        idVendedor: Long?,
        carpeta: String?,
    ): EmpresaDetalleDto {
        val ahora = LocalDateTime.now()
        val empresa =
            Empresa(
                ruc = request.ruc,
                razonSocial = request.razonSocial,
                actividadEcon = request.actividadEcon,
                ciiu = request.ciiu,
                sectorIndustrial = request.sectorIndustrial,
                idVendedor = idVendedor,
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
        empresa.driveFolderId = carpeta
        // Recien creada: aun no puede tener contactos, asi que no se consulta.
        return empresaRepository.save(empresa).toDetalle(emptyList())
    }

    /**
     * `id_vendedor` en el alta. Asignar la empresa a otro empleado es la misma
     * decision que reasignarla, y matriz_permisos.md §2.2 ("Reasignar vendedor
     * directo") la reserva a admin y gerencia: el jdv necesita solicitud aprobada
     * y vendedor/analista no pueden. Quedarse la empresa uno mismo (o no enviar
     * el campo) sigue permitido para todos, que es como vendedor y analista
     * conservan visibilidad sobre lo que registran.
     */
    private fun vendedorAlCrear(
        solicitado: Long?,
        usuario: UsuarioActual,
    ): Long? {
        if (solicitado == null) {
            return usuario.id.takeIf { usuario.visibilidadRestringida }
        }
        // Asignarsela a otro es lo que exige permiso; quedarsela uno mismo no.
        if (solicitado != usuario.id) {
            if (!usuario.puedeReasignarDirecto) {
                throw PermisoInsuficienteException("Solo gerencia puede asignar la empresa a otro empleado")
            }
            // Misma validacion que reasignarVendedor: descarta roles no comerciales,
            // empleados inactivos e ids inexistentes (que si no serian un 500 por FK).
            if (!empleadoService.esAsignableComoVendedor(solicitado)) {
                throw ValidacionException("El destino debe ser un vendedor o jdv activo", field = "id_vendedor")
            }
        }
        return solicitado
    }

    override fun asegurarCarpetaDrive(id: Long): String = asegurarCarpetaDriveDe(entidad(id))

    override fun asegurarCarpetaDrive(
        id: Long,
        usuario: UsuarioActual,
    ): String {
        rechazarSiEsApoyo(usuario)
        return asegurarCarpetaDriveDe(visible(id, usuario))
    }

    /**
     * SIN `@Transactional` en los metodos publicos a proposito: aqui hay una llamada
     * de red y no puede correr con una transaccion abierta.
     *
     * Antes esto hacia `SELECT ... FOR UPDATE` y llamaba a Drive con la fila
     * bloqueada. El bloqueo cumplia su funcion (evitar carpetas duplicadas), pero
     * retenia fila y conexion durante toda la latencia de Drive. La exclusion la da
     * ahora un UPDATE condicional atomico: el `WHERE drive_folder_id IS NULL` solo
     * puede casar una vez, asi que la empresa nunca acaba con dos carpetas
     * distintas. Si esta peticion pierde la carrera (0 filas), devuelve la del
     * ganador y descarta la suya, que queda vacia y huerfana en Drive — exactamente
     * el mismo residuo que ya dejaba cualquier rollback posterior a `crearCarpeta`.
     */
    private fun asegurarCarpetaDriveDe(empresa: Empresa): String {
        empresa.driveFolderId?.let { return it }
        val id = requireNotNull(empresa.id)
        val carpeta = driveStorageService.crearCarpeta(nombreCarpetaDrive(empresa.ruc, empresa.razonSocial))
        val ganada = empresaRepository.asignarCarpetaDriveSiFalta(id, carpeta) > 0
        val definitiva = if (ganada) carpeta else empresaRepository.findDriveFolderId(id) ?: carpeta
        // Mantiene coherente la entidad si venia gestionada por una transaccion
        // llamante: sin esto, un flush posterior reescribiria drive_folder_id a null.
        empresa.driveFolderId = definitiva
        return definitiva
    }

    /**
     * Sin `@Transactional`: `visible` resuelve la visibilidad en su propia lectura
     * corta y el listado de Drive (paginado, tantas llamadas como paginas) ocurre
     * ya sin conexion del pool retenida.
     */
    override fun archivosDrive(
        id: Long,
        usuario: UsuarioActual,
    ): List<DriveArchivoSubido> = visible(id, usuario).driveFolderId?.let { driveStorageService.listarArchivos(it) } ?: emptyList()

    @Transactional
    @Suppress("CyclomaticComplexMethod") // Un `?.let` por campo opcional del PATCH; la complejidad es la del DTO, no logica ramificada.
    override fun actualizar(
        id: Long,
        request: ActualizarEmpresaRequest,
        usuario: UsuarioActual,
    ): EmpresaDetalleDto {
        rechazarSiEsApoyo(usuario)
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
        return empresaRepository.save(empresa).conContactos()
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
        rechazarSiEsApoyo(usuario)
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
        rechazarSiEsApoyo(usuario)
        if (!usuario.puedeReasignarDirecto) {
            throw PermisoInsuficienteException("La reasignación directa es exclusiva de gerencia; envía una solicitud")
        }
        val empresa = entidad(id)
        // El CHECK de V27 (chk_cartera_maestra_sin_vendedor) prohibe esta combinacion.
        // Dejarlo caer en la constraint daba un 409 generico que no decia que hacer:
        // hay que liberarla primero desde la cartera maestra.
        if (empresa.enCarteraMaestra) {
            throw ConflictoException(
                "EMPRESA_EN_CARTERA_MAESTRA",
                "La empresa está reservada en la cartera maestra; libérala antes de asignarle un vendedor",
            )
        }
        if (!empleadoService.esAsignableComoVendedor(idVendedor)) {
            throw ValidacionException("El destino debe ser un vendedor o jdv activo", field = "id_vendedor")
        }
        empresa.idVendedor = idVendedor
        empresa.updatedAt = LocalDateTime.now()
        empresa.updatedBy = usuario.id
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
        rechazarSiEsApoyo(usuario)
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

    /**
     * La carpeta de Drive va a la papelera DESPUES de que la transaccion confirme
     * el borrado. Un fallo de Drive no debe revertir el borrado de la empresa: se
     * loguea y sigue (mismo criterio que el resto de operaciones "mejor esfuerzo"
     * con Drive de este servicio).
     */
    @Transactional
    override fun eliminar(id: Long) {
        val empresa = entidad(id)
        val carpeta = empresa.driveFolderId
        empresaRepository.delete(empresa)
        carpeta?.let {
            runCatching { driveStorageService.enviarCarpetaAPapelera(it) }
                .onFailure { ex -> log.warn("No se pudo enviar a la papelera la carpeta {} de la empresa {}", it, id, ex) }
        }
    }

    @Transactional(readOnly = true)
    override fun idsSinCarpetaDrive(): List<Long> = empresaRepository.findIdsSinCarpetaDrive()

    // ── privados ───────────────────────────────────────────────

    /**
     * Roles de apoyo: solo lectura sobre empresas (matriz_permisos.md). 403 y no
     * 404: la empresa puede ser visible para el si colabora en una tarea suya.
     */
    private fun rechazarSiEsApoyo(usuario: UsuarioActual) {
        if (usuario.esRolApoyo) {
            throw PermisoInsuficienteException(
                "Tu rol es de apoyo: puedes consultar esta empresa, pero no modificarla",
            )
        }
    }

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

    /**
     * `?estado_cartera=` fuera del enum es un error del cliente (400), no un filtro
     * que se ignora: mismo criterio que `OportunidadServiceImpl.estadoFiltro`.
     */
    private fun estadoCarteraFiltro(estadoCartera: String?): EstadoCartera? {
        val pedido = estadoCartera?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { EstadoCartera.valueOf(pedido) }.getOrNull()
            ?: throw ValidacionException(
                "El estado_cartera '$pedido' no es válido. Estados permitidos: " +
                    EstadoCartera.values().joinToString(", ") { it.name },
                field = "estado_cartera",
            )
    }

    private fun especificacion(
        filtros: EmpresaFiltros,
        estadoCartera: EstadoCartera?,
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
            estadoCartera?.let { predicados += cb.equal(root.get<EstadoCartera>("estadoCartera"), it) }
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

    /** `{ruc} - {razon social}`: ordena alfabeticamente y es rastreable desde Drive. */
    private fun nombreCarpetaDrive(
        ruc: String,
        razonSocial: String,
    ): String = "$ruc - $razonSocial"

    /**
     * Detalle con `contactos` poblado (contrato_api.md §8). Los datos del vinculo
     * (cargo, toma_decision, es_principal) viven en `empresa_contactos`, tabla del
     * modulo contactos: se piden por su interfaz publica, nunca por SQL propio.
     */
    private fun Empresa.conContactos(): EmpresaDetalleDto = toDetalle(contactoService.contactosDeEmpresa(requireNotNull(id)))

    private fun Empresa.toDetalle(contactos: List<ContactoDeEmpresaDto>): EmpresaDetalleDto {
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
            driveFolderId = driveFolderId,
            sitioWeb = sitioWeb,
            notas = notas,
            idVendedor = idVendedor,
            vendedor = vendedor,
            segmentos = segmentos.map { it.name }.sorted(),
            contactos = contactos,
            enCarteraMaestra = enCarteraMaestra,
            createdAt = createdAt.comoInstanteUtc(),
            createdBy = createdBy,
        )
    }

    private companion object {
        /** Allowlist de `sort` de GET /empresas; el primero es el orden por defecto. */
        val CAMPOS_ORDENABLES =
            CamposOrdenables("id", "ruc", "razonSocial", "estadoCartera", "distrito", "createdAt", "updatedAt")
    }
}
