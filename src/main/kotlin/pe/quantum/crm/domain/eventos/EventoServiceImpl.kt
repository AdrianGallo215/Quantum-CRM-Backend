package pe.quantum.crm.domain.eventos

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.catalogoeventos.CatalogoEventoService
import pe.quantum.crm.domain.catalogoeventos.dto.CatalogoEventoDto
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.nombreCompleto
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.eventos.dto.ActualizarEventoRequest
import pe.quantum.crm.domain.eventos.dto.CrearEventoRequest
import pe.quantum.crm.domain.eventos.dto.EventoDto
import pe.quantum.crm.domain.eventos.dto.EventoOcurridoDto
import pe.quantum.crm.domain.eventos.dto.EventoRecordatorioProyeccion
import pe.quantum.crm.domain.eventos.dto.EventosAgrupadosDto
import pe.quantum.crm.domain.eventos.dto.MarcarDescartadoRequest
import pe.quantum.crm.domain.eventos.dto.MarcarOcurridoRequest
import pe.quantum.crm.domain.eventos.dto.SugerenciaDto
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.shared.comoInstanteUtc
import pe.quantum.crm.shared.enums.EstadoEvento
import pe.quantum.crm.shared.enums.EstadoOportunidad
import pe.quantum.crm.shared.exception.EstadoInvalidoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime
import java.time.ZoneOffset

