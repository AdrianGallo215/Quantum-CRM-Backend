package pe.quantum.crm.shared.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UsuarioActualTest {
    @Test
    fun `gerencia es supervisor, valida facturado, ve cartera maestra y reasigna directo`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        assertThat(gerencia.esSupervisor).isTrue()
        assertThat(gerencia.puedeValidarFacturado).isTrue()
        assertThat(gerencia.puedeVerCarteraMaestra).isTrue()
        assertThat(gerencia.puedeReasignarDirecto).isTrue()
        assertThat(gerencia.visibilidadRestringida).isFalse()
    }

    @Test
    fun `jdv no ve cartera maestra ni reasigna directo, pero sigue siendo supervisor`() {
        val jdv = UsuarioActual(id = 2, rol = "jdv")
        assertThat(jdv.esSupervisor).isTrue()
        assertThat(jdv.puedeVerCarteraMaestra).isFalse()
        assertThat(jdv.puedeReasignarDirecto).isFalse()
    }

    @Test
    fun `puedeAprobar - admin aprueba ambas bandejas, cada rol solo la suya`() {
        assertThat(UsuarioActual(1, "admin").puedeAprobar("jdv")).isTrue()
        assertThat(UsuarioActual(1, "admin").puedeAprobar("gerencia")).isTrue()
        assertThat(UsuarioActual(2, "gerencia").puedeAprobar("gerencia")).isTrue()
        assertThat(UsuarioActual(2, "gerencia").puedeAprobar("jdv")).isFalse()
        assertThat(UsuarioActual(3, "jdv").puedeAprobar("jdv")).isTrue()
        assertThat(UsuarioActual(3, "jdv").puedeAprobar("gerencia")).isFalse()
        assertThat(UsuarioActual(4, "vendedor").puedeAprobar("jdv")).isFalse()
    }

    @Test
    fun `el rol gerente ya no existe - gerencia lo reemplaza en todos los checks`() {
        val exGerente = UsuarioActual(id = 1, rol = "gerente")
        // Un token viejo con "gerente" queda sin privilegios: debe re-loguear.
        assertThat(exGerente.esSupervisor).isFalse()
        assertThat(exGerente.puedeValidarFacturado).isFalse()
    }

    @Test
    fun `analista y otro son roles de apoyo`() {
        assertThat(UsuarioActual(1L, "analista").esRolApoyo).isTrue()
        assertThat(UsuarioActual(2L, "otro").esRolApoyo).isTrue()
    }

    @Test
    fun `los roles comerciales y supervisores no son de apoyo`() {
        assertThat(UsuarioActual(3L, "vendedor").esRolApoyo).isFalse()
        assertThat(UsuarioActual(4L, "jdv").esRolApoyo).isFalse()
        assertThat(UsuarioActual(5L, "gerencia").esRolApoyo).isFalse()
        assertThat(UsuarioActual(6L, "admin").esRolApoyo).isFalse()
    }

    @Test
    fun `el analista ya no puede validar el paso a facturado`() {
        assertThat(UsuarioActual(1L, "analista").puedeValidarFacturado).isFalse()
        assertThat(UsuarioActual(2L, "otro").puedeValidarFacturado).isFalse()
    }

    @Test
    fun `admin y gerencia siguen validando el paso a facturado`() {
        assertThat(UsuarioActual(5L, "gerencia").puedeValidarFacturado).isTrue()
        assertThat(UsuarioActual(6L, "admin").puedeValidarFacturado).isTrue()
    }
}
