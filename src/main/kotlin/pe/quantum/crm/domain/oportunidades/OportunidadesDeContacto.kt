package pe.quantum.crm.domain.oportunidades

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.oportunidades.dto.OportunidadResumenParaContacto
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Proyeccion de oportunidades para la vista de contacto (contrato_api.md §9).
 *
 * Vive fuera de `OportunidadService` porque no es pipeline: es una lectura de
 * presentacion que consume el modulo contactos, y el nucleo del pipeline ya estaba
 * en su limite de tamaño.
 *
 * Los contactos son globales (busqueda para vincular, matriz_permisos.md §1), pero
 * las oportunidades que cuelgan de ellos NO: se filtran por vendedor igual que
 * `listar` y `detalle`, porque la visibilidad aplica a listado Y detalle sin
 * excepcion. Sin ese filtro bastaba enumerar contactos para volcar el pipeline
 * completo de todos los vendedores, montos incluidos.
 */
interface OportunidadesDeContacto {
    /** Cuantas oportunidades del contacto alcanza `usuario` (listado de contactos). */
    fun contar(
        idContacto: Long,
        usuario: UsuarioActual,
    ): Int

    /** Conteo por lote del listado de contactos: una consulta para toda la pagina. */
    fun contarPorContactos(
        idsContacto: Collection<Long>,
        usuario: UsuarioActual,
    ): Map<Long, Int>

    /** Oportunidades del contacto que `usuario` puede ver (detalle de contacto). */
    fun listar(
        idContacto: Long,
        usuario: UsuarioActual,
    ): List<OportunidadResumenParaContacto>
}

@Service
class OportunidadesDeContactoImpl(
    private val oportunidadRepository: OportunidadRepository,
    private val contactoOportunidadRepository: OportunidadContactoRepository,
    private val empresaService: EmpresaService,
    private val oportunidadItemService: OportunidadItemService,
) : OportunidadesDeContacto {
    @Transactional(readOnly = true)
    override fun contar(
        idContacto: Long,
        usuario: UsuarioActual,
    ): Int = contactoOportunidadRepository.countVisiblesPorContacto(idContacto, usuario.filtroVendedor).toInt()

    @Transactional(readOnly = true)
    override fun contarPorContactos(
        idsContacto: Collection<Long>,
        usuario: UsuarioActual,
    ): Map<Long, Int> {
        if (idsContacto.isEmpty()) {
            return emptyMap()
        }
        return contactoOportunidadRepository
            .contarVisiblesPorContactos(idsContacto.toSet(), usuario.filtroVendedor)
            .associate { it.idContacto to it.total.toInt() }
    }

    @Transactional(readOnly = true)
    override fun listar(
        idContacto: Long,
        usuario: UsuarioActual,
    ): List<OportunidadResumenParaContacto> {
        val vinculos = contactoOportunidadRepository.findByIdIdContacto(idContacto)
        if (vinculos.isEmpty()) {
            return emptyList()
        }
        val idsOportunidad = vinculos.map { it.id.idOportunidad }
        // Lo que no entra en este mapa desaparece del `mapNotNull` de abajo.
        val visibles = oportunidadRepository.findAllById(idsOportunidad).filter { usuario.alcanza(it.idVendedor) }
        val oportunidades = visibles.associateBy { requireNotNull(it.id) }
        val empresas = empresaService.resumenPorIds(oportunidades.values.map { it.idEmpresa })
        // Item mas antiguo por oportunidad = el modelo "principal" a mostrar:
        // `porOportunidades` ya viene ordenado por id ascendente.
        val itemsPorOportunidad = oportunidadItemService.porOportunidades(oportunidades.keys)
        val montosPorOportunidad = oportunidadItemService.montoTotalPorOportunidades(oportunidades.keys)
        return vinculos.mapNotNull { vinculo ->
            oportunidades[vinculo.id.idOportunidad]?.let { op ->
                val idOp = requireNotNull(op.id)
                OportunidadResumenParaContacto(
                    id = idOp,
                    empresa = empresas[op.idEmpresa],
                    modelo = itemsPorOportunidad[idOp]?.firstOrNull()?.modelo,
                    estado = op.estado.name,
                    // D19: `monto_total` es la suma de los items, nunca la columna plana.
                    // Si todos los items estan incompletos no hay monto: `null`, igual
                    // que `OportunidadServiceImpl.toDtos()`.
                    montoTotal = montosPorOportunidad[idOp]?.toPlainString(),
                    fechaCierreEstimado = op.fechaCierreEstimado,
                    rolEnOportunidad = vinculo.rolEnOportunidad,
                )
            }
        }
    }
}
