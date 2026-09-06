package pe.quantum.crm.domain.simulaciones

import org.springframework.stereotype.Service
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.simulaciones.dto.CalculadoraDto
import pe.quantum.crm.domain.simulaciones.dto.CalculadoraRequest
import pe.quantum.crm.domain.simulaciones.dto.CronogramaDto
import pe.quantum.crm.domain.simulaciones.dto.FilaCronogramaDto
import pe.quantum.crm.domain.simulaciones.dto.ModeloEnSimulacionDto
import pe.quantum.crm.shared.enums.ModoSimulacion
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import pe.quantum.crm.shared.simulacion.MotorSimulacion
import pe.quantum.crm.shared.simulacion.ParametrosSimulacion
import pe.quantum.crm.shared.simulacion.ResultadoSimulacion

/**
 * Calculadora Financiera (`reglas_simulaciones.md` §9): el mismo motor y las
 * mismas reglas que el flujo que persiste, sobre un calculo efimero.
 *
 * **El constructor recibe EXACTAMENTE tres colaboradores y ninguno es un
 * repositorio.** No es un olvido: es la garantia estructural de §9 (decision
 * D50 de plan-11-mapa-historial-calculadora.md). Sin `SimulacionRepository` ni
 * `SimulacionLogRepository` en el constructor, escribir aqui es imposible por
 * tipo, no por convencion ni por un test de `verify(exactly = 0)`.
 *
 * Si alguna vez se propone anadir auditoria, logging persistido o "solo un
 * registro de uso" a este servicio, **contradice §9 explicitamente** ("Cero
 * persistencia: no escribe en `simulaciones` ni en `simulacion_log`. No deja
 * rastro de auditoria"). No se resuelve inyectando el repositorio: se resuelve
 * cambiando §9 primero.
 *
 * Las validaciones §13 y los defaults NO se duplican: salen de
 * [ValidacionesSimulacion] y [DefaultsSimulacion], los objetos compartidos con
 * `SimulacionServiceImpl` (decision D51).
 */
@Service
class CalculadoraFinancieraServiceImpl(
    private val permisos: SimulacionPermisos,
    private val modeloService: ModeloService,
    private val empresaService: EmpresaService,
) : CalculadoraFinancieraService {
    /**
     * Sin `@Transactional` a proposito: no toca la base para escribir, y las
     * dos lecturas opcionales (empresa, modelo) las abre cada servicio dueno.
     * Una transaccion aqui sugeriria una unidad de trabajo que no existe.
     */
    override fun calcular(
        request: CalculadoraRequest,
        usuario: UsuarioActual,
    ): CalculadoraDto {
        // §10 / K26: la columna "Calculadora Financiera" es exactamente esta
        // regla ya existente — `jdv` y `otro` fuera, `vendedor` dentro.
        permisos.exigirAcceso(usuario)
        val modo = resolverModo(request.modo)

        // Mismos defaults que `crear` (D51): los literales viven en un solo sitio.
        val descuento = request.descuento ?: DefaultsSimulacion.DESCUENTO
        val valorResidual = request.valorResidual ?: DefaultsSimulacion.VALOR_RESIDUAL

        ValidacionesSimulacion.exigirCuotaInicialMenorQuePrecioEfectivo(
            request.precioVenta,
            descuento,
            request.cuotaInicial,
        )

        // Una sola pasada del motor (D35), igual que `crear`: `principal`
        // depende del modo y su formula ya vive alli, asi que la validacion
        // posterior lee su salida en vez de duplicarla.
        val resultado =
            MotorSimulacion.calcular(
                ParametrosSimulacion(
                    modo = modo,
                    precioVenta = request.precioVenta,
                    descuento = descuento,
                    cuotaInicial = request.cuotaInicial,
                    plazoMeses = request.plazoMeses,
                    tea = request.tea,
                    valorResidual = valorResidual,
                ),
            )
        ValidacionesSimulacion.exigirValorResidualMenorQuePrincipal(valorResidual, resultado)

        // Solo si vinieron: son datos de presentacion (§9, "puede jalar
        // opcionalmente una empresa o un modelo"), no entradas del calculo.
        val modelo = request.idModelo?.let { modeloService.resumen(it) }
        val empresa = request.idEmpresa?.let { resolverEmpresa(it) }

        return CalculadoraDto(
            empresa = empresa,
            modelo = modelo?.let { ModeloEnSimulacionDto(id = it.id, codigo = it.codigo) },
            cronograma = aCronograma(resultado),
        )
    }

    /**
     * `EmpresaService` solo expone la variante en lotes; no hay metodo para un
     * solo id. Se resuelve como ya lo hace `SimulacionServiceImpl.toDto`, y la
     * ausencia de la clave es un 404 explicito: sin esto el id inexistente
     * devolveria un `empresa: null` silencioso, indistinguible de "no se pidio".
     */
    private fun resolverEmpresa(idEmpresa: Long) =
        empresaService.resumenPorIds(listOf(idEmpresa))[idEmpresa]
            ?: throw NoEncontradoException("La empresa no existe")

    /**
     * Un `modo` fuera del enum es un error del cliente (400), no un 500: sin
     * este filtro `ModoSimulacion.valueOf` lanzaria `IllegalArgumentException`.
     *
     * Duplica deliberadamente el `resolverModo` privado de
     * `SimulacionServiceImpl` (E10 del plan-12): es una expresion de una linea
     * y extraerla a un tercer objeto compartido no compensa.
     */
    private fun resolverModo(modo: String): ModoSimulacion =
        ModoSimulacion.entries.firstOrNull { it.name == modo }
            ?: throw ValidacionException(
                "modo debe ser uno de: ${ModoSimulacion.entries.joinToString { it.name }}",
                field = "modo",
            )

    /**
     * Mismo mapeo que `SimulacionServiceImpl.cronograma` (decision D13 del
     * Plan D): importes como `String` para no perder precision decimal en JSON,
     * y la Tasa Nominal Mensual **sin redondear** (§3.1).
     */
    private fun aCronograma(resultado: ResultadoSimulacion): CronogramaDto =
        CronogramaDto(
            cuotaFinal = resultado.cuotaFinal.toPlainString(),
            cuotaFinanciera = resultado.cuotaFinanciera.toPlainString(),
            valorVenta = resultado.valorVenta.toPlainString(),
            igv = resultado.igv.toPlainString(),
            principal = resultado.principal.toPlainString(),
            // §3.1: la Tasa Nominal Mensual NUNCA se redondea. Tal cual sale del motor.
            tasaNominalMensual = resultado.tasaNominalMensual.toPlainString(),
            filas =
                resultado.cronograma.map { fila ->
                    FilaCronogramaDto(
                        mes = fila.mes,
                        saldoInicial = fila.saldoInicial.toPlainString(),
                        amortizacion = fila.amortizacion.toPlainString(),
                        interes = fila.interes?.toPlainString(),
                        igv = fila.igv?.toPlainString(),
                        saldoFinal = fila.saldoFinal.toPlainString(),
                        cuota = fila.cuota?.toPlainString(),
                        cuotaConIgv = fila.cuotaConIgv?.toPlainString(),
                    )
                },
        )
}
