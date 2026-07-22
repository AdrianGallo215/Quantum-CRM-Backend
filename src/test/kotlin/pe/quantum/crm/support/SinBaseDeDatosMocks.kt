package pe.quantum.crm.support

import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
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
import pe.quantum.crm.domain.oportunidades.OportunidadRepository
import pe.quantum.crm.domain.solicitudes.SolicitudRepository
import pe.quantum.crm.domain.tareas.TareaRepository

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
    fun eventoRepository(): EventoRepository = mockk(relaxed = true)

    @Bean
    fun tareaRepository(): TareaRepository = mockk(relaxed = true)

    @Bean
    fun notificacionRepository(): NotificacionRepository = mockk(relaxed = true)

    @Bean
    fun recordatorioEnviadoRepository(): RecordatorioEnviadoRepository = mockk(relaxed = true)

    @Bean
    fun solicitudRepository(): SolicitudRepository = mockk(relaxed = true)

    @Bean
    fun metaVentaRepository(): MetaVentaRepository = mockk(relaxed = true)

    @Bean
    fun namedParameterJdbcTemplate(): NamedParameterJdbcTemplate = mockk(relaxed = true)
}
