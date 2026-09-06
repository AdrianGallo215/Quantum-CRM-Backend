package pe.quantum.crm.domain.simulaciones

import pe.quantum.crm.domain.simulaciones.dto.CalculadoraDto
import pe.quantum.crm.domain.simulaciones.dto.CalculadoraRequest
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * API publica de la Calculadora Financiera (`reglas_simulaciones.md` §9):
 * estimacion rapida durante la prospeccion, con el mismo motor y las mismas
 * validaciones §13 que `SimulacionService.crear`, pero **sin ninguna
 * escritura** — ni en `simulaciones`, ni en `simulacion_log`, ni en exito ni en
 * fallo. §9 es explicito: "no deja rastro de auditoria".
 *
 * Es un servicio propio y no un metodo mas de [SimulacionService] a proposito
 * (decision D50 de plan-11-mapa-historial-calculadora.md): §9 la llama "modulo
 * aparte", y colgarla del CRUD sugeriria que comparte su ciclo de vida cuando
 * la premisa entera es que no persiste.
 *
 * El boton "Enlazar a Oportunidad" NO vive aqui (hallazgo K26): es el frontend
 * llamando a `POST /simulaciones` con los mismos parametros mas el
 * `idOportunidadItem` elegido. No hay endpoint de "enlazar".
 */
interface CalculadoraFinancieraService {
    /**
     * Calcula el cronograma completo y lo devuelve sin guardarlo.
     *
     * 403 (`PermisoInsuficienteException`) para `jdv` y `otro`: es exactamente
     * la columna "Calculadora Financiera" de §10, ya implementada por
     * `SimulacionPermisos.exigirAcceso` (K26). El `vendedor` SI entra.
     */
    fun calcular(
        request: CalculadoraRequest,
        usuario: UsuarioActual,
    ): CalculadoraDto
}
