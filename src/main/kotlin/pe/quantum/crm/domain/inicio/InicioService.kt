package pe.quantum.crm.domain.inicio

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.RolEmpleado
import pe.quantum.crm.domain.inicio.dto.EtapaResumenDto
import pe.quantum.crm.domain.inicio.dto.InicioDto
import pe.quantum.crm.domain.inicio.dto.MedidorMetaDto
import pe.quantum.crm.domain.inicio.dto.MetaVentaAgregadoDto
import pe.quantum.crm.domain.inicio.dto.MetaVentaInicioDto
import pe.quantum.crm.domain.inicio.dto.ResumenPipelineDto
import pe.quantum.crm.domain.inicio.dto.TareaInicioDto
import pe.quantum.crm.domain.metasventa.MetaVentaService
import pe.quantum.crm.domain.metasventa.dto.MetaVentaResumen
import pe.quantum.crm.domain.prospeccion.ProspeccionService
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.domain.tareas.dto.TareaFiltros
import pe.quantum.crm.shared.enums.EstadoAccion
import pe.quantum.crm.shared.enums.EstadoOportunidad
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt

/**
 * Panel de inicio (contrato_api.md §17): una sola llamada agrega tareas
 * pendientes, eventos por seguir y los resumenes de pipeline y prospeccion.
 * Cada bloque se resuelve con consultas por lote (sin N+1).
 */
@Service
class InicioService(
    private val tareaService: TareaService,
    private val prospeccionService: ProspeccionService,
    private val inicioDao: InicioDao,
    private val empleadoService: EmpleadoService,
    private val metaVentaService: MetaVentaService,
) {
    @Transactional(readOnly = true)
    fun panel(usuario: UsuarioActual): InicioDto =
        InicioDto(
            tareasPendientes = tareasPendientes(usuario),
            eventosPorSeguir = inicioDao.eventosPorSeguir(usuario.id.takeIf { usuario.visibilidadRestringida }),
            resumenPipeline = resumenPipeline(usuario),
            resumenProspeccion = prospeccionService.resumen(usuario),
            metaVentas = metaVentas(usuario),
        )

    private fun tareasPendientes(usuario: UsuarioActual): List<TareaInicioDto> {
        val tareas =
            tareaService
                .listar(
                    filtros = TareaFiltros(estadoAccion = EstadoAccion.pendiente.name),
                    usuario = usuario,
                    page = 1,
                    perPage = LIMITE_TAREAS,
                    sort = "fechaEjecucion",
                    dir = "asc",
                ).items
        val hoy = LocalDate.now()
        val ahora = Instant.now()
        return tareas.map { tarea ->
            TareaInicioDto(
                id = tarea.id,
                descripcion = tarea.descripcion,
                tipoAccion = tarea.tipoAccion,
                fechaEjecucion = tarea.fechaEjecucion,
                estaVencida = tarea.fechaEjecucion?.isBefore(ahora) ?: false,
                // `hoy` es el dia UTC (la JVM corre con TZ=UTC): mismo reloj que el instante.
                esHoy = tarea.fechaEjecucion?.atOffset(ZoneOffset.UTC)?.toLocalDate() == hoy,
                empresa = tarea.empresa,
                idOportunidad = tarea.idOportunidad,
                contacto = tarea.contacto,
            )
        }
    }

    private fun resumenPipeline(usuario: UsuarioActual): ResumenPipelineDto {
        val filas = inicioDao.resumenPipeline(usuario.id.takeIf { usuario.visibilidadRestringida })
        val porEtapa =
            filas.associate { fila ->
                fila.etapa to
                    EtapaResumenDto(
                        count = fila.count,
                        valor = fila.valor.toPlainString(),
                        cantidadUnidades = fila.cantidadUnidades,
                    )
            }
        // "Activas" = en etapas previas al cierre (evaluacion + documentos).
        val filasActivas = filas.filter { it.etapa != EstadoOportunidad.facturado.name }
        val activas = filasActivas.sumOf { it.count }
        val valorActivo = filasActivas.fold(BigDecimal.ZERO) { acc, fila -> acc + fila.valor }
        val unidadesActivas = filasActivas.sumOf { it.cantidadUnidades }
        return ResumenPipelineDto(
            valorTotal = valorActivo.toPlainString(),
            oportunidadesActivas = activas,
            cantidadUnidades = unidadesActivas,
            porEtapa = porEtapa,
        )
    }

    /** Solo vendedor/jdv tienen meta de venta; el resto no vende. */
    private fun metaVentas(usuario: UsuarioActual): MetaVentaInicioDto? {
        if (usuario.rol != "vendedor" && usuario.rol != "jdv") return null
        val hoy = LocalDate.now()
        val anio = hoy.year
        val mes = hoy.monthValue
        val metaPropia = metaVentaService.aprobadasPorEmpleadosYAnio(listOf(usuario.id), anio)[usuario.id]
        val logradasAnual = inicioDao.unidadesFacturadasPorVendedor(listOf(usuario.id), anio, null)[usuario.id] ?: 0
        val logradasMes = inicioDao.unidadesFacturadasPorVendedor(listOf(usuario.id), anio, mes)[usuario.id] ?: 0
        return MetaVentaInicioDto(
            mensual = medidor(metaPropia?.metaPorMes?.get(mes - 1), logradasMes),
            anual = medidor(metaPropia?.metaAnual, logradasAnual),
            equipo = if (usuario.rol == "jdv") equipoMetaVentas(anio, mes) else null,
        )
    }

    /** Agregado del equipo de vendedores activos: solo cuenta metas `aprobada`. */
    private fun equipoMetaVentas(
        anio: Int,
        mes: Int,
    ): MetaVentaAgregadoDto {
        val idsVendedores = empleadoService.idsActivosPorRol(RolEmpleado.vendedor)
        val metas: Map<Long, MetaVentaResumen> = metaVentaService.aprobadasPorEmpleadosYAnio(idsVendedores, anio)
        val logradasAnualPorVendedor = inicioDao.unidadesFacturadasPorVendedor(idsVendedores, anio, null)
        val logradasMesPorVendedor = inicioDao.unidadesFacturadasPorVendedor(idsVendedores, anio, mes)
        val metaAnualTotal = metas.values.sumOf { it.metaAnual }.takeIf { metas.isNotEmpty() }
        val metaMesTotal = metas.values.sumOf { it.metaPorMes[mes - 1] }.takeIf { metas.isNotEmpty() }
        val logradasAnualTotal = metas.keys.sumOf { logradasAnualPorVendedor[it] ?: 0 }
        val logradasMesTotal = metas.keys.sumOf { logradasMesPorVendedor[it] ?: 0 }
        return MetaVentaAgregadoDto(
            mensual = medidor(metaMesTotal, logradasMesTotal),
            anual = medidor(metaAnualTotal, logradasAnualTotal),
        )
    }

    private fun medidor(
        meta: Int?,
        logradas: Int,
    ): MedidorMetaDto =
        if (meta == null) {
            MedidorMetaDto(tieneMeta = false, unidadesMeta = null, unidadesLogradas = logradas, porcentaje = null)
        } else {
            MedidorMetaDto(
                tieneMeta = true,
                unidadesMeta = meta,
                unidadesLogradas = logradas,
                porcentaje = ((logradas * PORCENTAJE_BASE) / meta).roundToInt(),
            )
        }

    private companion object {
        const val LIMITE_TAREAS = 50
        const val PORCENTAJE_BASE = 100.0
    }
}
