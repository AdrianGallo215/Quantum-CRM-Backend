package pe.quantum.crm.domain.contactos

import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import org.springframework.context.annotation.Lazy
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.contactos.dto.ActualizarContactoRequest
import pe.quantum.crm.domain.contactos.dto.ActualizarVinculoRequest
import pe.quantum.crm.domain.contactos.dto.ContactoDetalleDto
import pe.quantum.crm.domain.contactos.dto.ContactoDto
import pe.quantum.crm.domain.contactos.dto.ContactoListaDto
import pe.quantum.crm.domain.contactos.dto.ContactoResumen
import pe.quantum.crm.domain.contactos.dto.ContextoBusquedaContacto
import pe.quantum.crm.domain.contactos.dto.CrearContactoRequest
import pe.quantum.crm.domain.contactos.dto.EmpresaDeContactoDetalleDto
import pe.quantum.crm.domain.contactos.dto.EmpresaDeContactoDto
import pe.quantum.crm.domain.contactos.dto.VincularContactoRequest
import pe.quantum.crm.domain.contactos.dto.VinculoDto
import pe.quantum.crm.domain.contactos.dto.toResumen
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.ContactoDeEmpresaDto
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.shared.CamposOrdenables
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.ContactoVinculadoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime

@Service
// TooManyFunctions: vinculacion con empresas y oportunidades; igual que su interfaz.
// LongParameterList: `buscar` arrastra los 4 parametros de paginacion del contrato
// mas el contexto de visibilidad; son el contrato del endpoint, no una firma suelta.
@Suppress("TooManyFunctions", "LongParameterList")
class ContactoServiceImpl(
    private val contactoRepository: ContactoRepository,
    private val empresaContactoRepository: EmpresaContactoRepository,
    private val empresaService: EmpresaService,
    // Solo la interfaz publica de tareas (CLAUDE.md regla 12): contactos nunca toca
    // `tareas` ni `tarea_responsables`, recibe ids. `@Lazy` porque tareas ya depende
    // de ContactoService (existe/resumenPorIds) y Spring Boot 3 rechaza los ciclos
    // de constructor; el proxy corta el ciclo al arrancar. Mismo patron que
    // EmpresaServiceImpl con este mismo colaborador.
    @Lazy private val tareaService: TareaService,
) : ContactoService {
    @Transactional(readOnly = true)
    override fun buscar(
        q: String?,
        idEmpresa: Long?,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
        contexto: ContextoBusquedaContacto,
    ): Paginado<ContactoListaDto> {
        val reducido = contexto.esReducidoPara(usuario)
        val idsDeLaEmpresa =
            idEmpresa?.let {
                empresaService.vinculoVisible(it, usuario)
                empresaContactoRepository.findByIdIdEmpresa(it).map { vinculo -> vinculo.id.idContacto }
            }
        // Resuelto ANTES de construir la Specification, no dentro de su lambda:
        // Spring Data JPA evalua `toPredicate` dos veces por pagina (contenido y
        // conteo), y esto son dos consultas, no un `equal` gratis. Mismo criterio
        // que EmpresaServiceImpl.especificacion.
        val idsVisibles =
            if (contexto.aplicaFiltroDeVisibilidadPara(usuario)) idsContactosVisiblesPara(usuario) else null
        val pageRequest = Paginacion.pageRequest(page, perPage, sort, dir, CAMPOS_ORDENABLES)
        val resultado =
            contactoRepository.findAll(
                especificacion(q, idsDeLaEmpresa, idsVisibles, soloPorNombre = reducido),
                pageRequest,
            )
        val items = resultado.content.map { if (reducido) it.toListaReducidoDto() else it.toListaDto() }
        val meta = Paginacion.meta(pageRequest.pageNumber + 1, pageRequest.pageSize, resultado.totalElements)
        return Paginado(items, meta)
    }

    @Transactional
    override fun crear(
        request: CrearContactoRequest,
        usuario: UsuarioActual,
    ): ContactoDto {
        empresaService.vinculoVisible(request.idEmpresa, usuario)
        val ahora = LocalDateTime.now()
        val contacto =
            contactoRepository.save(
                Contacto(
                    nombres = request.nombres,
                    apellidos = request.apellidos,
                    email_1 = request.email_1,
                    email_2 = request.email_2,
                    tlf_1 = request.tlf_1,
                    tlf_2 = request.tlf_2,
                    notas = request.notas,
                    createdAt = ahora,
                    createdBy = usuario.id,
                    updatedAt = ahora,
                    updatedBy = usuario.id,
                ),
            )
        empresaContactoRepository.save(
            EmpresaContacto(
                id = EmpresaContactoId(idEmpresa = request.idEmpresa, idContacto = requireNotNull(contacto.id)),
                cargo = request.cargo,
                tomaDecision = request.tomaDecision,
                esPrincipal = request.esPrincipal,
            ),
        )
        return contacto.toDto()
    }

    @Transactional
    override fun actualizar(
        id: Long,
        request: ActualizarContactoRequest,
        usuario: UsuarioActual,
    ): ContactoDto {
        val contacto = entidad(id)
        rechazarSiFueraDeAlcance(id, usuario)
        request.nombres?.let { contacto.nombres = it }
        request.apellidos?.let { contacto.apellidos = it }
        request.email_1?.let { contacto.email_1 = it }
        request.email_2?.let { contacto.email_2 = it }
        request.tlf_1?.let { contacto.tlf_1 = it }
        request.tlf_2?.let { contacto.tlf_2 = it }
        request.notas?.let { contacto.notas = it }
        contacto.updatedAt = LocalDateTime.now()
        contacto.updatedBy = usuario.id
        return contactoRepository.save(contacto).toDto()
    }

    @Transactional
    override fun eliminar(id: Long) {
        val contacto = entidad(id)
        if (empresaContactoRepository.existsByIdIdContacto(id)) {
            throw ContactoVinculadoException()
        }
        contactoRepository.delete(contacto)
    }

    @Transactional(readOnly = true)
    override fun detalle(
        id: Long,
        usuario: UsuarioActual,
        contexto: ContextoBusquedaContacto,
    ): ContactoDetalleDto {
        val contacto = entidad(id)
        // Modo reducido: el buscador de vinculacion alcanza todo el CRM, asi que
        // aqui no hay 404 por alcance — lo que se recorta es el contenido.
        if (contexto.esReducidoPara(usuario)) {
            return contacto.toDetalleReducido()
        }
        // IDOR: contacto fuera de alcance -> 404, no 403 (CLAUDE.md regla 14). El
        // mensaje es identico al del inexistente a proposito: no debe poder
        // distinguirse un contacto ajeno de uno que no existe.
        if (contexto.aplicaFiltroDeVisibilidadPara(usuario) && id !in idsContactosVisiblesPara(usuario)) {
            throw NoEncontradoException("El contacto no existe")
        }
        val vinculos = empresaContactoRepository.findByIdIdContacto(id)
        val empresas = empresaService.resumenPorIds(vinculos.map { it.id.idEmpresa })
        val segmentos = empresaService.segmentosPorIds(vinculos.map { it.id.idEmpresa })
        return ContactoDetalleDto(
            id = requireNotNull(contacto.id),
            nombres = contacto.nombres,
            apellidos = contacto.apellidos,
            email_1 = contacto.email_1,
            email_2 = contacto.email_2,
            tlf_1 = contacto.tlf_1,
            tlf_2 = contacto.tlf_2,
            notas = contacto.notas,
            empresas =
                vinculos.mapNotNull { vinculo ->
                    empresas[vinculo.id.idEmpresa]?.let {
                        EmpresaDeContactoDetalleDto(
                            id = it.id,
                            razonSocial = it.razonSocial,
                            cargo = vinculo.cargo,
                            tomaDecision = vinculo.tomaDecision,
                            esPrincipal = vinculo.esPrincipal,
                            segmentos = segmentos[vinculo.id.idEmpresa].orEmpty(),
                        )
                    }
                },
        )
    }

    @Transactional
    override fun vincular(
        idEmpresa: Long,
        request: VincularContactoRequest,
        usuario: UsuarioActual,
    ): VinculoDto {
        rechazarSiEsApoyo(usuario)
        empresaService.vinculoVisible(idEmpresa, usuario)
        entidad(request.idContacto)
        val id = EmpresaContactoId(idEmpresa = idEmpresa, idContacto = request.idContacto)
        if (empresaContactoRepository.existsById(id)) {
            throw ConflictoException("VINCULO_DUPLICADO", "El contacto ya está vinculado a esta empresa")
        }
        val vinculo =
            empresaContactoRepository.save(
                EmpresaContacto(
                    id = id,
                    cargo = request.cargo,
                    tomaDecision = request.tomaDecision,
                    esPrincipal = request.esPrincipal,
                ),
            )
        return vinculo.toDto()
    }

    @Transactional
    override fun actualizarVinculo(
        idEmpresa: Long,
        idContacto: Long,
        request: ActualizarVinculoRequest,
        usuario: UsuarioActual,
    ): VinculoDto {
        rechazarSiEsApoyo(usuario)
        empresaService.vinculoVisible(idEmpresa, usuario)
        val vinculo = vinculoEntidad(idEmpresa, idContacto)
        request.cargo?.let { vinculo.cargo = it }
        request.tomaDecision?.let { vinculo.tomaDecision = it }
        request.esPrincipal?.let { vinculo.esPrincipal = it }
        return empresaContactoRepository.save(vinculo).toDto()
    }

    @Transactional
    override fun desvincular(
        idEmpresa: Long,
        idContacto: Long,
        usuario: UsuarioActual,
    ) {
        rechazarSiEsApoyo(usuario)
        empresaService.vinculoVisible(idEmpresa, usuario)
        val vinculo = vinculoEntidad(idEmpresa, idContacto)
        empresaContactoRepository.delete(vinculo)
    }

    @Transactional(readOnly = true)
    override fun contactosDeEmpresa(idEmpresa: Long): List<ContactoDeEmpresaDto> {
        val vinculos = empresaContactoRepository.findByIdIdEmpresa(idEmpresa)
        if (vinculos.isEmpty()) {
            return emptyList()
        }
        val contactos = contactoRepository.findAllById(vinculos.map { it.id.idContacto }).associateBy { it.id }
        return vinculos.mapNotNull { vinculo ->
            contactos[vinculo.id.idContacto]?.let { contacto ->
                ContactoDeEmpresaDto(
                    id = requireNotNull(contacto.id),
                    nombres = contacto.nombres,
                    apellidos = contacto.apellidos,
                    cargo = vinculo.cargo,
                    tomaDecision = vinculo.tomaDecision,
                    esPrincipal = vinculo.esPrincipal,
                    email_1 = contacto.email_1,
                    tlf_1 = contacto.tlf_1,
                )
            }
        }.sortedByDescending { it.esPrincipal }
    }

    @Transactional(readOnly = true)
    override fun countPorEmpresa(idEmpresa: Long): Int = empresaContactoRepository.countByIdIdEmpresa(idEmpresa).toInt()

    @Transactional(readOnly = true)
    override fun countPorEmpresas(idsEmpresa: Collection<Long>): Map<Long, Int> {
        val ids = idsEmpresa.toSet()
        if (ids.isEmpty()) {
            return emptyMap()
        }
        return empresaContactoRepository
            .findByIdIdEmpresaIn(ids)
            .groupingBy { it.id.idEmpresa }
            .eachCount()
    }

    @Transactional(readOnly = true)
    override fun existe(id: Long): Boolean = contactoRepository.existsById(id)

    @Transactional(readOnly = true)
    override fun resumenPorIds(ids: Collection<Long>): Map<Long, ContactoResumen> =
        contactoRepository.findAllById(ids.toSet()).associate { requireNotNull(it.id) to it.toResumen() }

    // ── privados ───────────────────────────────────────────────

    private fun entidad(id: Long): Contacto = contactoRepository.findById(id).orElseThrow { NoEncontradoException("El contacto no existe") }

    private fun especificacion(
        q: String?,
        idsDeLaEmpresa: List<Long>?,
        idsVisibles: Set<Long>?,
        soloPorNombre: Boolean,
    ): Specification<Contacto> =
        Specification { root, _, cb ->
            val predicados = mutableListOf<Predicate>()
            restriccionPorIds(idsDeLaEmpresa, root, cb)?.let { predicados += it }
            restriccionPorIds(idsVisibles, root, cb)?.let { predicados += it }
            q?.takeIf { it.isNotBlank() }?.let { texto ->
                val patron = "%${texto.lowercase()}%"
                val porNombre =
                    cb.like(cb.lower(cb.concat(cb.concat(root.get("nombres"), " "), root.get("apellidos"))), patron)
                predicados +=
                    if (soloPorNombre) {
                        // Modo reducido: buscar por telefono seria un oraculo. La
                        // respuesta oculta el numero, pero un `like` sobre `tlf_1`
                        // devolveria igual el nombre del dueño de ese numero.
                        porNombre
                    } else {
                        cb.or(
                            porNombre,
                            // Los atributos JPA se llaman como el campo de la entidad
                            // (`tlf_1`/`tlf_2`), no como su version sin guion bajo.
                            cb.like(root.get("tlf_1"), "%${texto.trim()}%"),
                            cb.like(root.get("tlf_2"), "%${texto.trim()}%"),
                        )
                    }
            }
            cb.and(*predicados.toTypedArray())
        }

    /**
     * `null` = sin restriccion. Coleccion vacia = falso explicito: `in(emptySet())`
     * es SQL invalido o, peor, un predicado que no filtra nada — y ahi es justo
     * donde se colaria el listado completo.
     */
    private fun restriccionPorIds(
        ids: Collection<Long>?,
        root: Root<Contacto>,
        cb: CriteriaBuilder,
    ): Predicate? {
        if (ids == null) {
            return null
        }
        return if (ids.isEmpty()) cb.disjunction() else root.get<Long>("id").`in`(ids)
    }

    /**
     * Contactos que un rol de apoyo alcanza: los vinculados a alguna empresa donde
     * colabora via tarea (matriz_permisos.md §1). Un contacto sin ninguna empresa
     * vinculada no lo alcanza nadie por esta via, y es lo correcto: el huerfano no
     * pertenece a ninguna cartera.
     *
     * Cruza la frontera del modulo tareas solo con ids, por su interfaz publica
     * (CLAUDE.md regla 12).
     */
    private fun idsContactosVisiblesPara(usuario: UsuarioActual): Set<Long> {
        val empresas = tareaService.idsEmpresasDondeColabora(usuario.id)
        if (empresas.isEmpty()) {
            return emptySet()
        }
        return empresaContactoRepository.findByIdIdEmpresaIn(empresas).map { it.id.idContacto }.toSet()
    }

    /**
     * Escritura de un rol de apoyo sobre un contacto que no alcanza: 403, no 404.
     *
     * Es una desviacion deliberada de CLAUDE.md regla 14, aprobada por producto
     * (R10 del requerimiento). La regla existe para no confirmar la existencia de
     * un recurso que el usuario no deberia poder enumerar — y aqui no aplica: en
     * contexto `vincular` este mismo usuario ve legitimamente ese contacto por
     * nombre, asi que su existencia no es secreta para el. Devolver 404 al editar
     * mentiria sobre algo que el sistema le acaba de mostrar. Mismo razonamiento
     * (y mismo status) que `EmpresaServiceImpl.rechazarSiEsApoyo`.
     */
    private fun rechazarSiFueraDeAlcance(
        idContacto: Long,
        usuario: UsuarioActual,
    ) {
        if (!usuario.esRolApoyo) {
            return
        }
        if (idContacto !in idsContactosVisiblesPara(usuario)) {
            throw PermisoInsuficienteException(
                "Tu rol es de apoyo: solo puedes editar contactos de las empresas donde colaboras",
            )
        }
    }

    /**
     * Vinculacion de contactos a empresas para un rol de apoyo: bloqueada por
     * completo, con 403. No es un guard de alcance (como `rechazarSiFueraDeAlcance`
     * para editar) sino un bloqueo total, igual que
     * `OportunidadServiceImpl.vincularContacto` bloquea la vinculacion de
     * contactos a oportunidades con `visibilidad.rechazarSiEsApoyo`.
     *
     * Por que bloquear en vez de acotar por alcance: `?contexto=vincular` busca
     * deliberadamente en todo el CRM (R5, no es un descuido). Si la vinculacion en
     * si misma solo estuviera acotada a contactos ya visibles, seguiria sin cerrar
     * nada porque el usuario nunca intentaria vincular algo que ya ve. El riesgo
     * real es el camino completo: buscar en todo el CRM -> vincular a una empresa
     * donde colabora -> `GET /contactos/:id` en modo listado lo devuelve completo.
     * Bloquear el segundo paso cierra el camino sin tocar la busqueda (R5 se
     * mantiene: sigue sirviendo para no crear un contacto duplicado, aunque el rol
     * de apoyo no pueda vincularlo el mismo).
     */
    private fun rechazarSiEsApoyo(usuario: UsuarioActual) {
        if (usuario.esRolApoyo) {
            throw PermisoInsuficienteException(
                "Tu rol es de apoyo: puedes consultar este contacto, pero no puedes vincularlo a una empresa",
            )
        }
    }

    private fun Contacto.toListaDto(): ContactoListaDto {
        val vinculos = empresaContactoRepository.findByIdIdContacto(requireNotNull(id))
        val empresas = empresaService.resumenPorIds(vinculos.map { it.id.idEmpresa })
        return ContactoListaDto(
            id = requireNotNull(id),
            nombres = nombres,
            apellidos = apellidos,
            email_1 = email_1,
            email_2 = email_2,
            tlf_1 = tlf_1,
            tlf_2 = tlf_2,
            notas = notas,
            empresas =
                vinculos.mapNotNull { vinculo ->
                    empresas[vinculo.id.idEmpresa]?.let {
                        EmpresaDeContactoDto(id = it.id, razonSocial = it.razonSocial, cargo = vinculo.cargo)
                    }
                },
        )
    }

    /**
     * Fila del buscador de vinculacion para un rol de apoyo: solo el nombre.
     * Ni telefonos, ni correos, ni notas, ni las empresas del contacto — saber a
     * que empresas pertenece es justo el dato que no tiene por que ver de una
     * empresa donde no colabora. De paso evita la consulta de vinculos por fila
     * que hace `toListaDto`.
     */
    private fun Contacto.toListaReducidoDto(): ContactoListaDto =
        ContactoListaDto(
            id = requireNotNull(id),
            nombres = nombres,
            apellidos = apellidos,
            email_1 = null,
            email_2 = null,
            tlf_1 = null,
            tlf_2 = null,
            notas = null,
            empresas = emptyList(),
        )

    /**
     * Detalle del buscador de vinculacion para un rol de apoyo: solo el nombre.
     * `oportunidades` y `actividades` quedan vacias — el controller ni siquiera
     * las consulta en este modo.
     */
    private fun Contacto.toDetalleReducido(): ContactoDetalleDto =
        ContactoDetalleDto(
            id = requireNotNull(id),
            nombres = nombres,
            apellidos = apellidos,
            email_1 = null,
            email_2 = null,
            tlf_1 = null,
            tlf_2 = null,
            notas = null,
            empresas = emptyList(),
        )

    private fun vinculoEntidad(
        idEmpresa: Long,
        idContacto: Long,
    ): EmpresaContacto =
        empresaContactoRepository
            .findById(EmpresaContactoId(idEmpresa = idEmpresa, idContacto = idContacto))
            .orElseThrow { NoEncontradoException("El contacto no está vinculado a esta empresa") }

    private fun EmpresaContacto.toDto(): VinculoDto =
        VinculoDto(
            idEmpresa = id.idEmpresa,
            idContacto = id.idContacto,
            cargo = cargo,
            tomaDecision = tomaDecision,
            esPrincipal = esPrincipal,
        )

    private fun Contacto.toDto(): ContactoDto {
        val vinculos = empresaContactoRepository.findByIdIdContacto(requireNotNull(id))
        val empresas = empresaService.resumenPorIds(vinculos.map { it.id.idEmpresa })
        return ContactoDto(
            id = requireNotNull(id),
            nombres = nombres,
            apellidos = apellidos,
            email_1 = email_1,
            email_2 = email_2,
            tlf_1 = tlf_1,
            tlf_2 = tlf_2,
            notas = notas,
            empresas =
                vinculos.mapNotNull { vinculo ->
                    empresas[vinculo.id.idEmpresa]?.let {
                        EmpresaDeContactoDto(id = it.id, razonSocial = it.razonSocial, cargo = vinculo.cargo)
                    }
                },
        )
    }

    private companion object {
        /** Allowlist de `sort` de GET /contactos; el primero es el orden por defecto. */
        val CAMPOS_ORDENABLES = CamposOrdenables("id", "nombres", "apellidos", "createdAt", "updatedAt")
    }
}
