package pe.quantum.crm.shared

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import pe.quantum.crm.domain.empresas.dto.EmpresaDetalleDto
import pe.quantum.crm.domain.eventos.dto.EventoDto
import pe.quantum.crm.domain.eventos.dto.EventoOcurridoDto
import pe.quantum.crm.domain.inicio.dto.EventoSeguimientoDto
import pe.quantum.crm.domain.inicio.dto.TareaInicioDto
import pe.quantum.crm.domain.metasventa.dto.MetaVentaDto
import pe.quantum.crm.domain.notificaciones.dto.NotificacionDto
import pe.quantum.crm.domain.oportunidades.dto.LogEstadoDto
import pe.quantum.crm.domain.oportunidades.dto.OportunidadDto
import pe.quantum.crm.domain.prospeccion.dto.HitoDto
import pe.quantum.crm.domain.prospeccion.dto.ProspeccionItemDto
import pe.quantum.crm.domain.solicitudes.dto.SolicitudDto
import pe.quantum.crm.domain.tareas.dto.ActividadContactoDto
import pe.quantum.crm.domain.tareas.dto.TareaDto
import pe.quantum.crm.support.SinBaseDeDatosMocks
import java.time.Instant
import java.time.LocalDate

/**
 * Formato de fechas del contrato (contrato_api.md §1):
 *
 *   Fechas: ISO 8601 — "2026-06-19T14:30:00Z" para timestamps, "2026-06-19" para fechas
 *
 * Dos clases de campo, dos formatos, y confundirlos cuesta cinco horas:
 *
 *  - Un TIMESTAMP es un instante. Sale con `Z`. Serializado como `LocalDateTime`
 *    llegaria al navegador sin zona, y `new Date("2026-06-19T14:00:00")` lo lee
 *    como hora LOCAL por spec de ECMAScript: en Lima (UTC-5) pinta las 14:00
 *    donde el instante real son las 09:00.
 *  - Un DATE es un dia del calendario (`fecha_estimada`, `fecha_seguimiento`,
 *    `fecha_cierre_estimado`). Sale como "2026-06-19" y NO se convierte a
 *    instante: darle una hora le inventaria una zona y reintroduciria el mismo
 *    bug por el otro lado.
 *
 * El ObjectMapper es el de la aplicacion (contexto real), no uno construido a
 * mano: un `standaloneSetup` serializaria en camelCase y no detectaria nada.
 */
@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    ],
)
@Import(SinBaseDeDatosMocks::class)
class FormatoFechasContratoTest {
    @Autowired
    lateinit var objectMapper: ObjectMapper

    private companion object {
        /** 2026-06-19T14:00:00Z: el instante que el contrato usa en sus ejemplos. */
        val TS: Instant = Instant.parse("2026-06-19T14:00:00Z")
        val DIA: LocalDate = LocalDate.of(2026, 6, 19)
        const val INSTANTE_JSON = "2026-06-19T14:00:00Z"
        const val DIA_JSON = "2026-06-19"
    }

    private fun json(dto: Any): String = objectMapper.writeValueAsString(dto)

    // ── Tareas (contrato §12) ───────────────────────────────────────────

    @Test
    fun `TareaDto expone fecha_ejecucion y created_at como instante UTC`() {
        val dto =
            TareaDto(
                id = 12, idEmpresa = 3, empresa = null, idOportunidad = null, idContacto = null,
                contacto = null, idAsignado = 7, asignado = null, idsColaboradores = emptyList(),
                colaboradores = emptyList(), tipoAccion = "llamada", estadoAccion = "pendiente",
                descripcion = null, fechaEjecucion = TS, createdAt = TS,
            )

        assertThat(json(dto))
            .contains("\"fecha_ejecucion\":\"$INSTANTE_JSON\"")
            .contains("\"created_at\":\"$INSTANTE_JSON\"")
    }

