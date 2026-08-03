package pe.quantum.crm.domain.notificaciones

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import pe.quantum.crm.support.IntegrationTestBase

@Tag("integration")
@SpringBootTest
class RecordatorioEnviadoRepositoryIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var repository: RecordatorioEnviadoRepository

    @Test
    fun `existsBy detecta un recordatorio ya registrado`() {
        repository.save(
            RecordatorioEnviado(
                origen = OrigenRecordatorio.tarea,
                idOrigen = 1,
                umbral = UmbralRecordatorio.proximo,
            ),
        )

        assertThat(
            repository.existsByOrigenAndIdOrigenAndUmbral(
                OrigenRecordatorio.tarea,
                1,
                UmbralRecordatorio.proximo,
            ),
        ).isTrue()
        assertThat(
            repository.existsByOrigenAndIdOrigenAndUmbral(
                OrigenRecordatorio.tarea,
                1,
                UmbralRecordatorio.vencido,
            ),
        ).isFalse()
        assertThat(
            repository.existsByOrigenAndIdOrigenAndUmbral(
                OrigenRecordatorio.evento,
                1,
                UmbralRecordatorio.proximo,
            ),
        ).isFalse()
    }

    @Test
    fun `el constraint unico rechaza un duplicado exacto`() {
        repository.saveAndFlush(
            RecordatorioEnviado(
                origen = OrigenRecordatorio.evento,
                idOrigen = 5,
                umbral = UmbralRecordatorio.vencido,
            ),
        )

        org.junit.jupiter.api.assertThrows<DataIntegrityViolationException> {
            repository.saveAndFlush(
                RecordatorioEnviado(
                    origen = OrigenRecordatorio.evento,
                    idOrigen = 5,
                    umbral = UmbralRecordatorio.vencido,
                ),
            )
        }
    }
}