@Service
// Cruza 3 modulos + notificaciones (evento_creado); de ahi tambien el numero de operaciones publicas.
@Suppress("LongParameterList", "TooManyFunctions")
class EventoServiceImpl(
    private val eventoRepository: EventoRepository,
    private val catalogoEventoService: CatalogoEventoService,
    private val oportunidadService: OportunidadService,
    private val empresaService: EmpresaService,
    private val empleadoService: EmpleadoService,
    private val notificacionService: NotificacionService,
) : EventoService {
    @Transactional(readOnly = true)
    override fun listarPorOportunidad(
        idOportunidad: Long,
        usuario: UsuarioActual,
    ): EventosAgrupadosDto {
        oportunidadService.vinculoVisible(idOportunidad, usuario)
        return agrupar(eventoRepository.findByIdOportunidadOrderByIdAsc(idOportunidad))
    }

    @Transactional
    override fun crearEnOportunidad(
        idOportunidad: Long,
        request: CrearEventoRequest,
        usuario: UsuarioActual,
    ): EventoDto {
        val oportunidad = oportunidadService.vinculoVisible(idOportunidad, usuario)
        val evento = crear(request, idOportunidad = oportunidad.id, idEmpresa = null, usuario = usuario)
        notificarEventoCreado(
            idVendedor = oportunidad.idVendedor,
            idEmpresaParaNombre = oportunidad.idEmpresa,
            entidadTipo = EntidadNotificacion.oportunidad,
            entidadId = oportunidad.id,
            usuario = usuario,
        )
        return evento
    }

    @Transactional
    override fun crearEnEmpresa(
        idEmpresa: Long,
        request: CrearEventoRequest,
        usuario: UsuarioActual,
    ): EventoDto {
        val empresa = empresaService.vinculoVisible(idEmpresa, usuario)
        val evento = crear(request, idOportunidad = null, idEmpresa = empresa.id, usuario = usuario)
        notificarEventoCreado(
            idVendedor = empresa.idVendedor,
            idEmpresaParaNombre = empresa.id,
            entidadTipo = EntidadNotificacion.empresa,
            entidadId = empresa.id,
            usuario = usuario,
        )
        return evento
    }

    @Transactional(readOnly = true)
    override fun listarPorEmpresa(
        idEmpresa: Long,
        usuario: UsuarioActual,
    ): EventosAgrupadosDto {
        empresaService.vinculoVisible(idEmpresa, usuario)
        return agrupar(eventoRepository.findByIdEmpresaAndIdOportunidadIsNullOrderByIdAsc(idEmpresa))
    }

    /**
     * Marca ocurrido y devuelve la SUGERENCIA si el evento dispara cambio de
     * estado. La oportunidad NO se toca aqui: el cambio es una segunda llamada
     * confirmada a `PATCH /oportunidades/:id/estado` (reglas §5.3).
     */
    @Transactional
    override fun marcarOcurrido(
        id: Long,
        request: MarcarOcurridoRequest,
        usuario: UsuarioActual,
    ): EventoOcurridoDto {
        val evento = visible(id, usuario)
        if (evento.estado != EstadoEvento.pendiente) {
            throw EstadoInvalidoException("Solo un evento pendiente puede marcarse como ocurrido")
        }
        evento.estado = EstadoEvento.ocurrido
        evento.fechaOcurrencia =
            request.fechaOcurrencia?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) } ?: LocalDateTime.now()
        request.descripcion?.let { evento.descripcion = it }
        evento.registradoPor = usuario.id
        evento.updatedAt = LocalDateTime.now()
        evento.updatedBy = usuario.id
        eventoRepository.save(evento)

        val sugerencia =
            if (evento.disparaCambioEstado && evento.estadoDestino != null) {
                SugerenciaDto(
                    dispara = true,
                    estadoDestino = evento.estadoDestino.name,
                    mensaje = "¿Deseas mover la oportunidad a ${etiqueta(evento.estadoDestino)}?",
                )
            } else {
                null
            }
        return EventoOcurridoDto(
            id = requireNotNull(evento.id),
            estado = evento.estado.name,
            fechaOcurrencia = evento.fechaOcurrencia?.comoInstanteUtc(),
            sugerencia = sugerencia,
        )
    }

    @Transactional
    override fun marcarDescartado(
        id: Long,
        request: MarcarDescartadoRequest,
        usuario: UsuarioActual,
    ): EventoDto {
        val evento = visible(id, usuario)
        if (evento.estado != EstadoEvento.pendiente) {
            throw EstadoInvalidoException("Solo un evento pendiente puede descartarse")
        }
        evento.estado = EstadoEvento.descartado
        request.descripcion?.let { evento.descripcion = it }
        evento.updatedAt = LocalDateTime.now()
        evento.updatedBy = usuario.id
        return eventoRepository.save(evento).toDto()
    }

    @Transactional
    override fun actualizar(
        id: Long,
        request: ActualizarEventoRequest,
        usuario: UsuarioActual,
    ): EventoDto {
        val evento = visible(id, usuario)
        if (evento.estado != EstadoEvento.pendiente) {
            throw EstadoInvalidoException("Solo se pueden editar eventos pendientes")
        }
        request.fechaEstimada?.let { evento.fechaEstimada = it }
        request.fechaSeguimiento?.let { evento.fechaSeguimiento = it }
        request.descripcion?.let { evento.descripcion = it }
        evento.updatedAt = LocalDateTime.now()
        evento.updatedBy = usuario.id
        return eventoRepository.save(evento).toDto()
    }

    @Transactional(readOnly = true)
    override fun pendientesParaRecordatorio(): List<EventoRecordatorioProyeccion> =
        eventoRepository.findByEstadoAndFechaEstimadaIsNotNull(EstadoEvento.pendiente).map {
            EventoRecordatorioProyeccion(
                id = requireNotNull(it.id),
                idOportunidad = it.idOportunidad,
                idEmpresa = it.idEmpresa,
                fechaEstimada = requireNotNull(it.fechaEstimada),
            )
        }

    // ── privados ───────────────────────────────────────────────

    /**
     * Origen mutuamente excluyente (reglas §5.1): del catalogo hereda
     * dispara/destino; personalizado exige nombre y nunca dispara.
     */
    @Suppress("LongMethod", "ThrowsCount") // Guard clauses del origen excluyente; separarlas dispersaria la regla §5.1.
    private fun crear(
        request: CrearEventoRequest,
        idOportunidad: Long?,
        idEmpresa: Long?,
        usuario: UsuarioActual,
    ): EventoDto {
        val ahora = LocalDateTime.now()
        val evento =
            if (request.esPersonalizado) {
                if (request.nombrePersonalizado.isNullOrBlank()) {
                    throw ValidacionException(
                        "Un evento personalizado requiere nombre_personalizado",
                        field = "nombre_personalizado",
                    )
                }
                if (request.idCatalogoEvento != null) {
                    throw ValidacionException(
                        "Un evento personalizado no puede referenciar el catálogo",
                        field = "id_catalogo_evento",
                    )
                }
                Evento(
                    idOportunidad = idOportunidad,
                    idEmpresa = idEmpresa,
                    idCatalogoEvento = null,
                    esPersonalizado = true,
                    nombrePersonalizado = request.nombrePersonalizado,
                    descripcion = request.descripcion,
                    fechaEstimada = request.fechaEstimada,
                    fechaSeguimiento = request.fechaSeguimiento,
                    // Los personalizados NUNCA disparan cambio de estado (§5.1).
                    disparaCambioEstado = false,
                    estadoDestino = null,
                    createdAt = ahora,
                    createdBy = usuario.id,
                    updatedAt = ahora,
                    updatedBy = usuario.id,
                )
            } else {
                val idCatalogo =
                    request.idCatalogoEvento
                        ?: throw ValidacionException(
                            "Un evento del catálogo requiere id_catalogo_evento",
                            field = "id_catalogo_evento",
                        )
                val catalogo = catalogoEventoService.porId(idCatalogo)
                if (idEmpresa != null && catalogo.etapaAsociada != null) {
                    throw ValidacionException(
                        "Este evento pertenece a una etapa del pipeline y debe registrarse en una oportunidad, no en una empresa",
                        field = "id_catalogo_evento",
                    )
                }
                Evento(
                    idOportunidad = idOportunidad,
                    idEmpresa = idEmpresa,
                    idCatalogoEvento = idCatalogo,
                    esPersonalizado = false,
                    nombrePersonalizado = null,
                    descripcion = request.descripcion,
                    fechaEstimada = request.fechaEstimada,
                    fechaSeguimiento = request.fechaSeguimiento,
                    disparaCambioEstado = catalogo.disparaCambioEstado,
                    estadoDestino = catalogo.estadoDestino?.let { EstadoOportunidad.valueOf(it) },
                    createdAt = ahora,
                    createdBy = usuario.id,
                    updatedAt = ahora,
                    updatedBy = usuario.id,
                )
            }
        return eventoRepository.save(evento).toDto()
    }

    /**
     * Vendedor asignado (si el actor no es el); si el actor ES el vendedor,
     * notifica a los supervisores en su lugar (docs/superpowers/specs/2026-07-09-notificaciones-in-app-design.md).
     */
    private fun notificarEventoCreado(
        idVendedor: Long?,
        idEmpresaParaNombre: Long,
        entidadTipo: EntidadNotificacion,
        entidadId: Long,
        usuario: UsuarioActual,
    ) {
        val empresa = empresaService.resumenPorIds(listOf(idEmpresaParaNombre))[idEmpresaParaNombre] ?: return
        val actor = empleadoService.resumenPorIds(listOf(usuario.id))[usuario.id] ?: return
        val destinatarios =
            if (idVendedor != null && idVendedor != usuario.id) {
                setOf(idVendedor)
            } else {
                empleadoService.idsSupervisoresActivos().toSet()
            }
        notificacionService.notificar(
            destinatarios = destinatarios,
            idActor = usuario.id,
            tipo = TipoNotificacion.evento_creado,
            mensaje = "${actor.nombreCompleto()} creó un evento en ${empresa.razonSocial}",
            entidadTipo = entidadTipo,
            entidadId = entidadId,
        )
    }

    private fun entidad(id: Long): Evento = eventoRepository.findById(id).orElseThrow { NoEncontradoException("El evento no existe") }

    /** Visibilidad via su oportunidad o su empresa (IDOR → 404). */
    private fun visible(
        id: Long,
        usuario: UsuarioActual,
    ): Evento {
        val evento = entidad(id)
        val idOportunidad = evento.idOportunidad
        val idEmpresa = evento.idEmpresa
        when {
            idOportunidad != null -> oportunidadService.vinculoVisible(idOportunidad, usuario)
            idEmpresa != null -> empresaService.vinculoVisible(idEmpresa, usuario)
            else -> throw NoEncontradoException("El evento no existe")
        }
        return evento
    }

    private fun agrupar(eventos: List<Evento>): EventosAgrupadosDto {
        val dtos = toDtos(eventos)
        return EventosAgrupadosDto(
            pendientes = dtos.filter { it.estado == EstadoEvento.pendiente.name },
            ocurridos = dtos.filter { it.estado == EstadoEvento.ocurrido.name },
            descartados = dtos.filter { it.estado == EstadoEvento.descartado.name },
        )
    }

    private fun toDtos(eventos: List<Evento>): List<EventoDto> {
        if (eventos.isEmpty()) {
            return emptyList()
        }
        val catalogo = catalogoEventoService.todosPorId()
        return eventos.map { it.toDto(catalogo) }
    }

    private fun Evento.toDto(catalogo: Map<Long, CatalogoEventoDto>? = null): EventoDto {
        val entrada = idCatalogoEvento?.let { (catalogo ?: catalogoEventoService.todosPorId())[it] }
        return EventoDto(
            id = requireNotNull(id),
            idOportunidad = idOportunidad,
            idEmpresa = idEmpresa,
            idCatalogoEvento = idCatalogoEvento,
            nombre = nombrePersonalizado ?: entrada?.nombre ?: "Evento",
            esPersonalizado = esPersonalizado,
            descripcion = descripcion,
            estado = estado.name,
            fechaEstimada = fechaEstimada,
            fechaSeguimiento = fechaSeguimiento,
            fechaOcurrencia = fechaOcurrencia?.comoInstanteUtc(),
            disparaCambioEstado = disparaCambioEstado,
            estadoDestino = estadoDestino?.name,
            esRecomendado = entrada?.esRecomendado ?: false,
            etapaAsociada = entrada?.etapaAsociada,
            esHitoProspeccion = entrada?.esHitoProspeccion ?: false,
        )
    }

    /** Etiqueta legible del estado destino para el prompt del frontend. */
    private fun etiqueta(estado: EstadoOportunidad): String =
        when (estado) {
            EstadoOportunidad.evaluacion_calidda -> "Evaluación Calidda"
            EstadoOportunidad.documentos_legales -> "Documentos Legales"
            EstadoOportunidad.facturado -> "Facturado"
            EstadoOportunidad.cerrado -> "Cerrado"
        }
}