    @Test
    fun `ActividadContactoDto expone fecha como instante UTC`() {
        val dto =
            ActividadContactoDto(
                id = 1,
                titulo = "llamada",
                descripcion = null,
                fecha = TS,
                estado = "pendiente",
            )

        assertThat(json(dto)).contains("\"fecha\":\"$INSTANTE_JSON\"")
    }

    // ── Eventos (contrato §11) ──────────────────────────────────────────

    @Test
    fun `EventoDto separa el instante de ocurrencia de los dias de calendario`() {
        val dto =
            EventoDto(
                id = 1, idOportunidad = 2, idEmpresa = null, idCatalogoEvento = 3, nombre = "Reunion",
                esPersonalizado = false, descripcion = null, estado = "ocurrido",
                fechaEstimada = DIA, fechaSeguimiento = DIA, fechaOcurrencia = TS,
                disparaCambioEstado = false, estadoDestino = null, esRecomendado = false,
                etapaAsociada = null, esHitoProspeccion = false,
            )

        assertThat(json(dto))
            .contains("\"fecha_ocurrencia\":\"$INSTANTE_JSON\"")
            // Columnas DATE de V14: dia del calendario, sin hora ni zona.
            .contains("\"fecha_estimada\":\"$DIA_JSON\"")
            .contains("\"fecha_seguimiento\":\"$DIA_JSON\"")
    }

    @Test
    fun `EventoOcurridoDto expone fecha_ocurrencia como instante UTC`() {
        val dto = EventoOcurridoDto(id = 1, estado = "ocurrido", fechaOcurrencia = TS, sugerencia = null)

        assertThat(json(dto)).contains("\"fecha_ocurrencia\":\"$INSTANTE_JSON\"")
    }

    // ── Oportunidades (contrato §10) ────────────────────────────────────

    @Test
    fun `OportunidadDto expone created_at y entrada_etapa_actual como instante UTC`() {
        val dto =
            OportunidadDto(
                id = 1, idEmpresa = 3, empresa = null, idVendedor = 7, vendedor = null,
                idFinanciadora = 1, financiadora = null,
                estado = "evaluacion_calidda", items = emptyList(),
                montoTotal = null, garantia = null, fincParalelo = null, fichaVenta = null,
                driveFolderId = null, notas = null, motivoCierre = null, fechaCierreEstimado = DIA,
                createdAt = TS, entradaEtapaActual = TS,
            )

        assertThat(json(dto))
            .contains("\"created_at\":\"$INSTANTE_JSON\"")
            .contains("\"entrada_etapa_actual\":\"$INSTANTE_JSON\"")
            // Columna DATE de V10: no se toca.
            .contains("\"fecha_cierre_estimado\":\"$DIA_JSON\"")
    }

    @Test
    fun `LogEstadoDto expone changed_at como instante UTC`() {
        val dto =
            LogEstadoDto(
                estadoAnterior = null,
                estadoNuevo = "evaluacion_calidda",
                changedAt = TS,
                changedBy = null,
            )

        assertThat(json(dto)).contains("\"changed_at\":\"$INSTANTE_JSON\"")
    }

    // ── Empresas (contrato §8) ──────────────────────────────────────────

    @Test
    fun `EmpresaDetalleDto expone created_at como instante UTC`() {
        val dto =
            EmpresaDetalleDto(
                id = 3, ruc = "20260426827", razonSocial = "Transportes ABC", actividadEcon = null,
                ciiu = null, sectorIndustrial = null, estadoSunat = null, condicionSunat = null,
                direccionFiscal = null, ubicacionReal = null, distrito = null, provincia = null,
                departamento = null, avalFiador = null, origenLead = null, estadoCartera = "prospeccion",
                fileDrive = null, driveFolderId = null, sitioWeb = null, notas = null, idVendedor = null,
                vendedor = null, segmentos = emptyList(), contactos = null, createdAt = TS, createdBy = 1,
            )

        assertThat(json(dto)).contains("\"created_at\":\"$INSTANTE_JSON\"")
    }

    // ── Solicitudes (contrato §20) y metas de venta (§21) ───────────────

