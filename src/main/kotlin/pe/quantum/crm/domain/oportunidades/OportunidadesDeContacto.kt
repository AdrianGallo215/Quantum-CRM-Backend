package pe.quantum.crm.domain.oportunidades

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.oportunidades.dto.ModeloEnOportunidadDto
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
    private val modeloService: ModeloService,
) : OportunidadesDeContacto {
    @Transactional(readOnly = true)
    override fun contar(
        idContacto: Long,
        usuario: UsuarioActual,
    ): Int = contactoOportunidadRepository.countVisiblesPorContacto(idContacto, usuario.filtroVendedor).toInt()

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
}
