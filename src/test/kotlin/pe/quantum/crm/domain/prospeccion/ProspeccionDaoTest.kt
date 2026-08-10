package pe.quantum.crm.domain.prospeccion

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDateTime

/**
 * Mapeo de filas de [ProspeccionDao]. El mock esta en la frontera del driver
 * (el `ResultSet`), no en la logica: lo que se ejerce es el codigo real que
 * construye el mapa. La consulta en si (el SQL) solo se puede verificar contra
 * una base real, en los tests `@Tag("integration")`.
 */
class ProspeccionDaoTest {
    private val jdbc = mockk<NamedParameterJdbcTemplate>()
    private val dao = ProspeccionDao(jdbc)

    private fun filaHito(
        idEmpresa: Long,
        idCatalogo: Long,
        fecha: Timestamp?,
    ): ResultSet {
        val rs = mockk<ResultSet>()
        every { rs.getLong("id_empresa") } returns idEmpresa
        every { rs.getLong("id_catalogo_evento") } returns idCatalogo
        every { rs.getTimestamp("fecha") } returns fecha
        return rs
    }

    /** Deja que el DAO arme la consulta y le entrega las filas indicadas. */
    private fun hitosOcurridosCon(filas: List<ResultSet>): Map<Pair<Long, Long>, LocalDateTime?> {
        val handler = slot<RowCallbackHandler>()
        every { jdbc.query(any<String>(), any<SqlParameterSource>(), capture(handler)) } answers {
            filas.forEach { handler.captured.processRow(it) }
        }
        return dao.hitosOcurridos(listOf(1L))
    }

    @Test
    fun `un hito ocurrido sin fecha de ocurrencia sigue contando como ocurrido`() {
        // El CHECK chk_evento_fecha_ocurrencia de V14 permite estado='ocurrido'
        // con fecha_ocurrencia NULL, asi que MAX(fecha_ocurrencia) puede ser NULL.
        val resultado = hitosOcurridosCon(listOf(filaHito(idEmpresa = 1L, idCatalogo = 7L, fecha = null)))

        assertThat(resultado).containsKey(1L to 7L)
        assertThat(resultado[1L to 7L]).isNull()
    }

    @Test
    fun `un hito ocurrido con fecha la conserva`() {
        val fecha = LocalDateTime.of(2026, 3, 14, 9, 30)

        val resultado = hitosOcurridosCon(listOf(filaHito(idEmpresa = 1L, idCatalogo = 7L, fecha = Timestamp.valueOf(fecha))))

        assertThat(resultado[1L to 7L]).isEqualTo(fecha)
    }

    @Test
    fun `sin ids de empresa no consulta la base`() {
        assertThat(dao.hitosOcurridos(emptyList())).isEmpty()
    }
}