    @Test
    fun `SolicitudDto expone created_at y resolved_at como instante UTC`() {
        val dto =
            SolicitudDto(
                id = 1, tipo = "descuento", estado = "aprobada", rolAprobador = "gerencia",
                entidadTipo = "oportunidad", entidadId = 5, entidadDescripcion = "Transportes ABC",
                dctoSolicitado = "5.00", idVendedorNuevo = null, vendedorNuevo = null, motivo = "x",
                solicitante = null, resolutor = null, motivoResolucion = null,
                resolvedAt = TS, createdAt = TS,
            )

        assertThat(json(dto))
            .contains("\"resolved_at\":\"$INSTANTE_JSON\"")
            .contains("\"created_at\":\"$INSTANTE_JSON\"")
    }

    @Test
    fun `MetaVentaDto expone created_at y resolved_at como instante UTC`() {
        val dto =
            MetaVentaDto(
                id = 1, idEmpleado = 7, empleado = null, anio = 2026,
                metaEnero = 1, metaFebrero = 1, metaMarzo = 1, metaAbril = 1, metaMayo = 1, metaJunio = 1,
                metaJulio = 1, metaAgosto = 1, metaSeptiembre = 1, metaOctubre = 1, metaNoviembre = 1,
                metaDiciembre = 1, metaAnual = 12, estado = "aprobada", propuestoPor = null,
                resolutor = null, motivoRechazo = null, resolvedAt = TS, createdAt = TS,
            )

        assertThat(json(dto))
            .contains("\"resolved_at\":\"$INSTANTE_JSON\"")
            .contains("\"created_at\":\"$INSTANTE_JSON\"")
    }

    // ── Notificaciones (contrato §19) ───────────────────────────────────

    @Test
    fun `NotificacionDto expone created_at como instante UTC`() {
        val dto =
            NotificacionDto(
                id = 1,
                tipo = "tarea_creada",
                mensaje = "x",
                entidadTipo = "empresa",
                entidadId = 3,
                leida = false,
                createdAt = TS,
                actor = null,
            )

        assertThat(json(dto)).contains("\"created_at\":\"$INSTANTE_JSON\"")
    }

    // ── Inicio (contrato §17) ───────────────────────────────────────────

    @Test
    fun `TareaInicioDto expone fecha_ejecucion como instante UTC`() {
        val dto =
            TareaInicioDto(
                id = 1, descripcion = null, tipoAccion = "llamada", fechaEjecucion = TS,
                estaVencida = false, esHoy = true, empresa = null, idOportunidad = null, contacto = null,
            )

        assertThat(json(dto)).contains("\"fecha_ejecucion\":\"$INSTANTE_JSON\"")
    }

    @Test
    fun `EventoSeguimientoDto mantiene fecha_seguimiento como dia de calendario`() {
        val dto =
            EventoSeguimientoDto(
                id = 1,
                nombre = "Reunion",
                fechaSeguimiento = DIA,
                seguimientoVencido = false,
                disparaCambioEstado = false,
                empresa = null,
                idOportunidad = null,
            )

        assertThat(json(dto)).contains("\"fecha_seguimiento\":\"$DIA_JSON\"")
    }

    // ── Prospeccion (contrato §16) ──────────────────────────────────────

    @Test
    fun `HitoDto y ProspeccionItemDto exponen sus timestamps como instante UTC`() {
        val item =
            ProspeccionItemDto(
                idEmpresa = 6, ruc = "20513480441", razonSocial = "Consorcio", corta = "Consorcio",
                distrito = null, segmentos = emptyList(), contactoPrincipal = null,
                checkpointsCompletados = 1, checkpointsTotal = 3,
                hitos = listOf(HitoDto(nombre = "Reporte Tributario recibido", completado = true, fecha = TS)),
                diasSinActividad = 8, ultimaActividadAt = TS, siguienteTarea = null,
                listaParaConvertir = false,
            )

        assertThat(json(item))
            .contains("\"fecha\":\"$INSTANTE_JSON\"")
            .contains("\"ultima_actividad_at\":\"$INSTANTE_JSON\"")
    }
}
