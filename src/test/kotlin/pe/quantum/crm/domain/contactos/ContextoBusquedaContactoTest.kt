package pe.quantum.crm.domain.contactos

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.contactos.dto.ContextoBusquedaContacto
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * El enum es la unica fuente de verdad de "en que modo esta esta peticion".
 * Si el controller o el servicio reimplementaran la regla, los dos modos podrian
 * divergir y el reducido dejaria de serlo por una de las dos puertas.
 */
class ContextoBusquedaContactoTest {
    private val analista = UsuarioActual(id = 7, rol = "analista")
    private val otro = UsuarioActual(id = 8, rol = "otro")
    private val vendedor = UsuarioActual(id = 42, rol = "vendedor")
    private val jdv = UsuarioActual(id = 3, rol = "jdv")
    private val admin = UsuarioActual(id = 1, rol = "admin")

    /**
     * El default NO puede ser `vincular`: un frontend viejo que todavia no manda
     * el parametro abriria la busqueda global sin que nadie lo pidiera.
     */
    @Test
    fun `contexto ausente, vacio o en blanco cae en listado, que es el modo restrictivo`() {
        assertThat(ContextoBusquedaContacto.desde(null)).isEqualTo(ContextoBusquedaContacto.listado)
        assertThat(ContextoBusquedaContacto.desde("")).isEqualTo(ContextoBusquedaContacto.listado)
        assertThat(ContextoBusquedaContacto.desde("   ")).isEqualTo(ContextoBusquedaContacto.listado)
    }

    @Test
    fun `contexto vincular se reconoce`() {
        assertThat(ContextoBusquedaContacto.desde("vincular")).isEqualTo(ContextoBusquedaContacto.vincular)
    }

    @Test
    fun `contexto listado explicito se reconoce`() {
        assertThat(ContextoBusquedaContacto.desde("listado")).isEqualTo(ContextoBusquedaContacto.listado)
    }

    @Test
    fun `contexto con espacios alrededor se normaliza`() {
        assertThat(ContextoBusquedaContacto.desde("  vincular  ")).isEqualTo(ContextoBusquedaContacto.vincular)
    }

    /**
     * Un valor fuera del enum es un error del cliente (400), no un filtro que se
     * ignora: mismo criterio que `?estado_cartera=` en EmpresaServiceImpl.
     */
    @Test
    fun `un contexto fuera del enum lanza ValidacionException y nombra los permitidos`() {
        assertThatThrownBy { ContextoBusquedaContacto.desde("global") }
            .isInstanceOf(ValidacionException::class.java)
            .hasMessageContaining("listado")
            .hasMessageContaining("vincular")
    }

    @Test
    fun `el contexto invalido apunta al campo contexto`() {
        val error = assertThatThrownBy { ContextoBusquedaContacto.desde("VINCULAR") }
        error.isInstanceOf(ValidacionException::class.java)
        val lanzado =
            org.assertj.core.api.Assertions.catchThrowable { ContextoBusquedaContacto.desde("VINCULAR") } as ValidacionException
        assertThat(lanzado.field).isEqualTo("contexto")
    }

    @Test
    fun `solo un rol de apoyo en contexto vincular recibe la respuesta reducida`() {
        assertThat(ContextoBusquedaContacto.vincular.esReducidoPara(analista)).isTrue()
        assertThat(ContextoBusquedaContacto.vincular.esReducidoPara(otro)).isTrue()
        assertThat(ContextoBusquedaContacto.vincular.esReducidoPara(vendedor)).isFalse()
        assertThat(ContextoBusquedaContacto.vincular.esReducidoPara(jdv)).isFalse()
        assertThat(ContextoBusquedaContacto.vincular.esReducidoPara(admin)).isFalse()
        assertThat(ContextoBusquedaContacto.listado.esReducidoPara(analista)).isFalse()
        assertThat(ContextoBusquedaContacto.listado.esReducidoPara(otro)).isFalse()
    }

    @Test
    fun `solo un rol de apoyo en contexto listado arrastra el filtro de visibilidad`() {
        assertThat(ContextoBusquedaContacto.listado.aplicaFiltroDeVisibilidadPara(analista)).isTrue()
        assertThat(ContextoBusquedaContacto.listado.aplicaFiltroDeVisibilidadPara(otro)).isTrue()
        assertThat(ContextoBusquedaContacto.listado.aplicaFiltroDeVisibilidadPara(vendedor)).isFalse()
        assertThat(ContextoBusquedaContacto.listado.aplicaFiltroDeVisibilidadPara(jdv)).isFalse()
        assertThat(ContextoBusquedaContacto.listado.aplicaFiltroDeVisibilidadPara(admin)).isFalse()
        assertThat(ContextoBusquedaContacto.vincular.aplicaFiltroDeVisibilidadPara(analista)).isFalse()
    }

    /** Los dos modos son excluyentes: ninguna combinacion activa ambos a la vez. */
    @Test
    fun `ningun usuario cae a la vez en modo reducido y en filtro de visibilidad`() {
        listOf(analista, otro, vendedor, jdv, admin).forEach { usuario ->
            ContextoBusquedaContacto.entries.forEach { contexto ->
                assertThat(contexto.esReducidoPara(usuario) && contexto.aplicaFiltroDeVisibilidadPara(usuario))
                    .describedAs("rol %s en contexto %s", usuario.rol, contexto.name)
                    .isFalse()
            }
        }
    }
}
