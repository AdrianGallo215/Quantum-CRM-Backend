package pe.quantum.crm.domain.prospeccion

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.catalogoeventos.CatalogoEventoService
import pe.quantum.crm.domain.prospeccion.dto.HitoDto
import pe.quantum.crm.domain.prospeccion.dto.ProspeccionItemDto
import pe.quantum.crm.domain.prospeccion.dto.ResumenProspeccionDto
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.comoInstanteUtc
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Service
class ProspeccionServiceImpl(
    private val dao: ProspeccionDao,
    private val catalogoEventoService: CatalogoEventoService,
) : ProspeccionService {
    @Transactional(readOnly = true)
    override fun listar(
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
    ): Paginado<ProspeccionItemDto> {
        val items = calcular(usuario)
        val pagina = (page ?: 1).coerceAtLeast(1)
        val tamano = (perPage ?: Paginacion.PER_PAGE_DEFAULT).coerceIn(1, Paginacion.PER_PAGE_MAX)
        val desde = (pagina - 1) * tamano
        val paginaItems = if (desde >= items.size) emptyList() else items.subList(desde, minOf(desde + tamano, items.size))
        return Paginado(paginaItems, Paginacion.meta(pagina, tamano, items.size.toLong()))
    }

    @Transactional(readOnly = true)
    override fun resumen(usuario: UsuarioActual): ResumenProspeccionDto {
        val items = calcular(usuario)
        return ResumenProspeccionDto(
            total = items.size,
            listasParaConvertir = items.count { it.listaParaConvertir },
            // Sin avance y sin actividad reciente (contrato §17).
            requierenAtencion = items.count { it.checkpointsCompletados == 0 && it.diasSinActividad >= DIAS_ATENCION },
        )
    }

    /** Calculo completo en memoria: el ordenamiento depende de valores derivados. */
    private fun calcular(usuario: UsuarioActual): List<ProspeccionItemDto> {
        val empresas = dao.empresasEnProspeccion(usuario.id.takeIf { usuario.visibilidadRestringida })
        if (empresas.isEmpty()) {
            return emptyList()
        }
        val ids = empresas.map { it.id }
        val hitosCatalogo = catalogoEventoService.hitosProspeccion()
        val hitosOcurridos = dao.hitosOcurridos(ids)
        val ultimaActividad = dao.ultimaActividad(ids)
        val siguienteTarea = dao.siguienteTarea(ids)
        val segmentos = dao.segmentos(ids)
        val contactos = dao.contactoPrincipal(ids)
        val ahora = LocalDateTime.now()

        return empresas
            .map { empresa ->
                val hitos =
                    hitosCatalogo.map { hito ->
                        val clave = empresa.id to hito.id
                        // El avance lo marca la existencia del evento ocurrido, no su
                        // fecha: `fecha_ocurrencia` puede ser nula en un evento ya
                        // ocurrido (CHECK de V14) y aun asi el hito esta cumplido.
                        val fecha = hitosOcurridos[clave]
                        HitoDto(
                            nombre = hito.nombre,
                            completado = hitosOcurridos.containsKey(clave),
                            fecha = fecha?.comoInstanteUtc(),
                        )
                    }
                val completados = hitos.count { it.completado }
                val ultima = ultimaActividad[empresa.id]
                // Sin actividad registrada, cuentan los dias desde la creacion.
                val referencia = ultima ?: empresa.createdAt
                ProspeccionItemDto(
                    idEmpresa = empresa.id,
                    ruc = empresa.ruc,
                    razonSocial = empresa.razonSocial,
                    corta = empresa.razonSocial.take(LONGITUD_CORTA),
                    distrito = empresa.distrito,
                    segmentos = segmentos[empresa.id].orEmpty(),
                    contactoPrincipal = contactos[empresa.id],
                    checkpointsCompletados = completados,
                    checkpointsTotal = hitosCatalogo.size,
                    hitos = hitos,
                    diasSinActividad = ChronoUnit.DAYS.between(referencia, ahora).toInt().coerceAtLeast(0),
                    ultimaActividadAt = ultima?.comoInstanteUtc(),
                    siguienteTarea = siguienteTarea[empresa.id],
                    listaParaConvertir = hitosCatalogo.isNotEmpty() && completados == hitosCatalogo.size,
                )
            }.sortedWith(
                compareByDescending<ProspeccionItemDto> { it.checkpointsCompletados }
                    .thenByDescending { it.diasSinActividad },
            )
    }

    private companion object {
        const val DIAS_ATENCION = 15
        const val LONGITUD_CORTA = 20
    }
}
