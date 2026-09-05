package pe.quantum.crm.support

import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import pe.quantum.crm.domain.catalogoeventos.CatalogoEventoRepository
import pe.quantum.crm.domain.contactos.ContactoRepository
import pe.quantum.crm.domain.contactos.EmpresaContactoRepository
import pe.quantum.crm.domain.empleados.EmpleadoRepository
import pe.quantum.crm.domain.empresas.EmpresaRepository
import pe.quantum.crm.domain.eventos.EventoRepository
import pe.quantum.crm.domain.financiadoras.FinanciadoraRepository
import pe.quantum.crm.domain.metasventa.MetaVentaRepository
import pe.quantum.crm.domain.modelos.ModeloRepository
import pe.quantum.crm.domain.notificaciones.NotificacionRepository
import pe.quantum.crm.domain.notificaciones.RecordatorioEnviadoRepository
import pe.quantum.crm.domain.oportunidades.OportunidadContactoRepository
import pe.quantum.crm.domain.oportunidades.OportunidadEstadoLogRepository
import pe.quantum.crm.domain.oportunidades.OportunidadItemRepository
import pe.quantum.crm.domain.oportunidades.OportunidadRepository
import pe.quantum.crm.domain.solicitudes.SolicitudRepository
import pe.quantum.crm.domain.tareas.TareaRepository
import pe.quantum.crm.domain.tareas.TareaResponsableRepository
import pe.quantum.crm.domain.tipocambio.TipoCambioRepository

/**
 * Mocks de la capa de datos para los tests de contexto que corren SIN base de
 * datos (excluyen DataSource/JPA/Flyway). Sin esto, los servicios de dominio no
 * pueden ensamblarse porque sus repositorios JPA no existen.
 */
@TestConfiguration
class SinBaseDeDatosMocks {
    @Bean
    fun empleadoRepository(): EmpleadoRepository = mockk(relaxed = true)

    @Bean
    fun modeloRepository(): ModeloRepository = mockk(relaxed = true)

    @Bean
    fun financiadoraRepository(): FinanciadoraRepository = mockk(relaxed = true)

    @Bean
    fun catalogoEventoRepository(): CatalogoEventoRepository = mockk(relaxed = true)

    @Bean
    fun empresaRepository(): EmpresaRepository = mockk(relaxed = true)

    @Bean
    fun contactoRepository(): ContactoRepository = mockk(relaxed = true)

    @Bean
    fun empresaContactoRepository(): EmpresaContactoRepository = mockk(relaxed = true)

    @Bean
    fun oportunidadRepository(): OportunidadRepository = mockk(relaxed = true)

    @Bean
    fun oportunidadEstadoLogRepository(): OportunidadEstadoLogRepository = mockk(relaxed = true)

    @Bean
    fun oportunidadContactoRepository(): OportunidadContactoRepository = mockk(relaxed = true)

    @Bean
    fun oportunidadItemRepository(): OportunidadItemRepository = mockk(relaxed = true)

    @Bean
    fun eventoRepository(): EventoRepository = mockk(relaxed = true)

    @Bean
    fun tareaRepository(): TareaRepository = mockk(relaxed = true)

    @Bean
    fun tareaResponsableRepository(): TareaResponsableRepository = mockk(relaxed = true)

    @Bean
    fun notificacionRepository(): NotificacionRepository = mockk(relaxed = true)

    @Bean
    fun recordatorioEnviadoRepository(): RecordatorioEnviadoRepository = mockk(relaxed = true)

    @Bean
    fun solicitudRepository(): SolicitudRepository = mockk(relaxed = true)

    @Bean
    fun metaVentaRepository(): MetaVentaRepository = mockk(relaxed = true)

    @Bean
    fun tipoCambioRepository(): TipoCambioRepository = mockk(relaxed = true)

    @Bean
    fun namedParameterJdbcTemplate(): NamedParameterJdbcTemplate = mockk(relaxed = true)

    /**
     * Sin DataSource no hay `PlatformTransactionManager` y, por tanto, Spring Boot
     * tampoco autoconfigura el `TransactionTemplate` que `EmpresaServiceImpl` usa
     * para abrir la transaccion del alta DESPUES de hablar con Drive. Estos slices
     * no tocan la base de datos: un gestor sin efecto basta para que el bloque se
     * ejecute igual.
     */
    @Bean
    fun transactionTemplate(): TransactionTemplate = TransactionTemplate(GestorSinTransaccion())
}

private class GestorSinTransaccion : PlatformTransactionManager {
    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()

    override fun commit(status: TransactionStatus) = Unit

    override fun rollback(status: TransactionStatus) = Unit
}
