package pe.quantum.crm.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NombresDeCampoTest {
    @Test
    fun `una sola palabra se queda igual`() {
        assertThat("email".aCampoSnakeCase()).isEqualTo("email")
    }

    @Test
    fun `camelCase se convierte a snake_case`() {
        assertThat("idContacto".aCampoSnakeCase()).isEqualTo("id_contacto")
        assertThat("rolEnOportunidad".aCampoSnakeCase()).isEqualTo("rol_en_oportunidad")
    }

    /** Un campo anidado con indice de array no debe perder el indice al convertir. */
    @Test
    fun `un campo anidado con indice de array conserva el indice`() {
        assertThat("contactos[0].idContacto".aCampoSnakeCase()).isEqualTo("contactos[0].id_contacto")
    }
}
