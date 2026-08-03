package pe.quantum.crm.domain.empresas

/**
 * Se publica cuando `EmpresaServiceImpl.reasignarVendedor` cambia el vendedor de
 * una empresa. `OportunidadServiceImpl` lo escucha para cascadear el mismo
 * vendedor a las oportunidades activas de la empresa (reglas_negocio.md §8).
 */
data class VendedorEmpresaReasignadoEvent(
    val idEmpresa: Long,
    val idVendedorNuevo: Long,
    val idActor: Long,
)
