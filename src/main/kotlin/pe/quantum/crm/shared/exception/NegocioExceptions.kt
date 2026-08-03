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

/** `monto_total` vino en el body: es calculado y de solo lectura (reglas §7.2). */
class MontoNoEditableException :
    ApiException(
        code = "MONTO_NO_EDITABLE",
        message = "monto_total es calculado por el sistema y no se acepta como entrada",
        status = HttpStatus.BAD_REQUEST,
        field = "monto_total",
    )

/** El RUC ya existe. No expone a que vendedor pertenece (reglas §2.1). */
class RucDuplicadoException :
    ApiException(
        code = "RUC_DUPLICADO",
        message = "Esta empresa ya está registrada en el sistema",
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

/** Conflicto de negocio generico (409) con codigo especifico. */
class ConflictoException(
    code: String,
    message: String,
) : ApiException(code = code, message = message, status = HttpStatus.CONFLICT)

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
