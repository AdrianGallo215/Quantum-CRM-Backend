package pe.quantum.crm.domain.oportunidades.dto

import java.math.BigDecimal

/**
 * Datos minimos de un item para chequeos de visibilidad cruzados con otros
 * modulos (plan-05-mapa-migrar-items.md, decision D14). Espejo de
 * `OportunidadVinculo`, pero a nivel de item: `solicitudes` lo necesita para
 * resolver el IDOR de una solicitud de descuento sin conocer la entidad JPA
 * (CLAUDE.md reglas 9 y 12).
 */
data class OportunidadItemVinculo(
    val id: Long,
    val idOportunidad: Long,
    val idEmpresa: Long,
    val descuento: BigDecimal?,
)
