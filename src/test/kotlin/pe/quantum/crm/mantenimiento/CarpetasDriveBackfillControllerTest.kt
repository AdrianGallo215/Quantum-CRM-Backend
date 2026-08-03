package pe.quantum.crm.mantenimiento

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import pe.quantum.crm.mantenimiento.dto.BackfillCarpetasDto
import pe.quantum.crm.mantenimiento.dto.ErrorBackfillDto
import pe.quantum.crm.shared.GlobalExceptionHandler

/**
 * La restriccion a `admin` la aplica `@PreAuthorize`, que necesita el contexto de
 * Spring Security completo; aqui se prueba el enrutamiento y el envelope. La
 * verificacion del 403 va en el test de contexto (ver Step 5).
 */
class CarpetasDriveBackfillControllerTest {
    private val backfillService = mockk<CarpetasDriveBackfillService>()

    private val mockMvc =
        MockMvcBuilders
            .standaloneSetup(CarpetasDriveBackfillController(backfillService))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

    @Test
    fun `sin tamano_lote procesa todo y devuelve los conteos`() {
        every { backfillService.ejecutar(null) } returns
            BackfillCarpetasDto(
                empresasProcesadas = 12,
                oportunidadesProcesadas = 30,
                errores = emptyList(),
                pendientesRestantes = 0,
            )

        mockMvc
            .perform(post("/api/v1/mantenimiento/carpetas-drive"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.empresasProcesadas").value(12))
            .andExpect(jsonPath("$.data.oportunidadesProcesadas").value(30))
            .andExpect(jsonPath("$.data.pendientesRestantes").value(0))

        verify { backfillService.ejecutar(null) }
    }

    @Test
    fun `con tamano_lote lo propaga al servicio`() {
        every { backfillService.ejecutar(25) } returns
            BackfillCarpetasDto(
                empresasProcesadas = 25,
                oportunidadesProcesadas = 0,
                errores = emptyList(),
                pendientesRestantes = 17,
            )

        mockMvc
            .perform(post("/api/v1/mantenimiento/carpetas-drive").param("tamano_lote", "25"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.pendientesRestantes").value(17))

        verify { backfillService.ejecutar(25) }
    }

    @Test
    fun `expone los errores por registro sin fallar la respuesta`() {
        every { backfillService.ejecutar(null) } returns
            BackfillCarpetasDto(
                empresasProcesadas = 1,
                oportunidadesProcesadas = 0,
                errores = listOf(ErrorBackfillDto(entidad = "empresa", id = 7, motivo = "Drive caido")),
                pendientesRestantes = 1,
            )

        mockMvc
            .perform(post("/api/v1/mantenimiento/carpetas-drive"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.errores[0].entidad").value("empresa"))
            .andExpect(jsonPath("$.data.errores[0].id").value(7))
            .andExpect(jsonPath("$.data.errores[0].motivo").value("Drive caido"))
    }
}
