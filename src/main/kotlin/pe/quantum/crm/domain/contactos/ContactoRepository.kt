package pe.quantum.crm.domain.contactos

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

interface ContactoRepository :
    JpaRepository<Contacto, Long>,
    JpaSpecificationExecutor<Contacto>

interface EmpresaContactoRepository : JpaRepository<EmpresaContacto, EmpresaContactoId> {
    fun findByIdIdEmpresa(idEmpresa: Long): List<EmpresaContacto>

    fun findByIdIdContacto(idContacto: Long): List<EmpresaContacto>

    fun findByIdIdContactoIn(idsContacto: Collection<Long>): List<EmpresaContacto>

    fun existsByIdIdContacto(idContacto: Long): Boolean

    fun countByIdIdEmpresa(idEmpresa: Long): Long
}
