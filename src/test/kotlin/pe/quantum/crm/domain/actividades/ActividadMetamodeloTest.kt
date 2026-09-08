package pe.quantum.crm.domain.actividades

import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Las entidades del modulo actividades resuelven contra un metamodelo real de
 * Hibernate. No hace falta base de datos (Testcontainers esta roto en local):
 * con el dialecto fijado, Hibernate arranca sin DataSource y eso basta para
 * resolver atributos. Un nombre de atributo mal escrito reventaria en cada
 * lectura en produccion; aqui revienta en el build.
 */
class ActividadMetamodeloTest {
    @Test
    fun `los atributos de ActividadComentario existen en el metamodelo`() {
        val cb = emf.criteriaBuilder
        val query = cb.createQuery(ActividadComentario::class.java)
        val root = query.from(ActividadComentario::class.java)

        assertThatCode {
            root.get<Long>("idTarea")
            root.get<Long>("idEvento")
            root.get<String>("texto")
            root.get<LocalDateTime>("createdAt")
            root.get<Long>("createdBy")
        }.doesNotThrowAnyException()
    }

    @Test
    fun `los atributos de ActividadAuditoria existen en el metamodelo`() {
        val cb = emf.criteriaBuilder
        val query = cb.createQuery(ActividadAuditoria::class.java)
        val root = query.from(ActividadAuditoria::class.java)

        assertThatCode {
            root.get<Long>("idTarea")
            root.get<Long>("idEvento")
            root.get<String>("campo")
            root.get<String>("valorAnterior")
            root.get<String>("valorNuevo")
            root.get<LocalDateTime>("changedAt")
            root.get<Long>("changedBy")
        }.doesNotThrowAnyException()
    }

    @Test
    fun `el tipo de actividad tiene exactamente los dos valores del contrato`() {
        assertThat(TipoActividad.entries.map { it.name })
            .containsExactly("tarea", "evento")
    }

    companion object {
        private val emf: EntityManagerFactory =
            MetadataSources(
                StandardServiceRegistryBuilder()
                    .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                    .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                    .applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false")
                    .build(),
            ).addAnnotatedClass(ActividadComentario::class.java)
                .addAnnotatedClass(ActividadAuditoria::class.java)
                .buildMetadata()
                .buildSessionFactory()

        @JvmStatic
        @AfterAll
        fun cerrarEmf() {
            emf.close()
        }
    }
}
