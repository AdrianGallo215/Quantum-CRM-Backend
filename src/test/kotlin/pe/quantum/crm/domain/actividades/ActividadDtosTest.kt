package pe.quantum.crm.domain.actividades

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.actividades.dto.ActividadDto
import pe.quantum.crm.domain.actividades.dto.CambioCampo
import java.time.Instant
import java.time.LocalDate

/**
 * El DTO unificado NO colapsa las fechas en un solo campo: `fechaHora` es para
 * columnas TIMESTAMP y `fechaDia` para columnas DATE. Mezclarlas obligaria a dar
 * una hora a un dia del calendario de Lima, que es justo lo que
 * `shared/TiempoUtc.kt` prohibe. Este test fija esa separacion para que nadie la
 * deshaga "simplificando" el DTO mas adelante.
 */
class ActividadDtosTest {
    @Test
    fun `una actividad de tipo tarea nunca lleva fecha de dia`() {
        val tarea = actividad(tipo = "tarea", fechaHora = Instant.parse("2026-09-08T15:00:00Z"), fechaDia = null)

        assertThat(tarea.fechaDia).isNull()
        assertThat(tarea.fechaHora).isNotNull()
    }

    @Test
    fun `una actividad de tipo evento puede llevar solo fecha de dia`() {
        val evento = actividad(tipo = "evento", fechaHora = null, fechaDia = LocalDate.of(2026, 9, 8))

        assertThat(evento.fechaHora).isNull()
        assertThat(evento.fechaDia).isEqualTo(LocalDate.of(2026, 9, 8))
    }

    @Test
    fun `un cambio de campo conserva el valor anterior y el nuevo por separado`() {
        val cambio = CambioCampo(campo = "descripcion", valorAnterior = "vieja", valorNuevo = "nueva")

        assertThat(cambio.valorAnterior).isEqualTo("vieja")
        assertThat(cambio.valorNuevo).isEqualTo("nueva")
    }

    private fun actividad(
        tipo: String,
        fechaHora: Instant?,
        fechaDia: LocalDate?,
    ) = ActividadDto(
        tipo = tipo,
        id = 1,
        titulo = "llamada",
        descripcion = null,
        estado = "pendiente",
        fechaHora = fechaHora,
        fechaDia = fechaDia,
        idEmpresa = 3,
        empresa = null,
        idOportunidad = null,
        idEmpleado = 7,
        empleado = null,
        comentarios = 0,
        createdAt = Instant.parse("2026-09-01T10:00:00Z"),
    )
}
