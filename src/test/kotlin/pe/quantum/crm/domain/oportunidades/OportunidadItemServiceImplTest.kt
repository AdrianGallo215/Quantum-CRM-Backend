package pe.quantum.crm.domain.oportunidades

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.modelos.dto.ModeloResumen
import pe.quantum.crm.domain.oportunidades.dto.ActualizarOportunidadItemRequest
import pe.quantum.crm.domain.oportunidades.dto.CrearOportunidadItemRequest
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.shared.enums.EstadoOportunidad
import pe.quantum.crm.shared.exception.AprobacionRequeridaException
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Optional

class OportunidadItemServiceImplTest {
    private val itemRepository = mockk<OportunidadItemRepository>()
    private val oportunidadRepository = mockk<OportunidadRepository>()
    private val modeloService = mockk<ModeloService>()
    private val tareaService = mockk<TareaService>()
    private val service =
        OportunidadItemServiceImpl(
            itemRepository,
            oportunidadRepository,
            modeloService,
            OportunidadVisibilidad(tareaService),
        )

    private val vendedor = UsuarioActual(id = 1, rol = "vendedor")

    private fun oportunidad(
        id: Long = 100,
        idVendedor: Long = 1,
    ) = Oportunidad(
        id = id,
        idEmpresa = 10,
        idVendedor = idVendedor,
        idFinanciadora = 1,
        estado = EstadoOportunidad.evaluacion_calidda,
        createdAt = LocalDateTime.now(),
        createdBy = 1,
        updatedAt = LocalDateTime.now(),
        updatedBy = 1,
    )

    private fun item(
        id: Long,
        idModelo: Long = 7,
        cantidad: Int? = 2,
        precioVenta: BigDecimal? = BigDecimal("100.00"),
        descuento: BigDecimal? = BigDecimal("10.00"),
    ) = OportunidadItem(
        id = id,
        idOportunidad = 100,
        idModelo = idModelo,
        cantidad = cantidad,
        precioVenta = precioVenta,
        descuento = descuento,
        createdBy = 1,
        updatedBy = 1,
    )

    /** Simula el `save` de JPA: si el item aun no tiene id, devuelve una copia con uno asignado. */
    private fun stubGuardadoDeItem(idAsignado: Long = 500) {
        every { itemRepository.save(any()) } answers {
            val guardado = firstArg<OportunidadItem>()
            if (guardado.id != null) {
                guardado
            } else {
                OportunidadItem(
                    id = idAsignado,
                    idOportunidad = guardado.idOportunidad,
                    idModelo = guardado.idModelo,
                    cantidad = guardado.cantidad,
                    precioVenta = guardado.precioVenta,
                    descuento = guardado.descuento,
                    cuotaFinanciadora = guardado.cuotaFinanciadora,
                    createdAt = guardado.createdAt,
                    createdBy = guardado.createdBy,
                    updatedAt = guardado.updatedAt,
                    updatedBy = guardado.updatedBy,
                )
            }
        }
    }

    private fun stubModelo(
        id: Long = 7,
        codigo: String = "BUS-X",
        precioBase: BigDecimal? = BigDecimal("100.00"),
    ) {
        every { modeloService.resumen(id) } returns ModeloResumen(id = id, codigo = codigo, precioBase = precioBase)
    }

    @Test
    fun `vinculoVisible de un item de oportunidad ajena responde 404, nunca 403`() {
        every { itemRepository.findById(500) } returns Optional.of(item(id = 500))
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad(idVendedor = 99))

