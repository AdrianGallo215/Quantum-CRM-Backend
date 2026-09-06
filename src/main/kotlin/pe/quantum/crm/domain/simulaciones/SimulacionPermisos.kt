package pe.quantum.crm.domain.simulaciones

import org.springframework.stereotype.Component
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Unico punto de decision de autorizacion del modulo `simulaciones`
 * (`reglas_simulaciones.md` §10 lo exige: "debe estar centralizado en un solo
 * punto de decision, no disperso en condicionales por endpoint", y el reparto es
 * candidato a cambiar).
 *
 * **No usa `UsuarioActual.esRolApoyo`, `esSupervisor` ni `visibilidadRestringida`
 * a proposito**: ninguno parte los roles como los parte §10. `esRolApoyo` agrupa
 * `analista` y `otro`, que aqui estan en extremos opuestos (el primero tiene
 * acceso total al modulo, el segundo ninguno); `esSupervisor` incluye a `jdv`,
 * que aqui no tiene acceso. Ver `plan-09-mapa-simulaciones-modulo.md`, hallazgo
 * K12 y decisiones D30/D31.
 */
@Component
class SimulacionPermisos {
    /**
     * 403 si el rol no tiene ninguna funcion de simulaciones (`jdv`, `otro`).
     * Es 403 y no 404 a proposito: no es una pregunta sobre si el recurso
     * existe, es que el rol no tiene la funcion.
     */
    fun exigirAcceso(usuario: UsuarioActual) {
        if (usuario.rol !in ROLES_CON_ALGUNA_FUNCION) {
            throw PermisoInsuficienteException("Tu rol no tiene acceso a las simulaciones")
        }
    }

    /**
     * 403 adicional para el listado del modulo (`GET /simulaciones`): solo
     * `admin`, `gerencia` y `analista`. El `vendedor` llega a sus simulaciones
     * por el contexto de la oportunidad y por la Calculadora, nunca por este
     * listado (§10, decision D39).
     */
    fun exigirAccesoAlModulo(usuario: UsuarioActual) {
        if (usuario.rol !in ROLES_MODULO) {
            throw PermisoInsuficienteException("Tu rol no tiene acceso al modulo de simulaciones")
        }
    }

    /**
     * Regla de alcance de una simulacion concreta (decision D31):
     *  - rol de [ROLES_MODULO] -> alcanza cualquiera,
     *  - `vendedor` -> la enlazada cuyo item es de SU oportunidad
     *    (`idVendedorDelItem == usuario.id`), y la NO enlazada que el creo
     *    (`idCreador == usuario.id`); sin item no hay cadena a oportunidad, asi
     *    que la autoria es el unico vinculo posible,
     *  - cualquier otro rol -> false.
     *
     * `idVendedorDelItem` es null cuando la simulacion no esta enlazada a un item.
     */
    fun alcanza(
        idCreador: Long,
        idVendedorDelItem: Long?,
        usuario: UsuarioActual,
    ): Boolean =
        when (usuario.rol) {
            in ROLES_MODULO -> true
            // Enlazada: manda el vendedor asignado del item, no la autoria (§9:
            // solo puede enlazar a items de oportunidades donde el es el vendedor
            // asignado). Sin item: la autoria es el unico vinculo posible.
            ROL_VENDEDOR ->
                if (idVendedorDelItem != null) idVendedorDelItem == usuario.id else idCreador == usuario.id
            else -> false
        }

    /** [alcanza] o 404 — recurso ajeno se trata como inexistente (CLAUDE.md regla 14). */
    fun exigirAlcance(
        idCreador: Long,
        idVendedorDelItem: Long?,
        usuario: UsuarioActual,
    ) {
        if (!alcanza(idCreador, idVendedorDelItem, usuario)) {
            throw NoEncontradoException("La simulacion no existe")
        }
    }

    private companion object {
        /** Roles con acceso total al modulo: ven y editan cualquier simulacion (§10). */
        val ROLES_MODULO = setOf("admin", "gerencia", "analista")

        const val ROL_VENDEDOR = "vendedor"

        /**
         * Roles con alguna funcion de simulaciones (§10): los del modulo mas el
         * `vendedor`, que entra por el simulador de su oportunidad y por la
         * Calculadora Financiera. Se expresa como lista de permitidos y no como
         * "todos menos `jdv` y `otro`" para que un rol nuevo en `rol_empleado`
         * nazca sin acceso y haya que concederselo explicitamente.
         */
        val ROLES_CON_ALGUNA_FUNCION = ROLES_MODULO + ROL_VENDEDOR
    }
}
