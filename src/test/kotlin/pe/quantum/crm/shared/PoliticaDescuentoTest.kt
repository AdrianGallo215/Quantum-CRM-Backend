package pe.quantum.crm.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.shared.enums.AprobadorSolicitud
import java.math.BigDecimal

class PoliticaDescuentoTest {
    @Test
    fun `limites por rol - vendedor y analista 3, jdv 7, gerencia y admin sin limite`() {
        assertThat(PoliticaDescuento.limitePara("vendedor")).isEqualByComparingTo(BigDecimal(3))
        assertThat(PoliticaDescuento.limitePara("analista")).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(PoliticaDescuento.limitePara("jdv")).isEqualByComparingTo(BigDecimal(7))
        assertThat(PoliticaDescuento.limitePara("gerencia")).isNull()
        assertThat(PoliticaDescuento.limitePara("admin")).isNull()
    }

    @Test
    fun `excedeLimite - bordes exactos aplican directo`() {
        assertThat(PoliticaDescuento.excedeLimite("vendedor", BigDecimal("3.00"))).isFalse()
        assertThat(PoliticaDescuento.excedeLimite("vendedor", BigDecimal("3.01"))).isTrue()
        assertThat(PoliticaDescuento.excedeLimite("jdv", BigDecimal("7.00"))).isFalse()
        assertThat(PoliticaDescuento.excedeLimite("jdv", BigDecimal("7.50"))).isTrue()
        assertThat(PoliticaDescuento.excedeLimite("gerencia", BigDecimal("50"))).isFalse()
        assertThat(PoliticaDescuento.excedeLimite("vendedor", null)).isFalse()
    }

    @Test
    fun `limitePara - un rol desconocido cae en el limite mas bajo, no en sin limite`() {
        // 'otro' es un valor real de RolEmpleado; 'gerente' es el nombre viejo que
        // V25 renombro a 'gerencia'. Ninguno de los dos puede quedar sin limite.
        assertThat(PoliticaDescuento.limitePara("gerente")).isEqualByComparingTo(BigDecimal(3))
        assertThat(PoliticaDescuento.limitePara("")).isEqualByComparingTo(BigDecimal(3))
        assertThat(PoliticaDescuento.limitePara("rol_que_no_existe")).isEqualByComparingTo(BigDecimal(3))
    }

    @Test
    fun `excedeLimite - un rol desconocido no puede aplicar un descuento grande sin aprobacion`() {
        assertThat(PoliticaDescuento.excedeLimite("otro", BigDecimal("90"))).isTrue()
        assertThat(PoliticaDescuento.excedeLimite("gerente", BigDecimal("90"))).isTrue()
        assertThat(PoliticaDescuento.excedeLimite("rol_que_no_existe", BigDecimal("3.01"))).isTrue()
        assertThat(PoliticaDescuento.excedeLimite("otro", BigDecimal("3.00"))).isTrue()
        assertThat(PoliticaDescuento.excedeLimite("otro", null)).isFalse()
    }

    @Test
    fun `aprobadorPara - un rol desconocido escala a gerencia, nunca queda sin solicitud`() {
        assertThat(PoliticaDescuento.aprobadorPara("otro", BigDecimal("90"))).isEqualTo(AprobadorSolicitud.gerencia)
        assertThat(PoliticaDescuento.aprobadorPara("otro", BigDecimal("5"))).isEqualTo(AprobadorSolicitud.gerencia)
        assertThat(PoliticaDescuento.aprobadorPara("gerente", BigDecimal("8"))).isEqualTo(AprobadorSolicitud.gerencia)
        assertThat(PoliticaDescuento.aprobadorPara("otro", BigDecimal("2"))).isEqualTo(AprobadorSolicitud.gerencia)
    }

    @Test
    fun `aprobadorPara - vendedor 3-7 va al jdv, mas de 7 a gerencia, jdv siempre a gerencia`() {
        assertThat(PoliticaDescuento.aprobadorPara("vendedor", BigDecimal("5"))).isEqualTo(AprobadorSolicitud.jdv)
        assertThat(PoliticaDescuento.aprobadorPara("vendedor", BigDecimal("7.00"))).isEqualTo(AprobadorSolicitud.jdv)
        assertThat(PoliticaDescuento.aprobadorPara("vendedor", BigDecimal("8"))).isEqualTo(AprobadorSolicitud.gerencia)
        assertThat(PoliticaDescuento.aprobadorPara("analista", BigDecimal("5"))).isEqualTo(AprobadorSolicitud.gerencia)
        assertThat(PoliticaDescuento.aprobadorPara("jdv", BigDecimal("9"))).isEqualTo(AprobadorSolicitud.gerencia)
        assertThat(PoliticaDescuento.aprobadorPara("vendedor", BigDecimal("2"))).isNull()
        assertThat(PoliticaDescuento.aprobadorPara("jdv", BigDecimal("5"))).isNull()
        assertThat(PoliticaDescuento.aprobadorPara("gerencia", BigDecimal("90"))).isNull()
    }

    @Test
    fun `los roles de apoyo no tienen margen de descuento`() {
        assertThat(PoliticaDescuento.limitePara("analista")).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(PoliticaDescuento.limitePara("otro")).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `cualquier descuento de un rol de apoyo excede su limite`() {
        assertThat(PoliticaDescuento.excedeLimite("analista", BigDecimal("0.01"))).isTrue()
        assertThat(PoliticaDescuento.excedeLimite("otro", BigDecimal("1"))).isTrue()
    }

    @Test
    fun `un descuento de cero no excede el limite de un rol de apoyo`() {
        assertThat(PoliticaDescuento.excedeLimite("analista", BigDecimal.ZERO)).isFalse()
        assertThat(PoliticaDescuento.excedeLimite("analista", null)).isFalse()
    }

    @Test
    fun `el vendedor conserva su limite de 3 por ciento`() {
        assertThat(PoliticaDescuento.limitePara("vendedor")).isEqualByComparingTo(BigDecimal(3))
        assertThat(PoliticaDescuento.excedeLimite("vendedor", BigDecimal(3))).isFalse()
        assertThat(PoliticaDescuento.excedeLimite("vendedor", BigDecimal("3.01"))).isTrue()
    }

    @Test
    fun `el jdv conserva su limite de 7 por ciento`() {
        assertThat(PoliticaDescuento.limitePara("jdv")).isEqualByComparingTo(BigDecimal(7))
    }

    @Test
    fun `admin y gerencia siguen sin limite`() {
        assertThat(PoliticaDescuento.limitePara("admin")).isNull()
        assertThat(PoliticaDescuento.limitePara("gerencia")).isNull()
    }
}
