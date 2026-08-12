package pe.quantum.crm.domain.notificaciones

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.support.IntegrationTestBase

@Tag("integration")
@SpringBootTest
@Transactional
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
    fun `deleteBy borra los dos umbrales de un origen y respeta a los demas`() {
        // Es lo que permite que una tarea reprogramada vuelva a recordarse: la
        // clave de dedup no incluye la fecha, asi que el reinicio la borra entera.
        listOf(UmbralRecordatorio.proximo, UmbralRecordatorio.vencido).forEach {
            repository.save(RecordatorioEnviado(origen = OrigenRecordatorio.tarea, idOrigen = 42, umbral = it))
        }
        repository.save(RecordatorioEnviado(origen = OrigenRecordatorio.tarea, idOrigen = 43, umbral = UmbralRecordatorio.proximo))
        repository.save(RecordatorioEnviado(origen = OrigenRecordatorio.evento, idOrigen = 42, umbral = UmbralRecordatorio.proximo))

        val borradas = repository.deleteByOrigenAndIdOrigen(OrigenRecordatorio.tarea, 42)

        assertThat(borradas).isEqualTo(2)
        assertThat(repository.existsByOrigenAndIdOrigenAndUmbral(OrigenRecordatorio.tarea, 42, UmbralRecordatorio.proximo)).isFalse()
        assertThat(repository.existsByOrigenAndIdOrigenAndUmbral(OrigenRecordatorio.tarea, 42, UmbralRecordatorio.vencido)).isFalse()
        // Otra tarea y otro origen con el mismo id quedan intactos.
        assertThat(repository.existsByOrigenAndIdOrigenAndUmbral(OrigenRecordatorio.tarea, 43, UmbralRecordatorio.proximo)).isTrue()
        assertThat(repository.existsByOrigenAndIdOrigenAndUmbral(OrigenRecordatorio.evento, 42, UmbralRecordatorio.proximo)).isTrue()
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
