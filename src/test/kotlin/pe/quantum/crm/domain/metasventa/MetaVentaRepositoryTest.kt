package pe.quantum.crm.domain.metasventa

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import pe.quantum.crm.shared.enums.EstadoMeta
import pe.quantum.crm.support.IntegrationTestBase

@Tag("integration")
@SpringBootTest
class MetaVentaRepositoryTest
    @Autowired
    constructor(
        private val repository: MetaVentaRepository,
    ) : IntegrationTestBase() {
        // id_empleado=1 es el admin seed de V19 (activo, referenciable por FK).
        private fun metaPropuesta(anio: Int = 2099) =
            MetaVenta(idEmpleado = 1, anio = anio, idPropuestoPor = 1).apply {
                establecerMeses(List(12) { 10 })
            }

        @Test
        fun `persiste y recupera una meta propuesta con el anual calculado`() {
            val guardada = repository.save(metaPropuesta())
            val leida = repository.findById(requireNotNull(guardada.id)).orElseThrow()
            assertThat(leida.estado).isEqualTo(EstadoMeta.propuesta)
            assertThat(leida.metaAnual).isEqualTo(120)
            repository.delete(leida)
        }

        @Test
        fun `el indice unico rechaza dos filas del mismo empleado y anio`() {
            val primera = repository.save(metaPropuesta(anio = 2098))
            assertThatThrownBy {
                repository.saveAndFlush(metaPropuesta(anio = 2098))
            }.isInstanceOf(DataIntegrityViolationException::class.java)
            repository.delete(primera)
        }

        @Test
        fun `findByIdEmpleadoAndAnio recupera la fila correcta`() {
            val guardada = repository.save(metaPropuesta(anio = 2097))
            val encontrada = repository.findByIdEmpleadoAndAnio(1, 2097)
            assertThat(encontrada?.id).isEqualTo(guardada.id)
            repository.delete(guardada)
        }
    }
