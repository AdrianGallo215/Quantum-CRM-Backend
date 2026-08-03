package pe.quantum.crm.domain.contactos

import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.contactos.dto.ActualizarContactoRequest
import pe.quantum.crm.domain.contactos.dto.ActualizarVinculoRequest
import pe.quantum.crm.domain.contactos.dto.ContactoDetalleDto
import pe.quantum.crm.domain.contactos.dto.ContactoDto
import pe.quantum.crm.domain.contactos.dto.ContactoListaDto
import pe.quantum.crm.domain.contactos.dto.ContactoResumen
import pe.quantum.crm.domain.contactos.dto.CrearContactoRequest
import pe.quantum.crm.domain.contactos.dto.EmpresaDeContactoDetalleDto
import pe.quantum.crm.domain.contactos.dto.EmpresaDeContactoDto
import pe.quantum.crm.domain.contactos.dto.VincularContactoRequest
import pe.quantum.crm.domain.contactos.dto.VinculoDto
import pe.quantum.crm.domain.contactos.dto.toResumen
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.ContactoDeEmpresaDto
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.ContactoVinculadoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime

@Service
@Suppress("TooManyFunctions") // Igual que su interfaz: vinculacion con empresas y oportunidades.
class ContactoServiceImpl(
    private val contactoRepository: ContactoRepository,
    private val empresaContactoRepository: EmpresaContactoRepository,
    private val empresaService: EmpresaService,
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
    ): Paginado<ContactoListaDto> {
        val idsPermitidos =
            idEmpresa?.let {
                empresaService.vinculoVisible(it, usuario)
                empresaContactoRepository.findByIdIdEmpresa(it).map { vinculo -> vinculo.id.idContacto }
            }
        val pageRequest = Paginacion.pageRequest(page, perPage, sort, dir, defaultSort = "id")
        val resultado = contactoRepository.findAll(especificacion(q, idsPermitidos), pageRequest)
        val items = resultado.content.map { it.toListaDto() }
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
    override fun detalle(id: Long): ContactoDetalleDto {
        val contacto = entidad(id)
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
        idsPermitidos: List<Long>?,
    ): Specification<Contacto> =
        Specification { root, _, cb ->
            val predicados = mutableListOf<Predicate>()
            if (idsPermitidos != null) {
                if (idsPermitidos.isEmpty()) {
                    predicados += cb.disjunction()
                } else {
                    predicados += root.get<Long>("id").`in`(idsPermitidos)
                }
            }
            q?.takeIf { it.isNotBlank() }?.let { texto ->
                val patron = "%${texto.lowercase()}%"
                predicados +=
                    cb.or(
                        cb.like(cb.lower(cb.concat(cb.concat(root.get("nombres"), " "), root.get("apellidos"))), patron),
                        cb.like(root.get("tlf1"), "%${texto.trim()}%"),
                        cb.like(root.get("tlf2"), "%${texto.trim()}%"),
                    )
            }
            cb.and(*predicados.toTypedArray())
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
}
