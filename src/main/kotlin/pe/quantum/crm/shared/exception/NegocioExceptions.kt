package pe.quantum.crm.shared.exception

import org.springframework.http.HttpStatus

/** Error generico de validacion de campos (contrato_api.md §3). */
class ValidacionException(
    message: String,
    field: String? = null,
) : ApiException(code = "VALIDACION", message = message, status = HttpStatus.BAD_REQUEST, field = field)

/** Transicion o valor de estado no permitido. */
class EstadoInvalidoException(
    message: String,
) : ApiException(code = "ESTADO_INVALIDO", message = message, status = HttpStatus.BAD_REQUEST)

/** Se intento cerrar una oportunidad sin motivo (reglas_negocio.md §4.4). */
class MotivoCierreRequeridoException :
    ApiException(
        code = "MOTIVO_CIERRE_REQUERIDO",
        message = "El motivo de cierre es obligatorio para cerrar una oportunidad",
        status = HttpStatus.BAD_REQUEST,
        field = "motivo_cierre",
    )

/** Se intento crear un modelo sin aplicaciones (reglas_negocio.md §2.4). */
class ModeloSinAplicacionesException :
    ApiException(
        code = "MODELO_SIN_APLICACIONES",
        message = "Todo modelo debe tener al menos una aplicacion",
        status = HttpStatus.BAD_REQUEST,
        field = "aplicaciones",
    )

/**
 * El RUC ya existe y pertenece a otro vendedor. No expone a quien (reglas §2.1).
 * El mensaje evita culpar al usuario: registrar un RUC que otro ya trabaja no es
 * un error suyo, es informacion que no tenia.
 */
class RucDuplicadoException :
    ApiException(
        code = "RUC_DUPLICADO",
        message =
            "Esta empresa ya está registrada en el sistema y la gestiona otro vendedor. " +
                "Coordina con tu jefe de ventas si necesitas acceder a ella.",
        status = HttpStatus.CONFLICT,
        field = "ruc",
    )

/** No se puede eliminar un contacto vinculado a una empresa (reglas §11.2). */
class ContactoVinculadoException :
    ApiException(
        code = "CONTACTO_VINCULADO",
        message = "No se puede eliminar un contacto vinculado a una empresa",
        status = HttpStatus.CONFLICT,
    )

/** Conflicto de negocio generico (409) con codigo especifico y, opcionalmente, el campo que lo provoca. */
class ConflictoException(
    code: String,
    message: String,
    field: String? = null,
) : ApiException(code = code, message = message, status = HttpStatus.CONFLICT, field = field)

/**
 * El empleado arrastra el cambio de contraseña inicial pendiente (B1.4) e intento
 * usar la API antes de cumplirlo. Es 403 y no 401: la credencial es valida, lo que
 * falta es cumplir un requisito de la cuenta.
 */
class CambioContrasenaRequeridoException :
    ApiException(
        code = "CAMBIO_CONTRASENA_REQUERIDO",
        message = "Debes cambiar tu contraseña antes de continuar",
        status = HttpStatus.FORBIDDEN,
    )

/** El rol del usuario no tiene acceso a la operacion (matriz_permisos.md). */
class PermisoInsuficienteException(
    message: String = "El rol no tiene acceso a esta operación",
) : ApiException(code = "PERMISO_INSUFICIENTE", message = message, status = HttpStatus.FORBIDDEN)

/** El cambio supera el limite del rol y requiere una solicitud aprobada (422). */
class AprobacionRequeridaException(
    message: String,
) : ApiException(
        code = "APROBACION_REQUERIDA",
        message = message,
        status = HttpStatus.UNPROCESSABLE_ENTITY,
        field = "dcto",
    )

/** No existe financiadora con `es_default = true`: error de configuracion del sistema. */
class FinanciadoraDefaultInexistenteException :
    ApiException(
        code = "FINANCIADORA_DEFAULT_INEXISTENTE",
        message = "No hay financiadora default configurada en el sistema",
        status = HttpStatus.INTERNAL_SERVER_ERROR,
    )

/**
 * El saldo final del ultimo mes no coincide con `valor_residual`
 * (reglas_simulaciones.md §3.5). Con precision completa el residuo es del orden
 * de 1e-30, asi que superar la tolerancia de 0.01 significa que hay un bug en el
 * motor: se devuelve error, nunca un cronograma silenciosamente incorrecto.
 */
class CronogramaInconsistenteException(
    message: String,
) : ApiException(
        code = "CRONOGRAMA_INCONSISTENTE",
        message = message,
        status = HttpStatus.INTERNAL_SERVER_ERROR,
    )
