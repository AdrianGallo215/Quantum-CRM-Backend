package pe.quantum.crm.domain.simulaciones

import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.modelos.dto.ModeloResumen
import pe.quantum.crm.domain.oportunidades.OportunidadItemService
import pe.quantum.crm.domain.oportunidades.dto.OportunidadItemParaSimulacion
import pe.quantum.crm.domain.simulaciones.dto.ActualizarSimulacionRequest
import pe.quantum.crm.domain.simulaciones.dto.BifurcarSimulacionRequest
import pe.quantum.crm.domain.simulaciones.dto.CrearSimulacionRequest
import pe.quantum.crm.domain.simulaciones.dto.CronogramaDto
import pe.quantum.crm.domain.simulaciones.dto.EventoHistorialDto
import pe.quantum.crm.domain.simulaciones.dto.FilaCronogramaDto
import pe.quantum.crm.domain.simulaciones.dto.ModeloEnSimulacionDto
import pe.quantum.crm.domain.simulaciones.dto.SimulacionDto
import pe.quantum.crm.domain.simulaciones.dto.SimulacionFiltros
import pe.quantum.crm.shared.CamposOrdenables
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.comoInstanteUtc
import pe.quantum.crm.shared.enums.ModoSimulacion
import pe.quantum.crm.shared.enums.TipoEventoSimulacion
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import pe.quantum.crm.shared.simulacion.MotorSimulacion
import pe.quantum.crm.shared.simulacion.ParametrosSimulacion
import java.time.LocalDateTime

/**
 * Implementacion del CRUD de simulaciones (`reglas_simulaciones.md`).
 *
 * El motor de calculo vive en `shared/simulacion/` y NO se toca desde aqui: es
 * una funcion pura que consumen dos flujos, este —que persiste— y la Calculadora
 * Financiera, que no persiste nada (§9). Este servicio solo lo invoca, valida su
 * salida contra §13 y guarda `cuota_final`, el unico derivado persistido (§4).
 *
 * Toda decision de autorizacion pasa por [SimulacionPermisos]: §10 exige un
 * unico punto de decision, y la visibilidad de este modulo NO es la de
 * `oportunidades` (`analista` tiene acceso total aqui pese a ser rol de apoyo
 * alli; `jdv` no tiene ninguno pese a ser supervisor alli).
 */