        assertThatThrownBy { service.vinculoVisible(500, vendedor) }
            .isInstanceOf(NoEncontradoException::class.java)
    }

    @Test
    fun `vinculoVisible devuelve el vinculo minimo del item propio`() {
        every { itemRepository.findById(500) } returns Optional.of(item(id = 500))
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())

        val vinculo = service.vinculoVisible(500, vendedor)

        assertThat(vinculo.id).isEqualTo(500)
        assertThat(vinculo.idOportunidad).isEqualTo(100)
        assertThat(vinculo.idEmpresa).isEqualTo(10)
        assertThat(vinculo.descuento).isEqualByComparingTo(BigDecimal("10.00"))
    }

    @Test
    fun `crear con descuento sobre el limite del rol exige aprobacion`() {
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())

        assertThatThrownBy {
            service.crear(
                100,
                CrearOportunidadItemRequest(idModelo = 7, cantidad = 1, descuento = BigDecimal("10")),
                vendedor,
            )
        }.isInstanceOf(AprobacionRequeridaException::class.java)

        verify(exactly = 0) { itemRepository.save(any()) }
    }

    @Test
    fun `crear sin precio_venta toma el precio base del modelo`() {
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())
        stubModelo(precioBase = BigDecimal("77.00"))
        stubGuardadoDeItem()

        val dto = service.crear(100, CrearOportunidadItemRequest(idModelo = 7, cantidad = 1), vendedor)

        assertThat(dto.precioVenta).isEqualTo("77.00")
    }

    @Test
    fun `crear sobre una oportunidad ajena responde 404`() {
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad(idVendedor = 99))

        assertThatThrownBy {
            service.crear(100, CrearOportunidadItemRequest(idModelo = 7, cantidad = 1), vendedor)
        }.isInstanceOf(NoEncontradoException::class.java)
    }

    @Test
    fun `actualizar aplica solo los campos presentes`() {
        val existente = item(id = 500)
        every { itemRepository.findById(500) } returns Optional.of(existente)
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())
        stubModelo()
        stubGuardadoDeItem()

        val dto = service.actualizar(500, ActualizarOportunidadItemRequest(cantidad = 4), vendedor)

        assertThat(dto.cantidad).isEqualTo(4)
        assertThat(existente.precioVenta).isEqualByComparingTo(BigDecimal("100.00"))
    }

    // `reglas_negocio.md §12.2` (cambio de modelo), reimplementada sobre el item:
    // hasta D19 la regla vivia en `OportunidadServiceImpl.actualizar()` y estaba
    // cubierta por `OportunidadActualizarTest`; B7 la saco de ahi y aqui vuelve.

    @Test
    fun `actualizar el modelo pisa el precio si seguia siendo el precio base del modelo anterior`() {
        val op = oportunidad()
        val existente = item(id = 500, idModelo = 7, precioVenta = BigDecimal("100.00"))
        every { itemRepository.findById(500) } returns Optional.of(existente)
        every { oportunidadRepository.findById(100) } returns Optional.of(op)
        stubModelo()
        stubModelo(id = 9, codigo = "BUS-Z", precioBase = BigDecimal("50.00"))
        stubGuardadoDeItem()

        val dto = service.actualizar(500, ActualizarOportunidadItemRequest(idModelo = 9), vendedor)

        assertThat(existente.idModelo).isEqualTo(9)
        assertThat(existente.precioVenta).isEqualByComparingTo(BigDecimal("50.00"))
        assertThat(dto.precioVenta).isEqualTo("50.00")
        assertThat(dto.advertencias).isEmpty()
    }

    @Test
    fun `actualizar el modelo conserva el precio editado a mano y devuelve la advertencia`() {
        val op = oportunidad()
        val existente = item(id = 500, idModelo = 7, precioVenta = BigDecimal("120.00"))
        every { itemRepository.findById(500) } returns Optional.of(existente)
        every { oportunidadRepository.findById(100) } returns Optional.of(op)
        stubModelo()
        stubModelo(id = 9, codigo = "BUS-Z", precioBase = BigDecimal("50.00"))
        stubGuardadoDeItem()

        val dto = service.actualizar(500, ActualizarOportunidadItemRequest(idModelo = 9), vendedor)

        assertThat(existente.idModelo).isEqualTo(9)
        assertThat(existente.precioVenta).isEqualByComparingTo(BigDecimal("120.00"))
        assertThat(dto.precioVenta).isEqualTo("120.00")
        assertThat(dto.advertencias)
            .containsExactly("El precio unitario fue editado manualmente y no se actualizó con el nuevo modelo")
    }

    /**
     * §12.2 con precio previo NULL: la condicion de "no editado" es
     * `item.precioVenta == null || ...`, asi que se toma el precio base del modelo
     * nuevo sin comparar nada contra el modelo anterior.
     */
    @Test
    fun `actualizar el modelo con precio previo nulo toma el precio base del modelo nuevo`() {
        val op = oportunidad()
        val existente = item(id = 500, idModelo = 7, precioVenta = null)
        every { itemRepository.findById(500) } returns Optional.of(existente)
        every { oportunidadRepository.findById(100) } returns Optional.of(op)
        stubModelo()
        stubModelo(id = 9, codigo = "BUS-Z", precioBase = BigDecimal("50.00"))
        stubGuardadoDeItem()

        val dto = service.actualizar(500, ActualizarOportunidadItemRequest(idModelo = 9), vendedor)

        assertThat(existente.idModelo).isEqualTo(9)
        assertThat(existente.precioVenta).isEqualByComparingTo(BigDecimal("50.00"))
        assertThat(dto.precioVenta).isEqualTo("50.00")
        assertThat(dto.advertencias).isEmpty()
    }

    /**
     * Rama fail-safe de §12.2: si el modelo ANTERIOR no tiene `precio_base`
     * configurado no hay con que comparar, asi que no se puede afirmar que el precio
     * actual siga siendo el de catalogo. Se conserva y se advierte, aunque "parezca"
     * no editado.
     */
    @Test
    fun `actualizar el modelo conserva el precio si el modelo anterior no tiene precio base`() {
        val op = oportunidad()
        val existente = item(id = 500, idModelo = 7, precioVenta = BigDecimal("100.00"))
        every { itemRepository.findById(500) } returns Optional.of(existente)
        every { oportunidadRepository.findById(100) } returns Optional.of(op)
        stubModelo(precioBase = null)
        stubModelo(id = 9, codigo = "BUS-Z", precioBase = BigDecimal("50.00"))
        stubGuardadoDeItem()

        val dto = service.actualizar(500, ActualizarOportunidadItemRequest(idModelo = 9), vendedor)

        assertThat(existente.idModelo).isEqualTo(9)
        assertThat(existente.precioVenta).isEqualByComparingTo(BigDecimal("100.00"))
        assertThat(dto.advertencias)
            .containsExactly("El precio unitario fue editado manualmente y no se actualizó con el nuevo modelo")
    }

    /**
     * `request.precioVenta` se aplica DESPUES del bloque de cambio de modelo, asi que
     * un precio explicito en el mismo request gana sobre lo que decidiera §12.2 (aqui
     * §12.2 habria puesto el precio base del modelo nuevo, 50.00).
     */
    @Test
    fun `actualizar con modelo y precio explicito en el mismo request deja mandar al precio del request`() {
        val op = oportunidad()
        val existente = item(id = 500, idModelo = 7, precioVenta = BigDecimal("100.00"))
        every { itemRepository.findById(500) } returns Optional.of(existente)
        every { oportunidadRepository.findById(100) } returns Optional.of(op)
        stubModelo()
        stubModelo(id = 9, codigo = "BUS-Z", precioBase = BigDecimal("50.00"))
        stubGuardadoDeItem()

        val dto =
            service.actualizar(
                500,
                ActualizarOportunidadItemRequest(idModelo = 9, precioVenta = BigDecimal("88.00")),
                vendedor,
            )

        assertThat(existente.idModelo).isEqualTo(9)
        assertThat(existente.precioVenta).isEqualByComparingTo(BigDecimal("88.00"))
        assertThat(dto.precioVenta).isEqualTo("88.00")
    }

    /** Reenviar el MISMO `id_modelo` no entra al bloque de §12.2: no hay consulta extra al catalogo. */
    @Test
    fun `actualizar reenviando el mismo modelo no vuelve a consultar el catalogo`() {
        val op = oportunidad()
        val existente = item(id = 500, idModelo = 7)
        every { itemRepository.findById(500) } returns Optional.of(existente)
        every { oportunidadRepository.findById(100) } returns Optional.of(op)
        stubModelo()
        stubGuardadoDeItem()

        service.actualizar(500, ActualizarOportunidadItemRequest(idModelo = 7, cantidad = 3), vendedor)

        // Solo la resolucion final para armar el DTO; ni modelo nuevo ni precio base anterior.
        verify(exactly = 1) { modeloService.resumen(7) }
        assertThat(existente.precioVenta).isEqualByComparingTo(BigDecimal("100.00"))
    }

    @Test
    fun `actualizar con descuento sobre el limite del rol exige aprobacion`() {
        every { itemRepository.findById(500) } returns Optional.of(item(id = 500))
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())

        assertThatThrownBy {
            service.actualizar(500, ActualizarOportunidadItemRequest(descuento = BigDecimal("9")), vendedor)
        }.isInstanceOf(AprobacionRequeridaException::class.java)

        // M6: el test original en `OportunidadServiceImplTest` verificaba que no se
        // guardara nada; el equivalente sobre el repositorio de items.
        verify(exactly = 0) { itemRepository.save(any()) }
    }

    @Test
    fun `eliminar el unico item de una oportunidad responde 409 y no borra nada`() {
        val soloUno = item(id = 500)
        every { itemRepository.findById(500) } returns Optional.of(soloUno)
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())
        every { itemRepository.findByIdOportunidadOrderByIdAsc(100) } returns listOf(soloUno)

        assertThatThrownBy { service.eliminar(500, vendedor) }
            .isInstanceOf(ConflictoException::class.java)
            .satisfies({ assertThat((it as ConflictoException).code).isEqualTo("ULTIMO_ITEM_NO_ELIMINABLE") })

        verify(exactly = 0) { itemRepository.delete(any()) }
        verify(exactly = 0) { oportunidadRepository.save(any()) }
    }

    @Test
    fun `eliminar uno de dos items borra el item`() {
        val primero = item(id = 500, idModelo = 7)
        val segundo = item(id = 501, idModelo = 9, cantidad = 3, precioVenta = BigDecimal("50.00"), descuento = null)
        every { itemRepository.findById(501) } returns Optional.of(segundo)
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())
        every { itemRepository.delete(segundo) } returns Unit
        every { itemRepository.findByIdOportunidadOrderByIdAsc(100) } returns listOf(primero, segundo)

        service.eliminar(501, vendedor)

        verify { itemRepository.delete(segundo) }
    }

    @Test
    fun `porOportunidades agrupa por oportunidad y resuelve los modelos por lotes`() {
        val deOtraOportunidad =
            OportunidadItem(
                id = 501,
                idOportunidad = 200,
                idModelo = 9,
                cantidad = 1,
                precioVenta = BigDecimal("50.00"),
                descuento = null,
                createdBy = 1,
                updatedBy = 1,
            )
        every { itemRepository.findByIdOportunidadInOrderByIdAsc(listOf(100L, 200L)) } returns
            listOf(item(id = 500, idModelo = 7), deOtraOportunidad)
        every { modeloService.resumenPorIds(any()) } returns
            mapOf(
                7L to ModeloResumen(id = 7, codigo = "BUS-X", precioBase = BigDecimal("100.00")),
                9L to ModeloResumen(id = 9, codigo = "BUS-Z", precioBase = BigDecimal("50.00")),
            )

        val porOportunidad = service.porOportunidades(listOf(100L, 200L))

        assertThat(porOportunidad.keys).containsExactlyInAnyOrder(100L, 200L)
        assertThat(porOportunidad[100]?.single()?.modelo?.codigo).isEqualTo("BUS-X")
        assertThat(porOportunidad[100]?.single()?.montoItem).isEqualTo("180.00")
        assertThat(porOportunidad[200]?.single()?.montoItem).isEqualTo("50.00")
        assertThat(porOportunidad[100]?.single()?.advertencias).isEmpty()
    }

    @Test
    fun `porOportunidades sin ids no consulta el repositorio`() {
        assertThat(service.porOportunidades(emptyList())).isEmpty()
    }

    // ── aplicarDescuentoAprobado (B12) ─────────────────────────

    @Test
    fun `aplicarDescuentoAprobado setea el descuento del item`() {
        val unico = item(id = 500)
        every { itemRepository.findById(500) } returns Optional.of(unico)
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())
        every { itemRepository.save(any()) } answers { firstArg() }

        service.aplicarDescuentoAprobado(500, BigDecimal("5.00"), idAprobador = 2)

        assertThat(unico.descuento).isEqualByComparingTo(BigDecimal("5.00"))
        assertThat(unico.updatedBy).isEqualTo(2)
    }

    @Test
    fun `aplicarDescuentoAprobado sobre un item borrado es SOLICITUD_NO_APLICABLE`() {
        every { itemRepository.findById(999) } returns Optional.empty()

        assertThatThrownBy { service.aplicarDescuentoAprobado(999, BigDecimal("5.00"), idAprobador = 2) }
            .isInstanceOf(ConflictoException::class.java)
            .hasMessageContaining("ya no existe")
    }

    @Test
    fun `aplicarDescuentoAprobado sobre una oportunidad cerrada es SOLICITUD_NO_APLICABLE`() {
        val unico = item(id = 500)
        val cerrada = oportunidad().apply { estado = EstadoOportunidad.cerrado }
        every { itemRepository.findById(500) } returns Optional.of(unico)
        every { oportunidadRepository.findById(100) } returns Optional.of(cerrada)

        assertThatThrownBy { service.aplicarDescuentoAprobado(500, BigDecimal("5.00"), idAprobador = 2) }
            .isInstanceOf(ConflictoException::class.java)
            .hasMessageContaining("el descuento ya no aplica")
        assertThat(unico.descuento).isEqualByComparingTo(BigDecimal("10.00"))
        verify(exactly = 0) { itemRepository.save(any()) }
    }

    @Test
    fun `un rol de apoyo no puede crear items`() {
        val analista = UsuarioActual(id = 5, rol = "analista")

        assertThatThrownBy {
            service.crear(100, CrearOportunidadItemRequest(idModelo = 7, cantidad = 1), analista)
        }.isInstanceOf(PermisoInsuficienteException::class.java)
    }

    @Test
    fun `un rol de apoyo no puede actualizar items`() {
        val analista = UsuarioActual(id = 5, rol = "analista")

        assertThatThrownBy {
            service.actualizar(500, ActualizarOportunidadItemRequest(cantidad = 3), analista)
        }.isInstanceOf(PermisoInsuficienteException::class.java)

        verify(exactly = 0) { itemRepository.save(any()) }
    }

    @Test
    fun `un rol de apoyo no puede eliminar items`() {
        val otro = UsuarioActual(id = 6, rol = "otro")

        assertThatThrownBy { service.eliminar(500, otro) }
            .isInstanceOf(PermisoInsuficienteException::class.java)

        verify(exactly = 0) { itemRepository.delete(any()) }
    }

    // ── datosParaSimulacion (D4) ─────────────────────

    /**
     * Instancia aparte con `OportunidadVisibilidad` MOCKEADO: el contrato de D32 es
     * que este metodo no aplica la visibilidad de oportunidades, y como la firma no
     * recibe `UsuarioActual`, la unica forma de comprobarlo es verificar que la
     * colaboracion nunca se invoca. El `service` de arriba usa la visibilidad real y
     * no se toca.
     */
    private val visibilidadMock = mockk<OportunidadVisibilidad>()

    private val servicioSinVisibilidad =
        OportunidadItemServiceImpl(itemRepository, oportunidadRepository, modeloService, visibilidadMock)

    private fun oportunidadCon(
        id: Long,
        idEmpresa: Long,
        idVendedor: Long,
    ) = Oportunidad(
        id = id,
        idEmpresa = idEmpresa,
        idVendedor = idVendedor,
        idFinanciadora = 1,
        estado = EstadoOportunidad.evaluacion_calidda,
        createdAt = LocalDateTime.now(),
        createdBy = 1,
        updatedAt = LocalDateTime.now(),
        updatedBy = 1,
    )

    @Suppress("LongParameterList") // Refleja las columnas de `OportunidadItem`, igual que la entidad.
    private fun itemDe(
        id: Long,
        idOportunidad: Long,
        idModelo: Long = 7,
        cantidad: Int? = 2,
        precioVenta: BigDecimal? = BigDecimal("100.00"),
        descuento: BigDecimal? = BigDecimal("10.00"),
        cuotaFinanciadora: BigDecimal = BigDecimal("937.50"),
    ) = OportunidadItem(
        id = id,
        idOportunidad = idOportunidad,
        idModelo = idModelo,
        cantidad = cantidad,
        precioVenta = precioVenta,
        descuento = descuento,
        cuotaFinanciadora = cuotaFinanciadora,
        createdBy = 1,
        updatedBy = 1,
    )

    @Test
    fun `datosParaSimulacion resuelve empresa y vendedor de la oportunidad dueña de cada item`() {
        every { itemRepository.findAllById(listOf(500L, 501L)) } returns
            listOf(
                itemDe(id = 500, idOportunidad = 100),
                itemDe(
                    id = 501,
                    idOportunidad = 200,
                    idModelo = 9,
                    cantidad = 3,
                    precioVenta = BigDecimal("50.00"),
                    descuento = null,
                    cuotaFinanciadora = BigDecimal("1200.00"),
                ),
            )
        every { oportunidadRepository.findAllById(listOf(100L, 200L)) } returns
            listOf(
                oportunidadCon(id = 100, idEmpresa = 10, idVendedor = 1),
                oportunidadCon(id = 200, idEmpresa = 20, idVendedor = 99),
            )

        val datos = servicioSinVisibilidad.datosParaSimulacion(listOf(500L, 501L))

        assertThat(datos.keys).containsExactlyInAnyOrder(500L, 501L)
        val primero = requireNotNull(datos[500L])
        assertThat(primero.id).isEqualTo(500L)
        assertThat(primero.idOportunidad).isEqualTo(100L)
        assertThat(primero.idEmpresa).isEqualTo(10L)
        assertThat(primero.idVendedor).isEqualTo(1L)
        assertThat(primero.idModelo).isEqualTo(7L)
        assertThat(primero.cantidad).isEqualTo(2)
        assertThat(primero.precioVenta).isEqualByComparingTo(BigDecimal("100.00"))
        assertThat(primero.descuento).isEqualByComparingTo(BigDecimal("10.00"))
        assertThat(primero.cuotaFinanciadora).isEqualByComparingTo(BigDecimal("937.50"))

        val segundo = requireNotNull(datos[501L])
        assertThat(segundo.idOportunidad).isEqualTo(200L)
        assertThat(segundo.idEmpresa).isEqualTo(20L)
        assertThat(segundo.idVendedor).isEqualTo(99L)
        assertThat(segundo.idModelo).isEqualTo(9L)
        assertThat(segundo.cantidad).isEqualTo(3)
        assertThat(segundo.precioVenta).isEqualByComparingTo(BigDecimal("50.00"))
        assertThat(segundo.descuento).isNull()
        assertThat(segundo.cuotaFinanciadora).isEqualByComparingTo(BigDecimal("1200.00"))
    }

    @Test
    fun `datosParaSimulacion sin ids devuelve vacio y no consulta ningun repositorio`() {
        assertThat(servicioSinVisibilidad.datosParaSimulacion(emptyList())).isEmpty()

        verify(exactly = 0) { itemRepository.findAllById(any()) }
        verify(exactly = 0) { oportunidadRepository.findAllById(any()) }
    }

    @Test
    fun `datosParaSimulacion omite del mapa el item inexistente en vez de lanzar`() {
        every { itemRepository.findAllById(listOf(500L, 999L)) } returns listOf(itemDe(id = 500, idOportunidad = 100))
        every { oportunidadRepository.findAllById(listOf(100L)) } returns
            listOf(oportunidadCon(id = 100, idEmpresa = 10, idVendedor = 1))

        val datos = servicioSinVisibilidad.datosParaSimulacion(listOf(500L, 999L))

        assertThat(datos.keys).containsExactly(500L)
    }

    /**
     * D32: el metodo NO aplica la visibilidad de oportunidades. La oportunidad de
     * este item es de otro vendedor (99) y aun asi los datos salen; y sobre todo,
     * `OportunidadVisibilidad` no se consulta en absoluto.
     */
    @Test
    fun `datosParaSimulacion no aplica la visibilidad de oportunidades`() {
        every { itemRepository.findAllById(listOf(500L)) } returns listOf(itemDe(id = 500, idOportunidad = 100))
        every { oportunidadRepository.findAllById(listOf(100L)) } returns
            listOf(oportunidadCon(id = 100, idEmpresa = 10, idVendedor = 99))

        val datos = servicioSinVisibilidad.datosParaSimulacion(listOf(500L))

        assertThat(datos).containsKey(500L)
        verify(exactly = 0) { visibilidadMock.alcanza(any(), any()) }
        verify(exactly = 0) { visibilidadMock.rechazarSiEsApoyo(any()) }
        verify(exactly = 0) { visibilidadMock.idsColaboracion(any()) }
    }
}