@Service
// Los 6 metodos del CRUD que exige `SimulacionService` mas los privados que
// parten `crear` por responsabilidad. Mismo precedente que
// `OportunidadItemServiceImpl`: fundirlos en menos funciones haria a `crear`
// mas larga y menos legible, no mas simple.
@Suppress("TooManyFunctions")
class SimulacionServiceImpl(
    private val simulacionRepository: SimulacionRepository,
    private val simulacionLogRepository: SimulacionLogRepository,
    private val permisos: SimulacionPermisos,
    private val oportunidadItemService: OportunidadItemService,
    private val modeloService: ModeloService,
    private val empresaService: EmpresaService,
) : SimulacionService {
    @Transactional
    override fun crear(
        request: CrearSimulacionRequest,
        usuario: UsuarioActual,
    ): SimulacionDto {
        permisos.exigirAcceso(usuario)
        val modo = resolverModo(request.modo)
        val item = resolverItem(request.idOportunidadItem, usuario)
        val idModelo = request.idModelo ?: item?.idModelo
        val modelo = idModelo?.let { modeloService.resumen(it) }

        // Defaults de §6.1 sobre lo que el request deja en null.
        val descuento = request.descuento ?: DefaultsSimulacion.DESCUENTO
        val valorResidual = request.valorResidual ?: DefaultsSimulacion.VALOR_RESIDUAL
        val diasTrabajados = request.diasTrabajados ?: DefaultsSimulacion.DIAS_TRABAJADOS
        val comisionEstructuracion = request.comisionEstructuracion ?: DefaultsSimulacion.COMISION_ESTRUCTURACION

        ValidacionesSimulacion.exigirCuotaInicialMenorQuePrecioEfectivo(
            request.precioVenta,
            descuento,
            request.cuotaInicial,
        )

        // Una sola pasada del motor (D35): `principal` depende del modo y su
        // formula ya vive ahi, asi que la validacion posterior lee su salida en
        // vez de duplicarla.
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

        // Relevo de la principal (D38/K14): desmarcar la vigente ANTES de
        // insertar, o el indice unico parcial `uq_simulacion_principal` aborta la
        // transaccion. Sin item nunca es principal: lo prohibe el CHECK
        // `chk_simulacion_principal_requiere_item`.
        if (item != null) {
            simulacionRepository.desmarcarPrincipalDe(item.id)
        }

        val ahora = LocalDateTime.now()
        val simulacion =
            simulacionRepository.save(
                Simulacion(
                    modo = modo,
                    // Nunca cadena vacia: lo prohibe `chk_simulacion_nombre_no_vacio`.
                    nombre = request.nombre?.trim()?.takeIf { it.isNotEmpty() },
                    idOportunidadItem = item?.id,
                    idModelo = idModelo,
                    precioVenta = request.precioVenta,
                    descuento = descuento,
                    cuotaInicial = request.cuotaInicial,
                    plazoMeses = request.plazoMeses,
                    tea = request.tea,
                    valorResidual = valorResidual,
                    diasTrabajados = diasTrabajados,
                    comisionEstructuracion = comisionEstructuracion,
                    cuotaFinal = resultado.cuotaFinal,
                    esPrincipal = item != null,
                    createdAt = ahora,
                    createdBy = usuario.id,
                    updatedAt = ahora,
                    updatedBy = usuario.id,
                ),
            )
        registrarEvento(simulacion, item, usuario, TipoEventoSimulacion.creada, simulacion.createdAt)
        return toDto(simulacion, item?.idEmpresa, modelo)
    }

    @Transactional(readOnly = true)
    override fun detalle(
        id: Long,
        usuario: UsuarioActual,
    ): SimulacionDto {
        permisos.exigirAcceso(usuario)
        val simulacion = entidad(id)
        val item = itemDe(simulacion.idOportunidadItem)
        // IDOR (CLAUDE.md regla 14, D31): ajena -> 404, nunca 403.
        permisos.exigirAlcance(simulacion.createdBy, item?.idVendedor, usuario)
        val modelo = simulacion.idModelo?.let { modeloService.resumen(it) }
        return toDto(simulacion, item?.idEmpresa, modelo)
    }

    @Transactional(readOnly = true)
    override fun listar(
        filtros: SimulacionFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<SimulacionDto> {
        // Mas restrictivo que exigirAcceso (D9): el listado del modulo es 403
        // tambien para `vendedor`. Solo llegan aqui admin/gerencia/analista
        // (§10, decision D39 de plan-09-mapa-simulaciones-modulo.md).
        permisos.exigirAccesoAlModulo(usuario)
        // Se resuelve ANTES de construir la Specification (no dentro de su lambda):
        // el lambda solo lo evalua el proveedor JPA en tiempo de query, y un modo
        // invalido debe ser 400 siempre, no solo cuando la query realmente corre.
        val modo = filtros.modo?.let { resolverModo(it) }
        val pageRequest = Paginacion.pageRequest(page, perPage, sort, dir, CAMPOS_ORDENABLES)
        val resultado = simulacionRepository.findAll(especificacion(filtros, modo), pageRequest)
        val meta = Paginacion.meta(pageRequest.pageNumber + 1, pageRequest.pageSize, resultado.totalElements)
        return Paginado(toDtos(resultado.content), meta)
    }

    /**
     * PATCH parcial de una simulacion (D11). Cada campo se toca SOLO si viene en
     * el body; uno ausente conserva el valor que la entidad ya tiene.
     *
     * `cuota_final` se recalcula SIEMPRE server-side con los valores ya
     * fusionados —nunca se conserva la anterior ni se acepta del cliente (§4,
     * §13)—, incluso cuando el body llega vacio.
     *
     * Limite conocido del contrato: con todos los campos nullable, "ausente" y
     * "null explicito" son indistinguibles, asi que este PATCH permite enlazar a
     * un item pero no desenlazar (poner `id_oportunidad_item` de vuelta en null).
     */
    @Transactional
    // Un `?.let` por campo opcional del PATCH, igual que `EmpresaServiceImpl.actualizar`: la
    // complejidad es la del DTO (muchos campos independientes), no logica ramificada.
    // `LongMethod`: fusion del PATCH, validaciones §13 y los dos eventos de bitacora (D45) son
    // una sola unidad transaccional; partirla ocultaria que ocurren juntas o no ocurren.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override fun actualizar(
        id: Long,
        request: ActualizarSimulacionRequest,
        usuario: UsuarioActual,
    ): SimulacionDto {
        permisos.exigirAcceso(usuario)
        val simulacion = entidad(id)
        val itemActual = itemDe(simulacion.idOportunidadItem)
        // IDOR (CLAUDE.md regla 14, D31): ajena -> 404, nunca 403.
        permisos.exigirAlcance(simulacion.createdBy, itemActual?.idVendedor, usuario)
        exigirModoInmutable(request.modo, simulacion.modo)

        // Reenlazar exige alcance sobre el item NUEVO (§9): un vendedor que
        // apunta al item de otro recibe 404. El reenlace registra ademas un
        // evento `enlazada_a_item` (D45), mas abajo, junto al `editada`.
        val idItemPedido = request.idOportunidadItem
        val item =
            if (idItemPedido != null && idItemPedido != simulacion.idOportunidadItem) {
                resolverItem(idItemPedido, usuario)
            } else {
                itemActual
            }

        // Fusion del PATCH sobre los valores actuales. `id_modelo` ausente
        // conserva el actual: al reenlazar NO se hereda el modelo del item nuevo.
        val idModelo = request.idModelo ?: simulacion.idModelo
        val modelo = idModelo?.let { modeloService.resumen(it) }
        val precioVenta = request.precioVenta ?: simulacion.precioVenta
        val descuento = request.descuento ?: simulacion.descuento
        val cuotaInicial = request.cuotaInicial ?: simulacion.cuotaInicial
        val plazoMeses = request.plazoMeses ?: simulacion.plazoMeses
        val tea = request.tea ?: simulacion.tea
        val valorResidual = request.valorResidual ?: simulacion.valorResidual

        // §13 sobre los valores YA FUSIONADOS y en el mismo orden que `crear`:
        // inicial, motor una sola vez, residual contra el `principal` del motor (D35).
        ValidacionesSimulacion.exigirCuotaInicialMenorQuePrecioEfectivo(precioVenta, descuento, cuotaInicial)
        val resultado =
            MotorSimulacion.calcular(
                ParametrosSimulacion(
                    // NUNCA el `modo` del request: es `val` en la entidad e inmutable (§2).
                    modo = simulacion.modo,
                    precioVenta = precioVenta,
                    descuento = descuento,
                    cuotaInicial = cuotaInicial,
                    plazoMeses = plazoMeses,
                    tea = tea,
                    valorResidual = valorResidual,
                ),
            )
        ValidacionesSimulacion.exigirValorResidualMenorQuePrincipal(valorResidual, resultado)

        // Reenlazar a OTRO item baja `esPrincipal` a false (K14/D38): esta
        // simulacion no "nacio" para ese item (§6.3 reserva "la ultima creada"
        // a `crear`), y si el item destino ya tiene su propia principal, dejar
        // `esPrincipal = true` aqui violaria `uq_simulacion_principal` sin pasar
        // por `desmarcarPrincipalDe`. Promoverla a principal del item nuevo es
        // "marcar principal" explicito (Plan E), no un efecto lateral del PATCH.
        val reenlazando = idItemPedido != null && idItemPedido != simulacion.idOportunidadItem
        if (reenlazando) {
            simulacion.esPrincipal = false
        }

        // Nunca cadena vacia: lo prohibe `chk_simulacion_nombre_no_vacio`. Un
        // nombre en blanco devuelve la simulacion al autogenerado de §8.1.
        request.nombre?.let { simulacion.nombre = it.trim().takeIf(String::isNotEmpty) }
        idItemPedido?.let { simulacion.idOportunidadItem = it }
        simulacion.idModelo = idModelo
        simulacion.precioVenta = precioVenta
        simulacion.descuento = descuento
        simulacion.cuotaInicial = cuotaInicial
        simulacion.plazoMeses = plazoMeses
        simulacion.tea = tea
        simulacion.valorResidual = valorResidual
        request.diasTrabajados?.let { simulacion.diasTrabajados = it }
        request.comisionEstructuracion?.let { simulacion.comisionEstructuracion = it }
        simulacion.cuotaFinal = resultado.cuotaFinal
        simulacion.updatedAt = LocalDateTime.now()
        simulacion.updatedBy = usuario.id

        // UPDATE de la fila existente, no un INSERT: se muta la entidad cargada,
        // que ya trae su `id`.
        val actualizada = simulacionRepository.save(simulacion)
        // Dos filas de log cuando el PATCH reenlaza (D45): el `editada` se
        // registra SIEMPRE, como en Plan D, y su diff saldra vacio si el
        // reenlace fue lo unico que cambio —correcto por spec, no un bug (K30).
        if (reenlazando) {
            registrarEventoDeEnlace(
                idSimulacion = requireNotNull(actualizada.id),
                idOportunidadItem = requireNotNull(idItemPedido),
                usuario = usuario,
                tipoEvento = TipoEventoSimulacion.enlazada_a_item,
            )
        }
        registrarEvento(actualizada, item, usuario, TipoEventoSimulacion.editada, actualizada.updatedAt)
        return toDto(actualizada, item?.idEmpresa, modelo)
    }

    /**
     * Hard delete (§5: esta tabla no tiene borrado logico). El evento
     * `eliminada` se registra ANTES del `delete`: es el unico orden que hace
     * que el snapshot en `simulacion_log` sea correcto por diseño y no por
     * casualidad de que el objeto Kotlin siga vivo en memoria tras borrar la
     * fila (§5, §7 de reglas_simulaciones.md).
     */
    @Transactional
    override fun eliminar(
        id: Long,
        usuario: UsuarioActual,
    ) {
        permisos.exigirAcceso(usuario)
        val simulacion = entidad(id)
        val item = itemDe(simulacion.idOportunidadItem)
        // IDOR (CLAUDE.md regla 14, D31): ajena -> 404, nunca 403.
        permisos.exigirAlcance(simulacion.createdBy, item?.idVendedor, usuario)

        registrarEvento(simulacion, item, usuario, TipoEventoSimulacion.eliminada, LocalDateTime.now())
        simulacionRepository.delete(simulacion)
    }

    /**
     * Cronograma recalculado al vuelo (D40, §4): nunca se persiste, ni en
     * `simulaciones` ni en `simulacion_log`. Mismo [ParametrosSimulacion] que
     * arman [crear] y [actualizar], leido directo de los campos esenciales ya
     * guardados en la entidad. El motor no se toca (K10).
     */
    @Transactional(readOnly = true)
    override fun cronograma(
        id: Long,
        usuario: UsuarioActual,
    ): CronogramaDto {
        permisos.exigirAcceso(usuario)
        val simulacion = entidad(id)
        val item = itemDe(simulacion.idOportunidadItem)
        // IDOR (CLAUDE.md regla 14, D31): ajena -> 404, nunca 403.
        permisos.exigirAlcance(simulacion.createdBy, item?.idVendedor, usuario)

        val resultado =
            MotorSimulacion.calcular(
                ParametrosSimulacion(
                    modo = simulacion.modo,
                    precioVenta = simulacion.precioVenta,
                    descuento = simulacion.descuento,
                    cuotaInicial = simulacion.cuotaInicial,
                    plazoMeses = simulacion.plazoMeses,
                    tea = simulacion.tea,
                    valorResidual = simulacion.valorResidual,
                ),
            )

        return CronogramaDto(
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

    /**
     * Historial con diff (§7.1, §7.2): los eventos con snapshot de los ultimos
     * 7 dias, hasta 15, mas recientes primero — el orden que ya devuelve la
     * query, que NO se reordena aqui.
     *
     * Dos consultas fijas por peticion, nunca una por evento: el predecesor de
     * cada fila devuelta ya esta en la propia lista (es la siguiente, porque
     * viene descendente), y el unico que falta es el del evento mas antiguo,
     * que puede caer FUERA de la ventana de 7 dias y aun asi es su predecesor
     * real (K23). Si ese evento es el primero de todos, [DiffSimulacion]
     * recibe `null` y devuelve diff vacio.
     *
     * Un diff vacio no es un error: tambien lo produce una escritura que no
     * toco ninguno de los 10 parametros del snapshot —un PATCH vacio o uno que
     * solo reenlaza a otro item— (K30).
     */
    @Transactional(readOnly = true)
    override fun historial(
        id: Long,
        usuario: UsuarioActual,
    ): List<EventoHistorialDto> {
        permisos.exigirAcceso(usuario)
        val simulacion = entidad(id)
        val item = itemDe(simulacion.idOportunidadItem)
        // IDOR (CLAUDE.md regla 14, D31): ajena -> 404, nunca 403.
        permisos.exigirAlcance(simulacion.createdBy, item?.idVendedor, usuario)

        val eventos = simulacionLogRepository.historial(id)
        // Sin eventos no hay predecesor que buscar: ni una consulta de mas.
        if (eventos.isEmpty()) return emptyList()

        val masAntiguo = eventos.last()
        val anteriorAlMasAntiguo =
            simulacionLogRepository.eventoAnteriorA(id, masAntiguo.createdAt, requireNotNull(masAntiguo.id))

        return eventos.mapIndexed { indice, evento ->
            // La lista viene descendente: el predecesor cronologico de la fila
            // `indice` es la fila `indice + 1`; el de la ultima, el de fuera.
            val predecesor = eventos.getOrNull(indice + 1) ?: anteriorAlMasAntiguo
            EventoHistorialDto(
                idEventoLog = requireNotNull(evento.id),
                tipoEvento = evento.tipoEvento.name,
                createdAt = evento.createdAt.comoInstanteUtc(),
                createdBy = evento.createdBy,
                diff = DiffSimulacion.calcular(predecesor, evento),
            )
        }
    }

    /**
     * Restaura una version de la ventana de 7 dias (§7.2, decision D48 de
     * plan-11-mapa-historial-calculadora.md). Escribe la simulacion y DOS filas
     * de log en la misma transaccion: el `editada` que congela el estado previo
     * —para poder deshacer el deshacer— y el `restaurada` con el estado nuevo.
     *
     * `cuota_final` sale SIEMPRE del motor, nunca de `log.cuotaFinal` (§7.2
     * paso 3), y el snapshot vuelve a pasar las validaciones §13 en el mismo
     * orden que [crear]: si hubo una correccion de formula entre medio,
     * `principal` cambio y un `valor_residual` que era valido puede haber
     * dejado de serlo. Sin revalidar, el motor lanzaria
     * `CronogramaInconsistenteException` —un 500— en vez de un 400 limpio.
     *
     * NO restaura `modo` (es `val`, §2, y es la misma simulacion), ni
     * `esPrincipal` ni `idOportunidadItem`: no estan en el snapshot y no son
     * parametros de calculo. Restaurar parametros no mueve la simulacion de
     * item ni cambia quien es la principal.
     */
    @Transactional
    override fun restaurar(
        id: Long,
        idEventoLog: Long,
        usuario: UsuarioActual,
    ): SimulacionDto {
        permisos.exigirAcceso(usuario)
        val simulacion = entidad(id)
        val item = itemDe(simulacion.idOportunidadItem)
        // IDOR (CLAUDE.md regla 14, D31): ajena -> 404, nunca 403.
        permisos.exigirAlcance(simulacion.createdBy, item?.idVendedor, usuario)
        val log = versionRestaurable(id, idEventoLog)
        val modelo = simulacion.idModelo?.let { modeloService.resumen(it) }

        // El estado ACTUAL, ANTES de mutar nada, con `momento = ahora`: el log
        // es un diario cronologico y este evento ("se guardo el estado previo")
        // ocurre ahora, no cuando se hizo la edicion que lo dejo asi. Fecharlo
        // con el `updatedAt` viejo insertaria una fila fuera de orden y romperia
        // el diff, que asume orden cronologico (D48 paso 3).
        registrarEvento(simulacion, item, usuario, TipoEventoSimulacion.editada, LocalDateTime.now())

        // El CHECK `chk_simulacion_log_snapshot` exige el snapshot completo para
        // los tres tipos que [versionRestaurable] admite (K15): un nulo aqui
        // seria corrupcion de datos, no un caso de negocio.
        val precioVenta = requireNotNull(log.precioVenta)
        val descuento = requireNotNull(log.descuento)
        val cuotaInicial = requireNotNull(log.cuotaInicial)
        val plazoMeses = requireNotNull(log.plazoMeses)
        val tea = requireNotNull(log.tea)
        val valorResidual = requireNotNull(log.valorResidual)

        // §13 y motor en el MISMO orden que `crear` y `actualizar`: inicial,
        // motor una sola vez (D35), residual contra el `principal` del motor.
        ValidacionesSimulacion.exigirCuotaInicialMenorQuePrecioEfectivo(precioVenta, descuento, cuotaInicial)
        val resultado =
            MotorSimulacion.calcular(
                ParametrosSimulacion(
                    // NUNCA `log.modo`: el modo es inmutable (§2) y el vigente es
                    // el de la entidad.
                    modo = simulacion.modo,
                    precioVenta = precioVenta,
                    descuento = descuento,
                    cuotaInicial = cuotaInicial,
                    plazoMeses = plazoMeses,
                    tea = tea,
                    valorResidual = valorResidual,
                ),
            )
        ValidacionesSimulacion.exigirValorResidualMenorQuePrincipal(valorResidual, resultado)

        simulacion.precioVenta = precioVenta
        simulacion.descuento = descuento
        simulacion.cuotaInicial = cuotaInicial
        simulacion.plazoMeses = plazoMeses
        simulacion.tea = tea
        simulacion.valorResidual = valorResidual
        simulacion.diasTrabajados = requireNotNull(log.diasTrabajados)
        simulacion.comisionEstructuracion = requireNotNull(log.comisionEstructuracion)
        // Del motor, jamas `log.cuotaFinal` (§7.2 paso 3).
        simulacion.cuotaFinal = resultado.cuotaFinal
        simulacion.updatedAt = LocalDateTime.now()
        simulacion.updatedBy = usuario.id

        val restaurada = simulacionRepository.save(simulacion)
        registrarEvento(restaurada, item, usuario, TipoEventoSimulacion.restaurada, restaurada.updatedAt)
        return toDto(restaurada, item?.idEmpresa, modelo)
    }

    /**
     * §6.3: cambia manualmente cual es la simulacion principal del item (D46).
     * Operacion propia, no un campo de [actualizar]: "cambiarse" no es
     * "editarse".
     *
     * Sin item enlazado no hay principal posible
     * (`chk_simulacion_principal_requiere_item`, K28): se rechaza con un 409
     * de negocio ANTES de tocar la base, mismo criterio que
     * [exigirModoInmutable] aplica a `modo`.
     *
     * Ya principal es no-op exitoso: ni `desmarcarPrincipalDe`, ni `save`, ni
     * evento — un `marcada_principal` ahi no representaria ningun cambio real.
     */
    @Transactional
    override fun marcarPrincipal(
        id: Long,
        usuario: UsuarioActual,
    ): SimulacionDto {
        permisos.exigirAcceso(usuario)
        val simulacion = entidad(id)
        val item = itemDe(simulacion.idOportunidadItem)
        // IDOR (CLAUDE.md regla 14, D31): ajena -> 404, nunca 403.
        permisos.exigirAlcance(simulacion.createdBy, item?.idVendedor, usuario)
        val modelo = simulacion.idModelo?.let { modeloService.resumen(it) }

        val idItem = simulacion.idOportunidadItem
        if (idItem == null) {
            throw ConflictoException(
                code = "SIMULACION_SIN_ITEM",
                message =
                    "Esta simulación no está enlazada a un ítem de oportunidad. " +
                        "Enlázala primero para poder marcarla como principal.",
            )
        }

        if (simulacion.esPrincipal) {
            return toDto(simulacion, item?.idEmpresa, modelo)
        }

        // Relevo de la principal (D38): desmarcar la vigente ANTES de marcar
        // esta, o el indice unico parcial `uq_simulacion_principal` aborta la
        // transaccion.
        simulacionRepository.desmarcarPrincipalDe(idItem)
        simulacion.esPrincipal = true
        simulacion.updatedAt = LocalDateTime.now()
        simulacion.updatedBy = usuario.id

        val actualizada = simulacionRepository.save(simulacion)
        registrarEventoDeEnlace(
            idSimulacion = requireNotNull(actualizada.id),
            idOportunidadItem = idItem,
            usuario = usuario,
            tipoEvento = TipoEventoSimulacion.marcada_principal,
        )
        return toDto(actualizada, item?.idEmpresa, modelo)
    }

    /**
     * §7.3 "Guardar como Nueva Simulacion" (decision D49 de
     * plan-11-mapa-historial-calculadora.md): INSERTA una fila NUEVA con
     * `id_simulacion_origen` apuntando al origen. No es un [actualizar] con
     * otro id — es un [crear] cuyos defaults son los valores del origen (K27).
     *
     * A diferencia de [actualizar], `modo` NO pasa por [exigirModoInmutable]:
     * §2 dice que cambiar de modo "exige Guardar como Nueva Simulacion", y esta
     * es exactamente esa via. La fila del origen conserva su modo intacto.
     *
     * `nombre` es el UNICO campo que NO se hereda: sale solo del request.
     * Heredar un nombre manual dejaria dos simulaciones con identico titulo;
     * sin el, la bifurcada autogenera el suyo y el correlativo las distingue
     * (§8.1).
     *
     * **Sobre el origen**: su entidad no se muta ni se guarda aqui — nunca hay
     * `save(origen)` ni `delete(origen)`. Pero si la bifurcada hereda su mismo
     * item y nace principal, el [SimulacionRepository.desmarcarPrincipalDe] de
     * mas abajo SI actualiza su fila en la base (UPDATE masivo por item). Es el
     * mismo relevo que hace [crear] y es correcto: §6.3 define la principal como
     * la ultima creada para ese item, y una bifurcacion es una creacion (D38).
     */
    @Transactional
    // Un `?:` por campo heredable, igual que `actualizar`: la complejidad es la del DTO
    // (muchos campos independientes), no logica ramificada. `LongMethod`: fusion sobre el
    // origen, validaciones §13, relevo de principal e insercion son una sola unidad
    // transaccional; partirla ocultaria que ocurren juntas o no ocurren.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override fun bifurcar(
        id: Long,
        request: BifurcarSimulacionRequest,
        usuario: UsuarioActual,
    ): SimulacionDto {
        permisos.exigirAcceso(usuario)
        val origen = entidad(id)
        val itemDelOrigen = itemDe(origen.idOportunidadItem)
        // IDOR (CLAUDE.md regla 14, D31): origen ajeno -> 404, nunca 403.
        permisos.exigirAlcance(origen.createdBy, itemDelOrigen?.idVendedor, usuario)

        // Sin `exigirModoInmutable` A PROPOSITO (K27, §2): esta es la via autorizada.
        val modo = request.modo?.let { resolverModo(it) } ?: origen.modo

        // La bifurcada hereda el item del origen (§7.3). Si el request pide otro,
        // se resuelve con la misma validacion de alcance que usa `actualizar` al
        // reenlazar: un vendedor que apunta al item de otro recibe 404.
        val idItemPedido = request.idOportunidadItem
        val item =
            if (idItemPedido != null && idItemPedido != origen.idOportunidadItem) {
                resolverItem(idItemPedido, usuario)
            } else {
                itemDelOrigen
            }

        // Fusion del request sobre los valores del ORIGEN, que hace de plantilla.
        val idModelo = request.idModelo ?: origen.idModelo
        val modelo = idModelo?.let { modeloService.resumen(it) }
        val precioVenta = request.precioVenta ?: origen.precioVenta
        val descuento = request.descuento ?: origen.descuento
        val cuotaInicial = request.cuotaInicial ?: origen.cuotaInicial
        val plazoMeses = request.plazoMeses ?: origen.plazoMeses
        val tea = request.tea ?: origen.tea
        val valorResidual = request.valorResidual ?: origen.valorResidual
        val diasTrabajados = request.diasTrabajados ?: origen.diasTrabajados
        val comisionEstructuracion = request.comisionEstructuracion ?: origen.comisionEstructuracion

        // §13 sobre los valores YA FUSIONADOS y en el mismo orden que `crear`:
        // inicial, motor una sola vez (D35), residual contra el `principal` del motor.
        ValidacionesSimulacion.exigirCuotaInicialMenorQuePrecioEfectivo(precioVenta, descuento, cuotaInicial)
        val resultado =
            MotorSimulacion.calcular(
                ParametrosSimulacion(
                    modo = modo,
                    precioVenta = precioVenta,
                    descuento = descuento,
                    cuotaInicial = cuotaInicial,
                    plazoMeses = plazoMeses,
                    tea = tea,
                    valorResidual = valorResidual,
                ),
            )
        ValidacionesSimulacion.exigirValorResidualMenorQuePrincipal(valorResidual, resultado)

        // Relevo de la principal (D38/K14), identico al de `crear`: desmarcar la
        // vigente ANTES de insertar, o el indice unico parcial
        // `uq_simulacion_principal` aborta la transaccion.
        if (item != null) {
            simulacionRepository.desmarcarPrincipalDe(item.id)
        }

        val ahora = LocalDateTime.now()
        val bifurcada =
            simulacionRepository.save(
                Simulacion(
                    modo = modo,
                    // NO se hereda el del origen (D49). Nunca cadena vacia:
                    // lo prohibe `chk_simulacion_nombre_no_vacio`.
                    nombre = request.nombre?.trim()?.takeIf { it.isNotEmpty() },
                    idOportunidadItem = item?.id,
                    idModelo = idModelo,
                    idSimulacionOrigen = requireNotNull(origen.id),
                    precioVenta = precioVenta,
                    descuento = descuento,
                    cuotaInicial = cuotaInicial,
                    plazoMeses = plazoMeses,
                    tea = tea,
                    valorResidual = valorResidual,
                    diasTrabajados = diasTrabajados,
                    comisionEstructuracion = comisionEstructuracion,
                    cuotaFinal = resultado.cuotaFinal,
                    esPrincipal = item != null,
                    createdAt = ahora,
                    createdBy = usuario.id,
                    updatedAt = ahora,
                    updatedBy = usuario.id,
                ),
            )
        // El enum no tiene tipo "bifurcada": la fila nueva nace con su propio
        // `creada`, y el vinculo con el origen viaja en `idSimulacionOrigen` del
        // log ademas de en la columna de `simulaciones` (K27, §7.3: el dato
        // sobrevive aunque el origen se purgue).
        registrarEvento(
            bifurcada,
            item,
            usuario,
            TipoEventoSimulacion.creada,
            bifurcada.createdAt,
            idSimulacionOrigen = bifurcada.idSimulacionOrigen,
        )
        return toDto(bifurcada, item?.idEmpresa, modelo)
    }

    /**
     * El evento de bitacora que se puede restaurar, o 404 (D48 paso 2). Es 404
     * —"esa version no es restaurable"— y no 409 en los cuatro casos, para no
     * filtrar por status code cuando la simulacion ademas es ajena.
     *
     * El limite de 15 versiones NO se valida aqui: §7.2 lo define como filtro
     * de lectura del historial, no como regla de escritura.
     */
    private fun versionRestaurable(
        idSimulacion: Long,
        idEventoLog: Long,
    ): SimulacionLog {
        val log: SimulacionLog? = simulacionLogRepository.findById(idEventoLog).orElse(null)
        // Los cuatro motivos comparten condicion y mensaje a proposito: distinguirlos
        // en la respuesta filtraria si el id existe o de quien es.
        val restaurable =
            log != null &&
                // El log de OTRA simulacion no restaura esta, aunque el id exista.
                log.idSimulacion == idSimulacion &&
                // `marcada_principal`/`enlazada_a_item` no llevan snapshot (K24) y
                // `eliminada` describe una fila que ya no existe.
                log.tipoEvento in TIPOS_RESTAURABLES &&
                !log.createdAt.isBefore(LocalDateTime.now().minusDays(DIAS_VENTANA_RESTAURACION))
        if (!restaurable) throw NoEncontradoException(VERSION_NO_RESTAURABLE)
        return requireNotNull(log)
    }

    /**
     * Un `modo` fuera del enum es un error del cliente (400), no un 500: sin
     * este filtro `ModoSimulacion.valueOf` lanzaria `IllegalArgumentException`.
     */
    private fun resolverModo(modo: String): ModoSimulacion =
        ModoSimulacion.entries.firstOrNull { it.name == modo }
            ?: throw ValidacionException(
                "modo debe ser uno de: ${ModoSimulacion.entries.joinToString { it.name }}",
                field = "modo",
            )

    /**
     * §2: `modo` es INMUTABLE tras la creacion —leasing y credito directo usan
     * formulas y columnas de cronograma distintas—, y el Service es una de las
     * tres lineas de defensa que la regla exige. Rechazar aqui evita llegar al
     * trigger `trg_simulacion_modo_inmutable`, que responde 500 (K16); esto es
     * un 409 limpio (decision D36).
     *
     * Un `modo` ausente o igual al actual no es conflicto: sigue. Uno fuera del
     * enum es 400, igual que en `crear`, porque [resolverModo] se aplica antes
     * de comparar.
     */
    private fun exigirModoInmutable(
        modoPedido: String?,
        modoActual: ModoSimulacion,
    ) {
        if (modoPedido == null || resolverModo(modoPedido) == modoActual) return
        throw ConflictoException(
            code = "MODO_INMUTABLE",
            message =
                "El modo de una simulación no se puede cambiar después de crearla. " +
                    "Usa \"Guardar como Nueva Simulación\" para simular la misma unidad con el otro modo.",
            field = "modo",
        )
    }

    /**
     * El item enlazado, o null si la simulacion nace suelta (§5: el enlace es
     * opcional). El item ajeno se trata como inexistente: 404, nunca 403
     * (CLAUDE.md regla 14, decision D31).
     */
    private fun resolverItem(
        idItem: Long?,
        usuario: UsuarioActual,
    ): OportunidadItemParaSimulacion? {
        if (idItem == null) return null
        val item =
            oportunidadItemService.datosParaSimulacion(listOf(idItem))[idItem]
                ?: throw NoEncontradoException("El item de oportunidad no existe")
        permisos.exigirAlcance(idCreador = usuario.id, idVendedorDelItem = item.idVendedor, usuario = usuario)
        return item
    }

    /**
     * Evento de bitacora con el snapshot COMPLETO que exige el CHECK
     * `chk_simulacion_log_snapshot` (K15): un INSERT incompleto revienta con 500.
     * `idOportunidad` se deriva del item; sin item va null (§5: no hay columna
     * directa, la cadena pasa por `id_oportunidad_item`).
     *
     * Lo comparten `creada` (D9) y `editada` (D11): el snapshot que ambos
     * eventos exigen es el mismo y su unica diferencia es [tipoEvento] y el
     * [momento] en que ocurrieron —`createdAt` al crear, `updatedAt` al
     * editar—, asi que duplicar el ensamblado seria una segunda copia de la
     * lista de campos del CHECK.
     *
     * [idSimulacionOrigen] solo lo puebla [bifurcar] (K27): el resto de los
     * llamadores lo dejan en su default `null`, que es lo correcto — su evento
     * no nace de ninguna otra simulacion.
     *
     * `LongParameterList`: un parametro por dimension del evento (que fila, que
     * item, quien, que tipo, cuando, de que origen). Son los campos del propio
     * [SimulacionLog], no una firma agrupable sin inventar un DTO intermedio que
     * solo usaria este metodo privado.
     */
    @Suppress("LongParameterList")
    private fun registrarEvento(
        simulacion: Simulacion,
        item: OportunidadItemParaSimulacion?,
        usuario: UsuarioActual,
        tipoEvento: TipoEventoSimulacion,
        momento: LocalDateTime,
        idSimulacionOrigen: Long? = null,
    ) {
        simulacionLogRepository.save(
            SimulacionLog(
                idSimulacion = requireNotNull(simulacion.id),
                idSimulacionOrigen = idSimulacionOrigen,
                tipoEvento = tipoEvento,
                modo = simulacion.modo,
                precioVenta = simulacion.precioVenta,
                descuento = simulacion.descuento,
                cuotaInicial = simulacion.cuotaInicial,
                plazoMeses = simulacion.plazoMeses,
                tea = simulacion.tea,
                valorResidual = simulacion.valorResidual,
                diasTrabajados = simulacion.diasTrabajados,
                comisionEstructuracion = simulacion.comisionEstructuracion,
                cuotaFinal = simulacion.cuotaFinal,
                idOportunidadItem = item?.id,
                idOportunidad = item?.idOportunidad,
                createdAt = momento,
                createdBy = usuario.id,
            ),
        )
    }

    /**
     * Eventos SIN snapshot (`marcada_principal`, `enlazada_a_item`): el CHECK
     * `chk_simulacion_log_snapshot` solo exige `id_oportunidad_item` para estos dos
     * tipos (K24). Meter aqui el snapshot completo seria semanticamente incorrecto
     * —el evento no representa una edicion de parametros— aunque el CHECK lo
     * dejara pasar.
     */
    private fun registrarEventoDeEnlace(
        idSimulacion: Long,
        idOportunidadItem: Long,
        usuario: UsuarioActual,
        tipoEvento: TipoEventoSimulacion,
    ) {
        simulacionLogRepository.save(
            SimulacionLog(
                idSimulacion = idSimulacion,
                tipoEvento = tipoEvento,
                idOportunidadItem = idOportunidadItem,
                createdAt = LocalDateTime.now(),
                createdBy = usuario.id,
            ),
        )
    }

    /** La simulacion por id, o `NoEncontradoException` (404) si no existe. */
    private fun entidad(id: Long): Simulacion =
        simulacionRepository.findById(id).orElseThrow { NoEncontradoException("La simulacion no existe") }

    /**
     * El item enlazado de una simulacion ya persistida, SIN chequeo de
     * visibilidad de oportunidades (D32) — igual que [resolverItem]. Quien
     * llama decide con [SimulacionPermisos] si el usuario alcanza.
     */
    private fun itemDe(idItem: Long?): OportunidadItemParaSimulacion? =
        idItem?.let { oportunidadItemService.datosParaSimulacion(listOf(it))[it] }

    /**
     * Filtros de `GET /simulaciones` (`id_oportunidad_item`, `id_modelo`,
     * `modo`).
     *
     * A PROPOSITO no lleva NINGUN filtro de visibilidad por vendedor: los
     * tres roles que superan [SimulacionPermisos.exigirAccesoAlModulo]
     * (`admin`, `gerencia`, `analista`) ven TODAS las simulaciones del
     * sistema (`reglas_simulaciones.md` §10,
     * `plan-09-mapa-simulaciones-modulo.md` decision D39). Comparado con el
     * patron de `oportunidades` o `metas_venta` -que si filtran por
     * vendedor- esto parece un bug; no lo es: `vendedor`, `jdv` y `otro` ya
     * fueron rechazados con 403 antes de construir esta Specification.
     *
     * `modo` llega ya resuelto (ver [listar]): resolverlo aqui dentro
     * dejaria la validacion 400 a merced de que el proveedor JPA decida
     * evaluar el lambda.
     */
    private fun especificacion(
        filtros: SimulacionFiltros,
        modo: ModoSimulacion?,
    ): Specification<Simulacion> =
        Specification { root, _, cb ->
            val predicados = mutableListOf<Predicate>()
            filtros.idOportunidadItem?.let { predicados += cb.equal(root.get<Long>("idOportunidadItem"), it) }
            filtros.idModelo?.let { predicados += cb.equal(root.get<Long>("idModelo"), it) }
            modo?.let { predicados += cb.equal(root.get<ModoSimulacion>("modo"), it) }
            cb.and(*predicados.toTypedArray())
        }

    /**
     * Ensamblado en lotes de una pagina completa (D10): una sola llamada a
     * cada dependencia para toda la pagina, nunca una por fila. Reutiliza
     * [ensamblar], el mismo punto de union que usa [toDto] para una sola
     * simulacion.
     */
    private fun toDtos(simulaciones: List<Simulacion>): List<SimulacionDto> {
        if (simulaciones.isEmpty()) return emptyList()

        // El correlativo solo hace falta para las que no tienen nombre
        // manual: el manual es pegajoso y nunca se regenera (§8.1).
        val idsSinNombreManual = simulaciones.filter { it.nombre == null }.map { requireNotNull(it.id) }
        val correlativos =
            if (idsSinNombreManual.isEmpty()) {
                emptyMap()
            } else {
                simulacionRepository.correlativos(idsSinNombreManual).associateBy({ it.getId() }, { it.getCorrelativo() })
            }

        val idsModelo = simulaciones.mapNotNull { it.idModelo }.distinct()
        val modelos = if (idsModelo.isEmpty()) emptyMap() else modeloService.resumenPorIds(idsModelo)

        val idsItem = simulaciones.mapNotNull { it.idOportunidadItem }.distinct()
        val items = if (idsItem.isEmpty()) emptyMap() else oportunidadItemService.datosParaSimulacion(idsItem)

        val idsEmpresa = items.values.map { it.idEmpresa }.distinct()
        val empresas = if (idsEmpresa.isEmpty()) emptyMap() else empresaService.resumenPorIds(idsEmpresa)

        return simulaciones.map { simulacion ->
            val modelo = simulacion.idModelo?.let { modelos[it] }
            val manual = simulacion.nombre
            if (manual != null) {
                ensamblar(simulacion, manual, nombreEsManual = true, modelo = modelo)
            } else {
                val item = simulacion.idOportunidadItem?.let { items[it] }
                val razonSocial = item?.let { empresas[it.idEmpresa]?.razonSocial }
                val correlativo = correlativos[requireNotNull(simulacion.id)] ?: CORRELATIVO_INICIAL
                val autogenerado = NombreSimulacion.autogenerado(razonSocial, modelo?.codigo, simulacion.modo, correlativo)
                ensamblar(simulacion, autogenerado, nombreEsManual = false, modelo = modelo)
            }
        }
    }

    /**
     * DTO de UNA simulacion: resuelve lo que le falta al ensamblado (razon
     * social de la empresa y correlativo del nombre) y delega en [ensamblar].
     * El listado ([listar], D10) resuelve esos mismos datos por lotes en
     * [toDtos] y llama directamente a [ensamblar], sin pasar por aqui.
     */
    private fun toDto(
        simulacion: Simulacion,
        idEmpresa: Long?,
        modelo: ModeloResumen?,
    ): SimulacionDto {
        // El nombre manual es pegajoso (§8.1): si existe, manda y no se
        // autogenera nada — ni se consulta la empresa ni el correlativo.
        val manual = simulacion.nombre
        if (manual != null) {
            return ensamblar(simulacion, manual, nombreEsManual = true, modelo = modelo)
        }
        val razonSocial = idEmpresa?.let { empresaService.resumenPorIds(listOf(it))[it]?.razonSocial }
        val correlativo =
            simulacionRepository
                .correlativos(listOf(requireNotNull(simulacion.id)))
                .firstOrNull()
                ?.getCorrelativo() ?: CORRELATIVO_INICIAL
        val autogenerado = NombreSimulacion.autogenerado(razonSocial, modelo?.codigo, simulacion.modo, correlativo)
        return ensamblar(simulacion, autogenerado, nombreEsManual = false, modelo = modelo)
    }

    /**
     * Ensamblado puro del DTO: no consulta nada, recibe ya resueltos el nombre
     * y el modelo. Es el punto que reutiliza el listado por lotes (D10).
     *
     * Los importes salen como `String` para no perder precision decimal en JSON,
     * igual que `OportunidadItemDto`.
     */
    private fun ensamblar(
        simulacion: Simulacion,
        nombre: String,
        nombreEsManual: Boolean,
        modelo: ModeloResumen?,
    ): SimulacionDto =
        SimulacionDto(
            id = requireNotNull(simulacion.id),
            nombre = nombre,
            nombreEsManual = nombreEsManual,
            modo = simulacion.modo.name,
            idOportunidadItem = simulacion.idOportunidadItem,
            idModelo = simulacion.idModelo,
            modelo = modelo?.let { ModeloEnSimulacionDto(id = it.id, codigo = it.codigo) },
            idSimulacionOrigen = simulacion.idSimulacionOrigen,
            precioVenta = simulacion.precioVenta.toPlainString(),
            descuento = simulacion.descuento.toPlainString(),
            cuotaInicial = simulacion.cuotaInicial.toPlainString(),
            plazoMeses = simulacion.plazoMeses,
            tea = simulacion.tea.toPlainString(),
            valorResidual = simulacion.valorResidual.toPlainString(),
            diasTrabajados = simulacion.diasTrabajados,
            comisionEstructuracion = simulacion.comisionEstructuracion.toPlainString(),
            cuotaFinal = simulacion.cuotaFinal.toPlainString(),
            esPrincipal = simulacion.esPrincipal,
            createdAt = simulacion.createdAt.comoInstanteUtc(),
            updatedAt = simulacion.updatedAt.comoInstanteUtc(),
        )

    private companion object {
        /**
         * Correlativo de respaldo del nombre autogenerado (§8.1). La proyeccion
         * siempre devuelve fila para una simulacion recien insertada; este valor
         * solo cubre el caso degenerado, y §8.1 dice que el correlativo no es un
         * dato critico.
         */
        const val CORRELATIVO_INICIAL = 1

        /** Ventana de restauracion de §7.2: 7 dias. El limite de 15 versiones lo aplica la query del historial. */
        const val DIAS_VENTANA_RESTAURACION = 7L

        /** Los unicos eventos con snapshot restaurable (§7.2). */
        val TIPOS_RESTAURABLES =
            setOf(
                TipoEventoSimulacion.creada,
                TipoEventoSimulacion.editada,
                TipoEventoSimulacion.restaurada,
            )

        /** Mismo mensaje para los cuatro motivos de 404 de `restaurar`: no se filtra cual fallo. */
        const val VERSION_NO_RESTAURABLE = "La version a restaurar no existe"

        /** Allowlist de `sort` de GET /simulaciones; el primero es el orden por defecto (D10). */
        val CAMPOS_ORDENABLES = CamposOrdenables("createdAt", "id", "cuotaFinal", "updatedAt")
    }
}
