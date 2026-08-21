# Quantum CRM — Contrato de API

> Especificación de endpoints para el backend (Spring Boot). Define qué recibe cada endpoint, qué devuelve y quién puede accederlo. Claude Code debe implementar exactamente lo descrito aquí sin agregar endpoints no documentados ni modificar las firmas.

---

## Índice

1. [Convenciones generales](#1-convenciones-generales)
2. [Formato de respuesta](#2-formato-de-respuesta)
3. [Códigos de error](#3-códigos-de-error)
4. [Paginación](#4-paginación)
5. [Autorización por rol](#5-autorización-por-rol)
6. [Auth](#6-auth)
7. [Empleados](#7-empleados)
8. [Empresas](#8-empresas)
9. [Contactos](#9-contactos)
10. [Oportunidades](#10-oportunidades)
11. [Eventos](#11-eventos)
12. [Tareas](#12-tareas)
13. [Financiadoras](#13-financiadoras)
14. [Modelos](#14-modelos)
15. [Catálogo de eventos](#15-catálogo-de-eventos)
16. [Prospección](#16-prospección)
17. [Inicio](#17-inicio)
18. [Reportes](#18-reportes)
19. [Notificaciones](#19-notificaciones)
20. [Solicitudes](#20-solicitudes)
21. [Metas de venta](#21-metas-de-venta)
22. [Mantenimiento](#22-mantenimiento)
23. [Enums](#23-enums)
24. [Notas operativas — Drive](#24-notas-operativas--drive)
25. [Changelog del contrato](#25-changelog-del-contrato)

---

## 1. Convenciones generales

```
Base URL:      /api/v1
Content-Type:  application/json
Auth:          cookies httpOnly, ver abajo — NUNCA Authorization: Bearer
Fechas:        ISO 8601 — "2026-06-19T14:30:00Z" para timestamps, "2026-06-19" para fechas
Montos:        NUMERIC como string en JSON para evitar pérdida de precisión — "45000.00"
Enums:         snake_case — "evaluacion_calidda", "no_contactado"
IDs:           Long (número entero)
```

**Autenticación real (SECURITY-backend.md §2.1):** el JWT viaja en dos cookies `httpOnly`, nunca en el body ni en un header. El frontend no las lee ni las setea — el navegador las adjunta solo. `credentials: 'include'` (o equivalente) es obligatorio en cada fetch.

```
Set-Cookie: access_token=<jwt>;  HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=3600
Set-Cookie: refresh_token=<jwt>; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=604800
```

- `Secure` exige HTTPS (en local sobre HTTP se desactiva vía `COOKIE_SECURE=false`, nunca en un despliegue real).
- `SameSite=Strict` es la mitigación de CSRF; no hay token CSRF adicional.
- Ambas cookies se reemiten en cada `/auth/login`, `/auth/refresh` y `/auth/cambiar-contrasena` exitoso.

Todo endpoint salvo `/auth/login`, `/auth/refresh` y `/auth/logout` requiere sesión válida (cookie `access_token`).

---

## 2. Formato de respuesta

Todos los endpoints devuelven el mismo envelope:

```json
{
  "data": { },
  "meta": null,
  "error": null
}
```

Para listas con paginación, `meta` contiene:

```json
{
  "meta": {
    "page": 1,
    "per_page": 20,
    "total": 87,
    "total_pages": 5
  }
}
```

En caso de error, `data` es `null` y `error` contiene:

```json
{
  "data": null,
  "error": {
    "code": "EMPRESA_RUC_DUPLICADO",
    "message": "Esta empresa ya está registrada en el sistema",
    "field": "ruc"
  }
}
```

`error.field` va siempre en **snake_case**, igual que el resto del JSON (§1): un campo compuesto como `idContacto` sale como `id_contacto`, casando con el nombre que el frontend envió en el request. Un campo anidado con índice de array conserva el índice: `contactos[0].id_contacto`.

---

## 3. Códigos de error

| Código | HTTP | Descripción |
|---|---|---|
| `CREDENCIALES_INVALIDAS` | 401 | Login o `password_actual` de `/auth/cambiar-contrasena` no coincide |
| `RUC_DUPLICADO` | 409 | El RUC ya existe en el sistema y pertenece a **otro** vendedor (mismo vendedor → `200 OK`, no es error; ver §8) |
| `MOTIVO_CIERRE_REQUERIDO` | 400 | Se intentó cerrar una oportunidad sin motivo |
| `MODELO_SIN_APLICACIONES` | 400 | Se intentó crear un modelo sin aplicaciones |
| `FINANCIADORA_DEFAULT_INEXISTENTE` | 500 | No hay financiadora con `es_default = true` |
| `ESTADO_INVALIDO` | 400 | Transición de estado no permitida |
| `PERMISO_INSUFICIENTE` | 403 | El rol no tiene acceso a esta operación |
| `CAMBIO_CONTRASENA_REQUERIDO` | 403 | La cuenta arrastra el cambio de contraseña inicial pendiente (ver abajo) |
| `NO_ENCONTRADO` | 404 | El recurso no existe |
| `CONTACTO_VINCULADO` | 409 | No se puede eliminar un contacto vinculado a una empresa |
| `MONTO_NO_EDITABLE` | 400 | Se intentó enviar `monto_total` en el body |
| `VALIDACION` | 400 | Error genérico de validación de campos |
| `APROBACION_REQUERIDA` | 422 | El descuento supera el límite del rol; requiere una solicitud aprobada |
| `SOLICITUD_DUPLICADA` | 409 | Ya existe una solicitud pendiente del mismo tipo sobre esa entidad |
| `SOLICITUD_YA_RESUELTA` | 409 | Otro aprobador ya resolvió la solicitud |
| `SOLICITUD_NO_APLICABLE` | 409 | La entidad cambió y el efecto de la solicitud ya no aplica |
| `CARTERA_MAESTRA_CON_OPORTUNIDADES` | 409 | No se puede reservar una empresa con oportunidades activas |
| `ARCHIVO_DEMASIADO_GRANDE` | 413 | El archivo supera `DRIVE_MAX_FILE_SIZE_BYTES` |
| `DRIVE_NO_DISPONIBLE` | 502 | Google Drive no respondió |
| `DRIVE_SIN_CUOTA` | 502 | `ROOT_DRIVE_FOLDER_ID` no apunta a una unidad compartida |

### Cambio de contraseña obligatorio (`CAMBIO_CONTRASENA_REQUERIDO`)

Todo empleado creado por un admin nace con `requiere_cambio_contrasena = true`. Mientras el flag siga en `true`, **el backend rechaza con `403 CAMBIO_CONTRASENA_REQUERIDO` cualquier request autenticada**, con estas únicas excepciones:

| Endpoint | Por qué está exento |
|---|---|
| `POST /auth/cambiar-contrasena` | Es la única forma de apagar el flag |
| `POST /auth/logout` | Cerrar sesión nunca debe poder fallar |
| `GET /empleados/me` | El frontend lee el flag aquí para redirigir al restaurar la sesión |

`POST /auth/login` y `POST /auth/refresh` son públicos y no se ven afectados: siguen funcionando y reemiten las cookies con el estado actualizado del flag.

**No es solo UX del cliente:** aunque el frontend no redirija (por un bug, o porque el usuario recargó la página), la API queda cerrada hasta que se cambie la contraseña. El frontend debe igualmente redirigir al formulario de cambio en cuanto vea el flag en `true`, para que el usuario no se tope con 403 sueltos.

---

## 4. Paginación

Todos los endpoints de listado aceptan:

| Param | Tipo | Default | Descripción |
|---|---|---|---|
| `page` | int | 1 | Número de página |
| `per_page` | int | 20 | Registros por página (máx. 100) |
| `sort` | string | varía | Campo de ordenamiento |
| `dir` | `asc` \| `desc` | `desc` | Dirección de ordenamiento |

---

## 5. Autorización por rol

La visibilidad de datos varía según el rol del usuario autenticado. El backend aplica estos filtros automáticamente — el frontend no puede sobreescribirlos.

| Recurso | admin | gerencia | jdv | vendedor | analista | otro |
|---|---|---|---|---|---|---|
| Ver todas las empresas | ✓ | ✓ | ✓ | Solo asignadas | Solo donde colabora vía tarea | Igual que analista |
| Ver todas las oportunidades | ✓ | ✓ | ✓ | Solo propias | Solo donde colabora vía tarea | Igual que analista |
| Ver todas las tareas | ✓ | ✓ | ✓ | Solo donde es dueño o colaborador | Solo donde es dueño o colaborador (sin cambios) | Igual que analista |
| Reasignar empresa directo (cascada automática a sus oportunidades activas) | ✓ | ✓ | — (vía solicitud) | — | — | — |
| Ver / gestionar Cartera Maestra | ✓ | ✓ | — | — | — | — |
| Validar paso a Facturado | ✓ | ✓ | — | — | — | — |
| Crear empleado | ✓ | — | — | — | — | — |
| Modificar catálogo de eventos | ✓ | — | — | — | — | — |
| Modificar financiadoras | ✓ | ✓ | — | — | — | — |
| Modificar modelos | ✓ | ✓ | — | — | — | — |
| Eliminar empresa / oportunidad (definitivo, cascada) | ✓ | — | — | — | — | — |

`vendedor` filtra por `id_vendedor = usuario_actual` en empresas y en oportunidades. **`analista`/`otro` (roles de apoyo, actualizado 2026-08-18) ya no aplican el mismo filtro que `vendedor`**: no tienen cartera propia (`id_vendedor`), y su visibilidad es exclusivamente sobre empresas/oportunidades donde el usuario figura como colaborador de una tarea (`ids_colaboradores`). Tampoco crean ni editan empresas/oportunidades, ni confirman `facturado`. Las empresas en Cartera Maestra (`en_cartera_maestra = true`) son invisibles para `jdv`, `vendedor` y los roles de apoyo en todos los endpoints. Detalle completo por operación en `matriz_permisos.md`.

**Límites de descuento** (por encima del límite, el cambio requiere una solicitud — ver §20 Solicitudes): `vendedor` hasta 3%, `jdv` hasta 7%, `gerencia`/`admin` sin límite. Los roles de apoyo (`analista`, `otro`) no aplican descuentos por ninguna vía — ni directo ni por solicitud.

---

## 6. Auth

Los tokens **nunca** viajan en el body ni se leen de un header `Authorization`: van en las cookies `httpOnly` descritas en §1. Todas las respuestas de esta sección solo llevan lo que no está ya en la cookie.

### POST /auth/login
> Autentica al usuario y setea las cookies de sesión.

**Body:**
```json
{
  "email": "aldo.martinez@quantum.pe",
  "password": "..."
}
```

**Respuesta 200:**
```json
{
  "data": {
    "expires_in": 3600,
    "requiere_cambio_contrasena": false,
    "empleado": {
      "id": 1,
      "nombres": "Aldo",
      "apellidos": "Martínez",
      "email": "aldo.martinez@quantum.pe",
      "rol": "jdv",
      "area": "Comercial",
      "puesto": "Jefe de Ventas",
      "activo": true
    }
  }
}
```

**Notas:**
- Setea `access_token` (expira en 1 hora) y `refresh_token` (expira en 7 días) — ver §1.
- Responde `401` si las credenciales son inválidas, sin indicar si el error es en email o contraseña.
- Rate limiting por email: 5 intentos fallidos → `429` con header `Retry-After` (segundos) y `error.code = "DEMASIADOS_INTENTOS"`. `Retry-After` está en `Access-Control-Expose-Headers`, así que es legible desde JS aunque `crm.*` y `api.*` sean orígenes distintos.
- `requiere_cambio_contrasena` vive **únicamente** en `data.requiere_cambio_contrasena` (nivel raíz de la respuesta). El objeto `empleado` nunca lo incluye — no lo busquen ahí.

---

### POST /auth/refresh
> Renueva el access token. El refresh token se lee de su propia cookie — **no** del body.

**Body:** ninguno (`POST` sin contenido).

**Respuesta 200:**
```json
{ "data": { "expires_in": 3600 } }
```

Reemite ambas cookies.

**Errores:**
- `401 CREDENCIALES_INVALIDAS` — la cookie `refresh_token` falta, no es válida, expiró, es de tipo `access`, el empleado está inactivo, la sesión fue revocada (logout o cambio de contraseña en otro momento — ver `/auth/logout`), **o el empleado ya no existe** (una credencial muerta no es un recurso ausente: nunca `404`).

---

### POST /auth/logout
> Cierra sesión: revoca el refresh token en servidor y limpia ambas cookies.

**Body:** ninguno. **No requiere sesión válida.**

**Respuesta:** `204 No Content`, sin body.

**Notas:**
- Idempotente y a prueba de fallos: responde `204` con o sin cookie de sesión, con cookie expirada, o sin sesión — **nunca** `401`.
- Si la cookie `refresh_token` es válida, invalida esa sesión en servidor (no solo en el navegador): un refresh token copiado antes del logout deja de servir en el siguiente `/auth/refresh`.
- Limpia `access_token` y `refresh_token` con `Max-Age=0`, mismos `Path`/`HttpOnly`/`Secure`/`SameSite` que al emitirlas.

---

### POST /auth/cambiar-contrasena
> Cambia la contraseña del usuario autenticado. Es el único endpoint de `/auth/**` que exige autenticación — todos los demás son públicos por definición.

**Roles:** todos (usuario autenticado, sobre su propia cuenta)

**Body:**
```json
{
  "password_actual": "...",
  "password_nueva": "..."
}
```

`password_nueva`: 8–72 caracteres.

**Respuesta 200:** sin datos (`{ "data": null }`). Reemite ambas cookies.

**Errores:**

| Código | HTTP | Motivo |
|---|---|---|
| `CREDENCIALES_INVALIDAS` | 401 | `password_actual` no coincide con la contraseña vigente |
| `VALIDACION` | 400 | `password_nueva` es igual a `password_actual`, o no cumple la longitud 8–72 (`field: "password_nueva"`) |

**Notas:**
- Al completarse con éxito, `requiere_cambio_contrasena` pasa a `false`. El siguiente `/auth/login` ya lo refleja en el `empleado` devuelto.
- Invalida el refresh token de cualquier **otra** sesión abierta con la cuenta (mismo mecanismo que `/auth/logout`); la sesión que hizo el cambio sigue viva porque el backend reemite sus cookies con la versión ya vigente.

---

## 7. Empleados

### GET /empleados
> Lista de empleados. Para selectores de asignación.

**Roles:** `admin` `gerente` `jdv`

**Query params:** `activo` (bool, default `true`), `rol`

**Respuesta 200:**
```json
{
  "data": [
    {
      "id": 1,
      "nombres": "Aldo",
      "apellidos": "Martínez",
      "email": "aldo.martinez@quantum.pe",
      "rol": "jdv",
      "area": "Comercial",
      "puesto": "Jefe de Ventas",
      "activo": true
    }
  ]
}
```

---

### GET /empleados/me
> Perfil del usuario autenticado.

**Roles:** todos

**Respuesta 200:** el objeto `empleado` de arriba **más** `requiere_cambio_contrasena`:

```json
{
  "data": {
    "id": 1,
    "nombres": "Aldo",
    "apellidos": "Martínez",
    "email": "aldo.martinez@quantum.pe",
    "rol": "jdv",
    "area": "Comercial",
    "puesto": "Jefe de Ventas",
    "activo": true,
    "requiere_cambio_contrasena": false
  }
}
```

**Notas:**
- `requiere_cambio_contrasena` aparece **solo aquí y en `/auth/login`**, nunca en `GET /empleados` (que lista a *otros* empleados): el estado de la contraseña de un colega no es asunto de quien lista.
- Este endpoint está exento del bloqueo por cambio de contraseña pendiente (ver §3), justamente para que el frontend pueda leer el flag y redirigir al restaurar la sesión en cada carga de página.

---

### POST /empleados
> Crea un nuevo empleado.

**Roles:** `admin`

**Body:**
```json
{
  "nombres": "Carlos",
  "apellidos": "Ríos",
  "email": "carlos.rios@quantum.pe",
  "password": "...",
  "rol": "vendedor",
  "area": "Comercial",
  "puesto": "Asesor Comercial"
}
```

**Respuesta 201:** el empleado creado.

---

### PUT /empleados/:id
> Actualiza datos de un empleado. No actualiza contraseña.

**Roles:** `admin`

**Body:** mismos campos que POST, todos opcionales excepto los de identificación.

**Respuesta 200:** el empleado actualizado.

---

### PATCH /empleados/:id/activo
> Activa o desactiva un empleado.

**Roles:** `admin`

**Body:** `{ "activo": false }`

**Respuesta 200:** el empleado actualizado.

---

## 8. Empresas

### GET /empresas
> Lista de empresas con filtros.

**Roles:** todos (con filtro automático por rol)

**Query params:**

| Param | Tipo | Descripción |
|---|---|---|
| `q` | string | Búsqueda por razón social o RUC |
| `estado_cartera` | enum | Filtrar por estado de cartera. Un valor fuera del enum responde `400 VALIDACION` (`field: "estado_cartera"`), no se ignora en silencio |
| `id_vendedor` | long | Filtrar por vendedor (solo admin/gerencia/jdv) |
| `segmento` | string | Filtrar por segmento |
| `distrito` | string | Filtrar por distrito |
| `cartera_maestra` | bool | Solo admin/gerencia: filtra por pertenencia a la Cartera Maestra. Ignorado para el resto de roles (siempre excluidas) |

**Respuesta 200:**
```json
{
  "data": [
    {
      "id": 3,
      "ruc": "20260426827",
      "razon_social": "Transp. Negociaciones Sta. Anita S.A.",
      "estado_sunat": "Activo",
      "condicion_sunat": "Habido",
      "estado_cartera": "oportunidad_activa",
      "distrito": "Santa Anita",
      "id_vendedor": 1,
      "vendedor": { "id": 1, "nombres": "Aldo", "apellidos": "Martínez" },
      "segmentos": ["urbano"],
      "contactos_count": 1,
      "en_cartera_maestra": false
    }
  ],
  "meta": { "page": 1, "per_page": 20, "total": 10, "total_pages": 1 }
}
```

---

### GET /empresas/:id
> Detalle completo de una empresa.

**Roles:** todos (con filtro automático por rol)

**Respuesta 200:**
```json
{
  "data": {
    "id": 3,
    "ruc": "20260426827",
    "razon_social": "Transp. Negociaciones Sta. Anita S.A.",
    "actividad_econ": "Transporte urbano de pasajeros",
    "ciiu": "4921",
    "sector_industrial": "Transporte",
    "estado_sunat": "Activo",
    "condicion_sunat": "Habido",
    "direccion_fiscal": "Av. Los Ángeles 123, Santa Anita",
    "ubicacion_real": "Jr. Los Pinos 456, Santa Anita",
    "distrito": "Santa Anita",
    "provincia": "Lima",
    "departamento": "Lima",
    "aval_fiador": "Juan Rodríguez",
    "origen_lead": "visita_fria",
    "estado_cartera": "oportunidad_activa",
    "file_drive": "https://drive.google.com/...",
    "drive_folder_id": "1AbCdEfGhIjKlMnOpQrStUvWxYz",
    "sitio_web": null,
    "notas": null,
    "id_vendedor": 1,
    "vendedor": { "id": 1, "nombres": "Aldo", "apellidos": "Martínez" },
    "segmentos": ["urbano"],
    "contactos": [
      {
        "id": 5,
        "nombres": "Hugo",
        "apellidos": "Rodríguez",
        "cargo": "Gerente",
        "toma_decision": true,
        "es_principal": true,
        "email_1": null,
        "tlf_1": "964415122"
      }
    ],
    "en_cartera_maestra": false,
    "created_at": "2026-05-01T10:00:00Z",
    "created_by": 1
  }
}
```

---

### GET /empresas/ruc/:ruc
> Verifica si un RUC ya existe antes de crear. Llamar antes del POST.

**Roles:** todos

**Respuesta 200** (si existe):
```json
{
  "data": {
    "existe": true,
    "mensaje": "Esta empresa ya está registrada en el sistema"
  }
}
```

**Respuesta 200** (si no existe): `{ "data": { "existe": false } }`

**Notas:**
- Siempre devuelve 200. No expone a qué vendedor pertenece si existe.

---

### POST /empresas
> Crea una nueva empresa.

**Roles:** `admin` `gerencia` `jdv` `vendedor` — **los roles de apoyo (`analista`, `otro`) no pueden crear empresas: `403 PERMISO_INSUFICIENTE`** (2026-08-18).

**Body:**
```json
{
  "ruc": "20546399703",
  "razon_social": "Kincar S.A.C.",
  "actividad_econ": "Transporte urbano",
  "ciiu": "4921",
  "sector_industrial": "Transporte",
  "estado_sunat": "Activo",
  "condicion_sunat": "Habido",
  "direccion_fiscal": "Av. Principal 100, Puente Piedra",
  "ubicacion_real": null,
  "distrito": "Puente Piedra",
  "provincia": "Lima",
  "departamento": "Lima",
  "aval_fiador": null,
  "origen_lead": "cartera",
  "file_drive": null,
  "sitio_web": null,
  "notas": null,
  "segmentos": ["urbano"],
  "id_vendedor": 1
}
```

**Respuesta 201** (RUC nuevo): el objeto empresa completo, recién creado.

**Respuesta 200** (RUC ya existente y asignado al **mismo** vendedor que lo envía): el objeto empresa **existente**, sin crear una fila ni una carpeta de Drive nuevas. Ver `reglas_negocio.md §2.1`.

**Notas:**
- Se crea la carpeta de Google Drive de la empresa bajo la unidad compartida raíz, y su ID se devuelve en `drive_folder_id`. Si Drive no responde, la empresa **no se crea** (`502 DRIVE_NO_DISPONIBLE`). Esto solo aplica al camino de creación real (201); el camino de reutilización (200) no llama a Drive.
- `drive_folder_id` es de **solo lectura**: lo administra el backend y nunca se acepta en el body. No confundir con `file_drive`, que es una URL suelta editable por el usuario.

**Notas:**
- `segmentos` se inserta en `empresa_segmentos` de forma atómica.
- El backend valida el RUC antes de insertar. **`reglas_negocio.md §2.1` es la fuente de verdad de esta regla** — si en algún momento este contrato y esa sección dejan de coincidir, manda `reglas_negocio.md` y hay que actualizar este documento, no al revés:
  - Si el RUC existe y pertenece a **otro** vendedor → `409 RUC_DUPLICADO` con mensaje: *"Esta empresa ya está registrada en el sistema y la gestiona otro vendedor. Coordina con tu jefe de ventas si necesitas acceder a ella."* No se expone a qué vendedor pertenece.
  - Si el RUC existe y pertenece al **mismo** vendedor → `200 OK` con la empresa existente (ver arriba). No es un error.
- `estado_cartera` siempre nace como `no_contactado`. No es aceptado como campo de entrada.

---

### PUT /empresas/:id
> Actualiza datos de una empresa. No actualiza `estado_cartera` ni `id_vendedor`.

**Roles:** `admin` `gerencia` `jdv` `vendedor` (solo su empresa) — **los roles de apoyo (`analista`, `otro`) no pueden editar ninguna empresa: `403 PERMISO_INSUFICIENTE`** (2026-08-18).

**Body:** mismos campos que POST, todos opcionales. Si `segmentos` viene en el body, reemplaza completamente los segmentos actuales.

**Respuesta 200:** el objeto empresa completo actualizado.

---

### GET /empresas/:id/eventos
> Lista los eventos de la empresa que no están vinculados a ninguna oportunidad (`id_oportunidad IS NULL`). Son los hitos de prospección (reglas_negocio.md §10.3): registrarlos antes de que exista una oportunidad es lo que hace avanzar el checkpoint de `GET /prospeccion`.

**Roles:** todos (mismo filtro automático por rol que el resto de `/empresas`)

**Respuesta 200** — mismo shape que `GET /oportunidades/:id/eventos` (§11), incluyendo `es_hito_prospeccion`:
```json
{
  "data": {
    "pendientes": [
      {
        "id": 12,
        "id_oportunidad": null,
        "id_empresa": 3,
        "id_catalogo_evento": 9,
        "nombre": "Reporte Tributario recibido",
        "es_personalizado": false,
        "descripcion": null,
        "estado": "pendiente",
        "fecha_estimada": "2026-07-15",
        "fecha_seguimiento": "2026-07-10",
        "fecha_ocurrencia": null,
        "dispara_cambio_estado": false,
        "estado_destino": null,
        "es_recomendado": true,
        "etapa_asociada": null,
        "es_hito_prospeccion": true
      }
    ],
    "ocurridos": [],
    "descartados": []
  }
}
```

---

### POST /empresas/:id/eventos
> Registra un nuevo evento en la empresa, sin oportunidad asociada.

**Roles:** todos (`vendedor`: solo su empresa; roles de apoyo `analista`/`otro`: solo donde colaboran vía tarea — este endpoint no tiene guard de escritura propio, a diferencia de `PATCH /empresas/:id/estado-cartera`, así que no está bloqueado; sin verificar si es intencional, ver `matriz_permisos.md §2.5`)

**Body** — idéntico al de `POST /oportunidades/:id/eventos` (catálogo o personalizado, §11).

**Respuesta 201:** el evento creado, con `id_empresa` seteado e `id_oportunidad = null`.

**Notas:**
- Si `id_catalogo_evento` referencia un evento con `etapa_asociada` no nula → `400 VALIDACION` (ese evento pertenece a una etapa del pipeline y debe registrarse en una oportunidad, no en una empresa suelta).
- `PATCH /eventos/:id/ocurrido`, `PATCH /eventos/:id/descartado` y `PUT /eventos/:id` (§11) operan igual sobre estos eventos, sin cambios. `sugerencia` en `PATCH /eventos/:id/ocurrido` siempre viene `null` para eventos de empresa (no disparan cambio de estado).

---

### PATCH /empresas/:id/estado-cartera
> Cambia el estado de cartera manualmente. Solo acepta estados manuales.

**Roles:** `admin` `gerencia` `jdv` `vendedor` (solo su empresa) — **los roles de apoyo (`analista`, `otro`) no pueden cambiar el estado de cartera: `403 PERMISO_INSUFICIENTE`** (2026-08-18).

**Body:**
```json
{ "estado_cartera": "prospeccion" }
```

**Notas:**
- Solo acepta `no_contactado`, `no_aplica`, `no_interesado`, `prospeccion`.
- Si se envía `oportunidad_activa` o `cliente` → `400 ESTADO_INVALIDO`.
- Si la empresa tiene oportunidades activas y el nuevo estado es manual → `400 ESTADO_INVALIDO` (el derivado tiene prioridad).

**Respuesta 200:** `{ "data": { "estado_cartera": "prospeccion" } }`

---

### PATCH /empresas/:id/vendedor
> Reasigna el vendedor de una empresa.

**Roles:** `admin` `gerencia`

**Body:** `{ "id_vendedor": 2 }`

**Respuesta 200:** `{ "data": { "id_vendedor": 2 } }`

**Notas:**
- Cascada automáticamente: todas las oportunidades activas de esta empresa cambian a `id_vendedor` en la misma operación (reglas_negocio.md §8.3). Las oportunidades cerradas (`facturado`, `cerrado`) no se ven afectadas.
- `id_vendedor` debe ser un empleado activo con rol `vendedor` o `jdv`; si no, `400 VALIDACION`.
- El `jdv` ya no reasigna directo: recibe `403 PERMISO_INSUFICIENTE` y debe enviar una solicitud (§19) que resuelve `gerencia`.

---

### PATCH /empresas/:id/cartera-maestra
> Mueve una empresa a la Cartera Maestra o la libera asignando vendedor.

**Roles:** `admin` `gerencia`

**Body (reservar):** `{ "en_cartera_maestra": true }`
- Requiere que la empresa no tenga oportunidades activas → si no, `409 CARTERA_MAESTRA_CON_OPORTUNIDADES`.
- Desasigna el vendedor (`id_vendedor` queda `null`).

**Body (liberar):** `{ "en_cartera_maestra": false, "id_vendedor": 8 }`
- `id_vendedor` obligatorio → si falta, `400 VALIDACION`.
- Notifica `empresa_asignada` al vendedor destino. A partir de este momento la empresa es visible para el `jdv` y el vendedor asignado.

**Respuesta 200:** `{ "data": { "en_cartera_maestra": false, "id_vendedor": 8 } }`

---

### POST /empresas/:id/carpeta-drive
> Crea la carpeta de Google Drive de la empresa. Idempotente.

**Roles:** `admin` `gerencia` `jdv` `vendedor` (solo las suyas) — **los roles de apoyo (`analista`, `otro`) no pueden crear la carpeta: `403 PERMISO_INSUFICIENTE`** (2026-08-18), porque escribe `drive_folder_id` en la empresa.

**Body:** vacío.

**Respuesta 200:** `{ "data": { "drive_folder_id": "1AbCdEfGhIjKlMnOpQrStUvWxYz" } }`

**Notas:**
- Si la empresa ya tiene carpeta, la devuelve sin tocar Drive. El frontend puede llamarlo sin verificar antes.
- El botón "Crear File del Cliente" debe **ocultarse** cuando `drive_folder_id` ya viene distinto de `null` en `GET /empresas/:id`.
- Errores: `404 NO_ENCONTRADO` (ajena o inexistente) · `502 DRIVE_NO_DISPONIBLE` / `DRIVE_SIN_CUOTA`.

---

### GET /empresas/:id/archivos
> Lista los documentos de la carpeta de Google Drive de la empresa.

**Roles:** los mismos que ven la empresa (`vendedor`: solo las suyas; roles de apoyo `analista`/`otro`: solo donde colaboran vía tarea — lectura, no bloqueada).

**Respuesta 200:**

```json
{
  "data": [
    {
      "id": "1AbCdEfGhIjKlMnOpQrStUvWxYz",
      "nombre": "ficha-ruc.pdf",
      "url": "https://drive.google.com/file/d/1AbCdEfGhIjKlMnOpQrStUvWxYz/view",
      "tamano_bytes": 284512,
      "mime_type": "application/pdf"
    }
  ]
}
```

**Notas:**
- Orden alfabético por nombre. No incluye subcarpetas de oportunidades ni elementos en la papelera de Drive.
- Si la empresa aún no tiene carpeta, devuelve `"data": []`. Esta llamada **nunca crea la carpeta**.
- Errores: `404 NO_ENCONTRADO` (ajena o inexistente) · `502 DRIVE_NO_DISPONIBLE`.

---

### POST /empresas/:id/archivos
> Sube un documento a la carpeta de Google Drive de la empresa.

**Roles:** `admin` `gerencia` `jdv` `vendedor` (solo las suyas) — **los roles de apoyo (`analista`, `otro`) no pueden subir archivos: `403 PERMISO_INSUFICIENTE`** (2026-08-18), porque este endpoint asegura la carpeta primero (`asegurarCarpetaDrive`, ver nota arriba).

**Request:** `multipart/form-data` con el archivo en el campo **`file`**. Otros campos se ignoran.

El backend no almacena el archivo: lo transmite en streaming hacia Drive.

**Respuesta 201:** igual forma que el listado, un solo objeto en `data` (ver `POST /oportunidades/:id/archivos`).

**Errores:** los mismos que `POST /oportunidades/:id/archivos` — `400 VALIDACION` · `404 NO_ENCONTRADO` · `413 ARCHIVO_DEMASIADO_GRANDE` · `502 DRIVE_NO_DISPONIBLE` / `DRIVE_SIN_CUOTA`.

---

### DELETE /empresas/:id
> Elimina definitivamente una empresa y todo lo que cuelga de ella en el pipeline comercial.

**Roles:** `admin`

**Respuesta 204:** sin body.

**Notas:**
- Elimina en cascada sus oportunidades, las tareas y eventos de esas oportunidades, el log de estados, y las tareas/eventos propios de la empresa (sin oportunidad asociada).
- Los contactos vinculados **no** se eliminan: solo se borra el vínculo (`empresa_contactos`). El contacto sigue existiendo y puede estar vinculado a otras empresas.
- Sin restricción por estado: incluye empresas con oportunidades en `facturado`. Operación irreversible.

---

## 9. Contactos

### GET /contactos
> Busca contactos. Usado para vincular un contacto existente a una empresa, y para la vista de listado de Contactos.

**Roles:** todos — **con filtro automático por rol** para `analista` y `otro` (ver nota de visibilidad abajo)

**Query params:** `q` (nombre o teléfono), `id_empresa` (contactos de una empresa específica), `contexto` (`listado` | `vincular`), `page`, `per_page`

**Respuesta 200:**
```json
{
  "data": [
    {
      "id": 5,
      "nombres": "Hugo",
      "apellidos": "Rodríguez",
      "email_1": null,
      "tlf_1": "964415122",
      "oportunidades_count": 3,
      "empresas": [
        { "id": 3, "razon_social": "Transp. Negociaciones Sta. Anita S.A.", "cargo": "Gerente" }
      ]
    }
  ],
  "meta": { "page": 1, "per_page": 20, "total": 42, "total_pages": 3 }
}
```

**Visibilidad por rol (`analista` y `otro`):** estos roles no tienen cartera propia. Lo que devuelve el endpoint depende de `contexto`:

| `contexto` | Qué contactos devuelve | Qué campos devuelve |
|---|---|---|
| `listado` (default) | Solo los vinculados a empresas donde el usuario colabora vía tarea (`ids_colaboradores`). Un contacto sin ninguna empresa vinculada nunca aparece. | Todos, igual que el resto de roles. |
| `vincular` | Todos los del CRM, incluidos los que no tienen empresa. | **Solo `id`, `nombres` y `apellidos`.** `email_*`, `tlf_*` y `notas` vienen `null`; `empresas` viene `[]`; `oportunidades_count` viene `0` (no se puede distinguir de un contacto sin oportunidades — un cliente que necesite esa distinción no debe usar este campo en este modo). |

**Notas sobre `contexto`:**
- **Es el parámetro que distingue las dos pantallas que comparten este endpoint:** la vista de listado de Contactos (`listado`) y el buscador de "vincular contacto existente" dentro de una empresa (`vincular`). Sin él, el backend no puede aplicar la regla correcta, porque son opuestas para el mismo rol.
- **Si no se envía, se asume `listado`** — el modo restrictivo. Un cliente que todavía no adoptó el parámetro nunca abre la búsqueda global por omisión.
- Un valor fuera de `listado`/`vincular` devuelve `400 VALIDACION` con `field: "contexto"`. No se ignora silenciosamente.
- **En `contexto=vincular`, para `analista`/`otro`, `q` busca solo por nombre y apellidos — no por teléfono.** Ocultar el teléfono en la respuesta no bastaría: un `LIKE` sobre el número convertiría el endpoint en un oráculo (escribo un teléfono, me devuelve de quién es). Para el resto de roles `q` sigue buscando por nombre y por los dos teléfonos, como siempre.
- Para `admin`, `gerencia`, `jdv` y `vendedor` el parámetro no cambia nada: ven todo, con todos los campos, en cualquier contexto.

---

### GET /contactos/:id
> Detalle completo del contacto: empresas vinculadas, oportunidades vinculadas y su línea de tiempo de actividades.

**Roles:** todos — **con filtro automático por rol** para `analista` y `otro`

**Query params:** `contexto` (`listado` | `vincular`) — misma semántica que en `GET /contactos`

**Respuesta 200:**
```json
{
  "data": {
    "id": 5, "nombres": "Hugo", "apellidos": "Rodríguez",
    "email_1": "h@x.com", "email_2": null, "tlf_1": "964415122", "tlf_2": null, "notas": null,
    "empresas": [
      { "id": 3, "razon_social": "Transp. Sta. Anita S.A.", "cargo": "Gerente",
        "toma_decision": true, "es_principal": true, "segmentos": ["interprovincial"] }
    ],
    "oportunidades": [
      { "id": 12, "empresa": { "id": 3, "razon_social": "Transp. Sta. Anita S.A." },
        "modelo": { "id": 2, "codigo": "KinWin K9" },
        "estado": "evaluacion_calidda", "monto_total": "450000.00",
        "fecha_cierre_estimado": "2024-12-15", "rol_en_oportunidad": "Contacto Principal" }
    ],
    "actividades": [
      { "id": 88, "tipo": "tarea", "titulo": "llamada",
        "descripcion": "Acordar términos...", "fecha": "2024-10-24T10:30:00", "estado": "pendiente" }
    ]
  }
}
```

**Notas:**
- `actividades[]` incluye solo tareas por ahora. `eventos` no tiene columna `id_contacto` en el schema actual y no existe una entidad de notas — se agregarán cuando el schema lo soporte.
- `oportunidades[].modelo.codigo` usa el mismo campo que el resto del contrato (§10), no `nombre`.
- `actividades[].titulo` es el valor de `tipo_accion` (`llamada`, `correo`, `reunion`, `whatsapp`, `otro`) — `Tarea` no tiene un campo de título libre.
- `actividades[]` respeta la visibilidad de tareas: vendedor/analista solo ven las tareas asignadas a sí mismos.
- **Visibilidad para `analista`/`otro`:** en `contexto=listado` (default), un contacto que no esté vinculado a ninguna empresa donde el usuario colabora devuelve `404 NO_ENCONTRADO` — indistinguible de un contacto inexistente, a propósito. En `contexto=vincular` el detalle sí se devuelve para cualquier contacto, pero recortado: solo `id`, `nombres` y `apellidos`; `empresas`, `oportunidades` y `actividades` vienen vacíos.
- Errores: `404 NO_ENCONTRADO` si el contacto no existe o está fuera del alcance del rol. `400 VALIDACION` si `contexto` trae un valor desconocido.

---

### POST /contactos
> Crea un contacto nuevo y lo vincula a una empresa.

**Roles:** todos

**Body:**
```json
{
  "nombres": "Hugo",
  "apellidos": "Rodríguez",
  "email_1": null,
  "email_2": null,
  "tlf_1": "964415122",
  "tlf_2": null,
  "notas": null,
  "id_empresa": 3,
  "cargo": "Gerente",
  "toma_decision": true,
  "es_principal": true
}
```

**Respuesta 201:** el contacto creado con su vinculación.

**Notas:**
- `id_empresa`, `cargo`, `toma_decision` y `es_principal` crean el registro en `empresa_contactos` de forma atómica.

---

### PUT /contactos/:id
> Actualiza datos propios del contacto (no los de su vinculación a empresa).

**Roles:** todos — `analista` y `otro` solo sobre contactos vinculados a empresas donde colaboran

**Body:** `nombres`, `apellidos`, `email_1`, `email_2`, `tlf_1`, `tlf_2`, `notas` — todos opcionales.

**Respuesta 200:** el contacto actualizado.

**Notas:**
- **`analista`/`otro` sobre un contacto fuera de su alcance:** `403 PERMISO_INSUFICIENTE`, no 404. Es una excepción deliberada al criterio IDOR de este repo (CLAUDE.md regla 14: recurso ajeno → 404, no 403): en `contexto=vincular` estos roles pueden ver ese mismo contacto por nombre, así que esconderlo al editar mentiría sobre algo que el sistema ya les mostró. El mensaje del error se puede mostrar tal cual al usuario.
- Un contacto inexistente devuelve `404 NO_ENCONTRADO` para todos los roles, incluidos los de apoyo: el 404 se evalúa antes que el permiso.

---

### DELETE /contactos/:id
> Elimina un contacto. Solo si no está vinculado a ninguna empresa.

**Roles:** `admin` `gerencia` `jdv`

**Respuesta 204:** sin body.

**Notas:**
- Si está vinculado a alguna empresa → `409 CONTACTO_VINCULADO`.

---

### POST /empresas/:id/contactos
> Vincula un contacto existente a una empresa.

**Roles:** todos (`vendedor`: solo su empresa; roles de apoyo `analista`/`otro`: **bloqueado, 403 `PERMISO_INSUFICIENTE`** — igual que la vinculación de contactos a oportunidades)

**Body:**
```json
{
  "id_contacto": 5,
  "cargo": "Gerente",
  "toma_decision": true,
  "es_principal": false
}
```

**Respuesta 201:** la vinculación creada.

---

### PUT /empresas/:id/contactos/:contacto_id
> Actualiza el cargo o rol del contacto en esta empresa.

**Roles:** todos (`vendedor`: solo su empresa; roles de apoyo `analista`/`otro`: **bloqueado, 403 `PERMISO_INSUFICIENTE`** — igual que la vinculación de contactos a oportunidades)

**Body:** `{ "cargo": "Socio", "toma_decision": false, "es_principal": false }`

**Respuesta 200:** la vinculación actualizada.

---

### DELETE /empresas/:id/contactos/:contacto_id
> Desvincula un contacto de una empresa. No elimina el contacto.

**Roles:** todos (`vendedor`: solo su empresa; roles de apoyo `analista`/`otro`: **bloqueado, 403 `PERMISO_INSUFICIENTE`** — igual que la vinculación de contactos a oportunidades)

**Respuesta 204:** sin body.

---

## 10. Oportunidades

### GET /oportunidades
> Lista de oportunidades con filtros.

**Roles:** todos (con filtro automático por rol)

**Query params:**

| Param | Tipo | Descripción |
|---|---|---|
| `estado` | enum | Filtrar por etapa del pipeline. Un valor fuera del enum responde `400 VALIDACION` (`field: "estado"`), no se ignora en silencio |
| `id_empresa` | long | Filtrar por empresa |
| `id_vendedor` | long | Solo admin/gerente/jdv |
| `id_financiadora` | long | Filtrar por financiadora |
| `incluir_cerradas` | bool | Default `false` |

**Respuesta 200:**
```json
{
  "data": [
    {
      "id": 101,
      "id_empresa": 3,
      "empresa": { "id": 3, "razon_social": "Transp. Negociaciones Sta. Anita S.A.", "distrito": "Santa Anita" },
      "id_vendedor": 1,
      "vendedor": { "id": 1, "nombres": "Aldo", "apellidos": "Martínez" },
      "id_financiadora": 1,
      "financiadora": { "id": 1, "nombre": "Calidda – Fraccionamiento GNV", "monto_por_unidad": "45000.00", "plazo_meses": 48, "tea": "0.0000", "cuota_por_unidad": "937.50" },
      "id_modelo": 1,
      "modelo": { "id": 1, "codigo": "KinWin K12", "precio_base": "92000.00" },
      "estado": "documentos_legales",
      "cantidad": 8,
      "precio_unitario": "92000.00",
      "dcto": "3.00",
      "monto_total": "713952.00",
      "garantia": true,
      "finc_paralelo": false,
      "ficha_venta": null,
      "drive_folder_id": "1XyZaBcDeFgHiJkLmNoPqRsTuV",
      "notas": null,
      "motivo_cierre": null,
      "fecha_cierre_estimado": "2026-07-10",
      "tareas_pendientes_count": 1,
      "eventos_pendientes_count": 2,
      "created_at": "2026-05-15T09:00:00Z"
    }
  ],
  "meta": { "page": 1, "per_page": 20, "total": 6, "total_pages": 1 }
}
```

---

### GET /oportunidades/:id
> Detalle completo de una oportunidad.

**Roles:** todos (con filtro automático por rol)

**Respuesta 200:** mismo objeto que el listado más:

```json
{
  "data": {
    "...campos del listado...",
    "contactos": [
      { "id": 5, "nombres": "Hugo", "apellidos": "Rodríguez", "rol_en_oportunidad": "Contacto Principal" }
    ],
    "entrada_etapa_actual": "2026-06-02T10:00:00Z"
  }
}
```

**Notas:**
- `entrada_etapa_actual` es el `changed_at` del último cambio de estado en `oportunidad_estados_log`. Derivado, no almacenado.

---

### POST /oportunidades
> Crea una nueva oportunidad.

**Roles:** `admin` `gerencia` `jdv` `vendedor` (la empresa debe estar asignada al vendedor si es `vendedor`) — **los roles de apoyo (`analista`, `otro`) no pueden crear oportunidades: `403 PERMISO_INSUFICIENTE`** (2026-08-18).

**Body:**
```json
{
  "id_empresa": 3,
  "id_modelo": 1,
  "id_financiadora": 1,
  "cantidad": 8,
  "dcto": 3.00,
  "garantia": true,
  "finc_paralelo": false,
  "ficha_venta": null,
  "notas": null,
  "fecha_cierre_estimado": "2026-07-10",
  "contactos": [
    { "id_contacto": 5, "rol_en_oportunidad": "Contacto Principal" }
  ],
  "id_vendedor": null
}
```

**Respuesta 201:** el objeto oportunidad completo.

**Notas:**
- `monto_total` NO se acepta en el body. Si viene, se ignora y se calcula.
- `precio_unitario` se inicializa con `modelos.precio_base` del modelo seleccionado.
- `id_vendedor` se toma de `empresas.id_vendedor` en el momento de la creación. **Excepción:** si la empresa no tiene vendedor asignado (solo la ven roles supervisores), quien crea con `gerencia`/`admin` DEBE enviar `id_vendedor` en el body — la empresa queda asignada a ese vendedor en la misma operación. Si falta → `400 VALIDACION` (`field: "id_vendedor"`).
- `id_financiadora` es opcional — si no viene, se usa la que tenga `es_default = true`.
- Si `dcto` supera el límite del rol (§5) → `422 APROBACION_REQUERIDA`: no se crea la oportunidad. Crear primero sin descuento (o dentro del límite) y solicitar el mayor después sobre la oportunidad ya creada.
- Se inserta el primer registro en `oportunidad_estados_log`.
- Se llama a `actualizarEstadoCartera` en la misma transacción.
- Se crea la subcarpeta de Google Drive de la oportunidad dentro de la carpeta de su empresa, y su ID se devuelve en `drive_folder_id`. Si Drive no responde, la oportunidad **no se crea** (`502 DRIVE_NO_DISPONIBLE`).

---

### POST /oportunidades/:id/carpeta-drive
> Crea la carpeta de Google Drive de la oportunidad, dentro de la de su empresa. Idempotente.

**Roles:** `admin` `gerencia` `jdv` `vendedor` (solo las suyas) — **los roles de apoyo (`analista`, `otro`) no pueden crear la carpeta: `403 PERMISO_INSUFICIENTE`** (2026-08-18), porque escribe `drive_folder_id` en la oportunidad.

**Body:** vacío.

**Respuesta 200:** `{ "data": { "drive_folder_id": "1XyZaBcDeFgHiJkLmNoPqRsTuV" } }`

**Notas:**
- Si la empresa de esa oportunidad tampoco tiene carpeta, se crean **ambas**: primero la de la empresa, y la de la oportunidad dentro.
- Si la oportunidad ya tiene carpeta, la devuelve sin tocar Drive.
- El botón "Crear File de la Oportunidad" debe **ocultarse** cuando `drive_folder_id` ya viene distinto de `null` en `GET /oportunidades/:id`.
- Errores: `404 NO_ENCONTRADO` (ajena o inexistente) · `502 DRIVE_NO_DISPONIBLE` / `DRIVE_SIN_CUOTA`.

---

### GET /oportunidades/:id/archivos
> Lista los documentos de la carpeta de Google Drive de la oportunidad.

**Roles:** los mismos que ven la oportunidad (`vendedor`: solo las suyas; roles de apoyo `analista`/`otro`: solo donde colaboran vía tarea — lectura, no bloqueada).

**Respuesta 200:**

```json
{
  "data": [
    {
      "id": "1AbCdEfGhIjKlMnOpQrStUvWxYz",
      "nombre": "contrato-firmado.pdf",
      "url": "https://drive.google.com/file/d/1AbCdEfGhIjKlMnOpQrStUvWxYz/view",
      "tamano_bytes": 284512,
      "mime_type": "application/pdf"
    }
  ]
}
```

**Notas:**
- Orden alfabético por nombre. No incluye subcarpetas ni elementos en la papelera de Drive.
- Si la oportunidad aún no tiene carpeta (nunca se subió nada), devuelve `"data": []`. Esta llamada **nunca crea la carpeta** — a diferencia de `POST`, una lectura no tiene efectos secundarios.
- Errores: `404 NO_ENCONTRADO` (ajena o inexistente) · `502 DRIVE_NO_DISPONIBLE`.

---

### POST /oportunidades/:id/archivos
> Sube un documento a la carpeta de Google Drive de la oportunidad.

**Roles:** `admin` `gerencia` `jdv` `vendedor` (solo las suyas) — **los roles de apoyo (`analista`, `otro`) no pueden subir archivos: `403 PERMISO_INSUFICIENTE`** (2026-08-18), porque este endpoint asegura la carpeta primero.

**Request:** `multipart/form-data` con el archivo en el campo **`file`**. Otros campos se ignoran.

El backend no almacena el archivo: lo transmite en streaming hacia Drive. No hay límite práctico de tamaño por memoria del servidor, solo el tope configurado (`DRIVE_MAX_FILE_SIZE_BYTES`, 100 MB por defecto).

**Respuesta 201:**

```json
{
  "data": {
    "id": "1AbCdEfGhIjKlMnOpQrStUvWxYz",
    "nombre": "contrato-firmado.pdf",
    "url": "https://drive.google.com/file/d/1AbCdEfGhIjKlMnOpQrStUvWxYz/view",
    "tamano_bytes": 284512,
    "mime_type": "application/pdf"
  }
}
```

**Errores:**

| Código HTTP | `code` | Cuándo |
|---|---|---|
| 400 | `VALIDACION` | No es `multipart/form-data`, falta el campo `file`, o el archivo no tiene nombre |
| 404 | `NO_ENCONTRADO` | La oportunidad no existe o es ajena (IDOR → 404, nunca 403) |
| 413 | `ARCHIVO_DEMASIADO_GRANDE` | Supera `DRIVE_MAX_FILE_SIZE_BYTES` |
| 502 | `DRIVE_NO_DISPONIBLE` | Google Drive no responde |
| 502 | `DRIVE_SIN_CUOTA` | `ROOT_DRIVE_FOLDER_ID` no apunta a una unidad compartida |

**Notas:**
- Si la oportunidad aún no tiene carpeta (creada antes de la migración V35), se crea en esta llamada.
- La visibilidad se verifica **antes** de leer un solo byte del cuerpo.

---

### PUT /oportunidades/:id
> Actualiza campos negociables de la oportunidad. No cambia el estado.

**Roles:** `admin` `gerencia` `jdv` `vendedor` (solo su oportunidad) — **los roles de apoyo (`analista`, `otro`) no pueden editar ninguna oportunidad: `403 PERMISO_INSUFICIENTE`** (2026-08-18).

**Body:** `id_modelo`, `cantidad`, `precio_unitario`, `dcto`, `garantia`, `finc_paralelo`, `ficha_venta`, `notas`, `fecha_cierre_estimado` — todos opcionales.

**Notas:**
- `monto_total` NO se acepta. Si viene → `400 MONTO_NO_EDITABLE`.
- `estado`, `id_empresa`, `id_vendedor` NO se aceptan en este endpoint.
- Si `dcto` supera el límite del rol (§5) → `422 APROBACION_REQUERIDA`: el resto de campos puede reintentarse sin `dcto` o dentro del límite; el descuento mayor requiere una solicitud (§19).
- Si cambia `id_modelo` y `precio_unitario` no fue editado previamente (igual al `precio_base` del modelo anterior), se actualiza automáticamente con el nuevo `precio_base`.
- Si `precio_unitario` fue editado manualmente, el backend devuelve en la respuesta: `"advertencias": ["El precio unitario fue editado manualmente y no se actualizó con el nuevo modelo"]`.
- Recalcula y persiste `monto_total`.

**Respuesta 200:** la oportunidad actualizada.

---

### DELETE /oportunidades/:id
> Elimina definitivamente una oportunidad.

**Roles:** `admin`

**Respuesta 204:** sin body.

**Notas:**
- Elimina en cascada su log de estados, sus vínculos de contacto (`oportunidad_contactos`), sus eventos y sus tareas. Los contactos en sí **no** se eliminan, solo el vínculo.
- Recalcula `estado_cartera` de la empresa tras eliminar (reglas_negocio.md §3.3): si la empresa se queda sin oportunidades activas/facturadas, vuelve a su estado manual (o `null`).
- Sin restricción por estado: incluye oportunidades en `facturado`. Operación irreversible.

---

### PATCH /oportunidades/:id/estado
> Cambia el estado de una oportunidad.

**Roles:** todos con restricción: el paso a `facturado` solo lo pueden confirmar `admin` y `gerencia`. (Corregido 2026-08-18: decía "gerente" — nombre obsoleto desde la migración V25 — y "analista", que dejó de tener este privilegio al pasar a rol de apoyo.)

**Body:**
```json
{
  "estado": "documentos_legales",
  "motivo_cierre": null
}
```

**Respuesta 200:**
```json
{
  "data": {
    "estado": "documentos_legales",
    "es_retroceso": false,
    "advertencias": []
  }
}
```

**Notas:**
- Si `estado = 'cerrado'` y `motivo_cierre` es null o vacío → `400 MOTIVO_CIERRE_REQUERIDO`.
- Si es un retroceso, la respuesta incluye `"es_retroceso": true`. El frontend debe pedir confirmación antes de llamar a este endpoint — el backend aplica el cambio sin una segunda confirmación.
- Si hay eventos recomendados sin registrar para la etapa actual, `advertencias` los lista.
- Se inserta en `oportunidad_estados_log`.
- Se llama a `actualizarEstadoCartera` en la misma transacción.
- Si retrocede desde `cerrado`, `motivo_cierre` se pone en `NULL` automáticamente.

---

### GET /oportunidades/:id/log
> Historial de cambios de estado de la oportunidad.

**Roles:** todos (con filtro automático por rol)

**Respuesta 200:**
```json
{
  "data": [
    {
      "estado_anterior": null,
      "estado_nuevo": "evaluacion_calidda",
      "changed_at": "2026-05-15T09:00:00Z",
      "changed_by": { "id": 1, "nombres": "Aldo", "apellidos": "Martínez" }
    },
    {
      "estado_anterior": "evaluacion_calidda",
      "estado_nuevo": "documentos_legales",
      "changed_at": "2026-06-02T10:00:00Z",
      "changed_by": { "id": 1, "nombres": "Aldo", "apellidos": "Martínez" }
    }
  ]
}
```

---

### POST /oportunidades/:id/contactos
> Vincula un contacto a la oportunidad con su rol.

**Roles:** `admin` `gerencia` `jdv` `vendedor` (solo su oportunidad) — **los roles de apoyo (`analista`, `otro`) no pueden vincular contactos a una oportunidad: `403 PERMISO_INSUFICIENTE`** (2026-08-18). A diferencia de la vinculación de contactos a una *empresa* (§9), que sí les está permitida donde colaboran — ver `matriz_permisos.md §2.3`.

**Body:** `{ "id_contacto": 5, "rol_en_oportunidad": "Contacto Principal" }`

**Respuesta 201:** la vinculación creada.

**Errores:**
- `409 CONTACTO_YA_VINCULADO` — el contacto ya está vinculado a esta oportunidad; usa `PUT` para cambiar su rol en vez de reenviar el `POST`.

---

### PUT /oportunidades/:id/contactos/:contacto_id
> Actualiza el rol de un contacto en la oportunidad.

**Body:** `{ "rol_en_oportunidad": "Aprobador" }`

**Respuesta 200:** la vinculación actualizada.

---

### DELETE /oportunidades/:id/contactos/:contacto_id
> Desvincula un contacto de la oportunidad.

**Respuesta 204:** sin body.

---

## 11. Eventos

### GET /oportunidades/:id/eventos
> Lista todos los eventos de una oportunidad, separados por estado.

**Roles:** todos (con filtro automático por rol)

**Respuesta 200:**
```json
{
  "data": {
    "pendientes": [
      {
        "id": 2,
        "id_catalogo_evento": 5,
        "nombre": "Contrato tripartito firmado",
        "es_personalizado": false,
        "descripcion": null,
        "estado": "pendiente",
        "fecha_estimada": "2026-06-24",
        "fecha_seguimiento": "2026-06-20",
        "fecha_ocurrencia": null,
        "dispara_cambio_estado": false,
        "estado_destino": null,
        "es_recomendado": true,
        "etapa_asociada": "documentos_legales",
        "es_hito_prospeccion": false
      }
    ],
    "ocurridos": [
      {
        "id": 1,
        "nombre": "Aprobación Calidda",
        "estado": "ocurrido",
        "fecha_ocurrencia": "2026-06-02T10:00:00Z",
        "dispara_cambio_estado": true,
        "estado_destino": "documentos_legales"
      }
    ],
    "descartados": []
  }
}
```

---

### POST /oportunidades/:id/eventos
> Registra un nuevo evento en la oportunidad.

**Roles:** todos (`vendedor`: solo su oportunidad; roles de apoyo `analista`/`otro`: solo donde colaboran vía tarea — no bloqueado, ver `matriz_permisos.md §2.5`)

**Body (evento del catálogo):**
```json
{
  "id_catalogo_evento": 5,
  "fecha_estimada": "2026-06-24",
  "fecha_seguimiento": "2026-06-20",
  "descripcion": null
}
```

**Body (evento personalizado):**
```json
{
  "es_personalizado": true,
  "nombre_personalizado": "Reunión con asesor legal del cliente",
  "fecha_estimada": "2026-06-25",
  "fecha_seguimiento": "2026-06-22",
  "descripcion": "El cliente quiere que su abogado revise el contrato"
}
```

**Respuesta 201:** el evento creado.

---

### PATCH /eventos/:id/ocurrido
> Marca un evento como ocurrido.

**Roles:** todos (`vendedor`: solo eventos de su oportunidad; roles de apoyo `analista`/`otro`: solo eventos de oportunidades donde colaboran vía tarea — no bloqueado, ver `matriz_permisos.md §2.5`)

**Body:**
```json
{
  "fecha_ocurrencia": "2026-06-19T14:30:00Z",
  "descripcion": null
}
```

**Respuesta 200:**
```json
{
  "data": {
    "id": 3,
    "estado": "ocurrido",
    "fecha_ocurrencia": "2026-06-19T14:30:00Z",
    "sugerencia": {
      "dispara": true,
      "estado_destino": "documentos_legales",
      "mensaje": "¿Deseas mover la oportunidad a Documentos Legales?"
    }
  }
}
```

**Notas:**
- Si `dispara_cambio_estado = false`, `sugerencia` es `null`.
- El backend **no cambia** el estado de la oportunidad en este endpoint. El cambio de estado se hace mediante `PATCH /oportunidades/:id/estado` si el vendedor confirma.
- Si `fecha_ocurrencia` no viene en el body, se usa `NOW()`.

---

### PATCH /eventos/:id/descartado
> Marca un evento como descartado.

**Roles:** todos (`vendedor`: solo eventos de su oportunidad; roles de apoyo `analista`/`otro`: solo eventos de oportunidades donde colaboran vía tarea — no bloqueado, ver `matriz_permisos.md §2.5`)

**Body:** `{ "descripcion": "Evento ya no aplica" }` (opcional)

**Respuesta 200:** `{ "data": { "estado": "descartado" } }`

---

### PUT /eventos/:id
> Actualiza fechas o descripción de un evento pendiente.

**Roles:** todos (`vendedor`: solo eventos de su oportunidad; roles de apoyo `analista`/`otro`: solo eventos de oportunidades donde colaboran vía tarea — no bloqueado, ver `matriz_permisos.md §2.5`)

**Body:** `fecha_estimada`, `fecha_seguimiento`, `descripcion` — todos opcionales.

**Notas:** Solo se pueden editar eventos con `estado = 'pendiente'`.

**Respuesta 200:** el evento actualizado.

---

## 12. Tareas

### GET /tareas
> Lista de tareas con filtros.

**Roles:** todos (con filtro automático por rol)

**Query params:**

| Param | Tipo | Descripción |
|---|---|---|
| `id_empresa` | long | Tareas de una empresa |
| `id_oportunidad` | long | Tareas de una oportunidad |
| `estado_accion` | enum | `pendiente`, `completada`, `cancelada` |
| `id_asignado` | long | Por asignado (solo admin/gerencia/jdv) |
| `solo_prospeccion` | bool | Solo tareas sin oportunidad (`id_oportunidad IS NULL`) |
| `vencidas` | bool | Tareas pendientes con `fecha_ejecucion < NOW()` |

**Respuesta 200:**
```json
{
  "data": [
    {
      "id": 1,
      "id_empresa": 3,
      "empresa": { "id": 3, "razon_social": "Transp. Negociaciones Sta. Anita S.A." },
      "id_oportunidad": 101,
      "id_contacto": 5,
      "contacto": { "id": 5, "nombres": "Hugo", "apellidos": "Rodríguez" },
      "id_asignado": 1,
      "asignado": { "id": 1, "nombres": "Aldo", "apellidos": "Martínez" },
      "ids_colaboradores": [2, 4],
      "colaboradores": [
        { "id": 2, "nombres": "Diego", "apellidos": "Reyes" },
        { "id": 4, "nombres": "Lucía", "apellidos": "Vargas" }
      ],
      "tipo_accion": "reunion",
      "estado_accion": "pendiente",
      "descripcion": "Revisar minuta del contrato tripartito",
      "fecha_ejecucion": "2026-06-19T10:00:00Z",
      "created_at": "2026-06-15T09:00:00Z"
    }
  ]
}
```

**Notas:**
- `id_asignado` es el dueño de la tarea (único). `ids_colaboradores`/`colaboradores` son empleados adicionales que trabajan la tarea en conjunto con el dueño — no reemplazan a `id_asignado`, se suman.
- vendedor/analista ven una tarea si son el dueño **o** aparecen en `ids_colaboradores` (visibilidad de tareas, `matriz_permisos.md §2.6`).

---

### POST /tareas
> Crea una nueva tarea.

**Roles:** todos

**Body:**
```json
{
  "id_empresa": 3,
  "id_oportunidad": 101,
  "id_contacto": 5,
  "id_asignado": 1,
  "ids_colaboradores": [2, 4],
  "tipo_accion": "reunion",
  "descripcion": "Revisar minuta del contrato tripartito",
  "fecha_ejecucion": "2026-06-19T10:00:00Z"
}
```

**Respuesta 201:** la tarea creada.

**Notas:**
- `id_oportunidad` es opcional. Si es `null`, es una tarea de prospección.
- Si `id_oportunidad` es `null` y la empresa tiene oportunidades activas → `400 VALIDACION` con mensaje: `"Las tareas de empresas con oportunidades activas deben vincularse a una oportunidad"`.
- `id_asignado` es opcional. Si no viene, se asigna al usuario autenticado.
- `ids_colaboradores` es opcional (default `[]`). Cada colaborador recibe una notificación `tarea_colaborador_agregado` y ve la tarea en sus actividades, igual que el dueño.
- Asignar el dueño o un colaborador a un empleado **distinto del usuario autenticado** requiere rol admin/gerencia/jdv (`matriz_permisos.md §2.6`). Un vendedor/analista solo puede dejarse a sí mismo como dueño o colaborador → si no, `403 PERMISO_INSUFICIENTE`.

---

### PATCH /tareas/:id/completada
> Marca una tarea como completada.

**Roles:** todos (solo tareas donde es dueño o colaborador si es vendedor/analista)

**Body:** `{ "descripcion": null }` (descripción adicional opcional)

**Respuesta 200:** `{ "data": { "estado_accion": "completada" } }`

---

### PATCH /tareas/:id/cancelada
> Marca una tarea como cancelada.

**Roles:** todos (solo tareas donde es dueño o colaborador si es vendedor/analista)

**Respuesta 200:** `{ "data": { "estado_accion": "cancelada" } }`

---

### PUT /tareas/:id
> Actualiza una tarea pendiente.

**Roles:** todos (solo tareas donde es dueño o colaborador si es vendedor/analista)

**Body:** `tipo_accion`, `descripcion`, `fecha_ejecucion`, `id_contacto`, `id_asignado`, `ids_colaboradores` — todos opcionales.

**Notas:**
- Solo se pueden editar tareas con `estado_accion = 'pendiente'`.
- `ids_colaboradores`, si viene en el body (aunque sea `[]`), **reemplaza el set completo** de colaboradores de la tarea. Si se omite, los colaboradores existentes no se tocan. Solo los colaboradores agregados en esta llamada reciben notificación `tarea_colaborador_agregado`; los que ya estaban no se re-notifican y los removidos no reciben notificación de remoción.
- Si `id_asignado` cambia respecto al valor actual, el nuevo dueño recibe una notificación `tarea_creada`.
- Mismo requisito de permisos que en la creación: asignar a un empleado distinto del usuario autenticado requiere admin/gerencia/jdv.

**Respuesta 200:** la tarea actualizada.

---

## 13. Financiadoras

### GET /financiadoras
> Lista todas las financiadoras.

**Roles:** todos

**Respuesta 200:**
```json
{
  "data": [
    {
      "id": 1,
      "nombre": "Calidda – Fraccionamiento GNV",
      "monto_por_unidad": "45000.00",
      "plazo_meses": 48,
      "tea": "0.0000",
      "cuota_por_unidad": "937.50",
      "es_default": true,
      "notas": null
    }
  ]
}
```

---

### POST /financiadoras
> Crea una nueva financiadora.

**Roles:** `admin` `gerente`

**Body:**
```json
{
  "nombre": "Financiadora Alternativa S.A.",
  "monto_por_unidad": null,
  "plazo_meses": null,
  "tea": null,
  "cuota_por_unidad": null,
  "es_default": false,
  "notas": "Términos negociables por operación"
}
```

**Respuesta 201:** la financiadora creada.

**Notas:**
- Solo puede haber una financiadora con `es_default = true`. Si se intenta crear otra con `es_default = true` → `409`.

---

### PUT /financiadoras/:id
> Actualiza una financiadora.

**Roles:** `admin` `gerente`

**Body:** mismos campos que POST, todos opcionales.

**Respuesta 200:** la financiadora actualizada.

**Errores:**
- `409 FINANCIADORA_DEFAULT_REQUERIDA` — se intenta desmarcar (`es_default: false`) la única financiadora default; marca otra antes de desmarcar esta.

---

## 14. Modelos

### GET /modelos
> Lista el catálogo de modelos de bus.

**Roles:** todos

**Respuesta 200:**
```json
{
  "data": [
    {
      "id": 1,
      "codigo": "KinWin K12",
      "longitud": "12.00",
      "capacidad_tanques": "2x100L",
      "max_asientos": 45,
      "precio_base": "92000.00",
      "ficha_tecnica": "https://...",
      "aplicaciones": ["urbano", "interprovincial"]
    }
  ]
}
```

---

### POST /modelos
> Crea un nuevo modelo. Atómico: modelo + aplicaciones en una sola operación.

**Roles:** `admin` `gerente`

**Body:**
```json
{
  "codigo": "KinWin K11",
  "longitud": "12.00",
  "capacidad_tanques": "2x200L + 1x65L",
  "max_asientos": 42,
  "precio_base": "95000.00",
  "ficha_tecnica": "https://...",
  "aplicaciones": ["urbano"]
}
```

**Respuesta 201:** el modelo creado con sus aplicaciones.

**Notas:**
- Si `aplicaciones` viene vacío o no viene → `400 MODELO_SIN_APLICACIONES`.

---

### PUT /modelos/:id
> Actualiza un modelo. Si `aplicaciones` viene, reemplaza todas las aplicaciones actuales.

**Roles:** `admin` `gerente`

**Body:** mismos campos que POST, todos opcionales.

**Respuesta 200:** el modelo actualizado.

**Errores:**
- `409 CODIGO_DUPLICADO` (`field: "codigo"`) — el código ya lo usa otro modelo. Mismo código y `field` que devuelve `POST /modelos`.

---

## 15. Catálogo de eventos

### GET /catalogo-eventos
> Lista los eventos del catálogo.

**Roles:** todos

**Query params:** `etapa_asociada` (filtra por etapa, útil para la UI)

**Respuesta 200:**
```json
{
  "data": [
    {
      "id": 1,
      "nombre": "Fee depositado",
      "etapa_asociada": "evaluacion_calidda",
      "dispara_cambio_estado": false,
      "estado_destino": null,
      "es_recomendado": true,
      "es_hito_prospeccion": false
    }
  ]
}
```

---

### POST /catalogo-eventos
> Crea un evento en el catálogo.

**Roles:** `admin`

**Body:**
```json
{
  "nombre": "Inspección técnica de unidades",
  "etapa_asociada": "documentos_legales",
  "dispara_cambio_estado": false,
  "estado_destino": null,
  "es_recomendado": false,
  "es_hito_prospeccion": false
}
```

**Respuesta 201:** el evento creado.

---

### PUT /catalogo-eventos/:id
> Actualiza un evento del catálogo.

**Roles:** `admin`

**Body:** mismos campos que POST, todos opcionales.

**Respuesta 200:** el evento actualizado.

**Errores:**
- `409 NOMBRE_DUPLICADO` (`field: "nombre"`) — el nombre ya lo usa otro evento del catálogo. Mismo código y `field` que devuelve `POST /catalogo-eventos`.

---

## 16. Prospección

### GET /prospeccion
> Lista de empresas en prospección activa, con su avance calculado.
> Ordenado por: `checkpoints DESC`, `dias_sin_actividad DESC`.

**Roles:** todos (con filtro automático por rol)

**Respuesta 200:**
```json
{
  "data": [
    {
      "id_empresa": 6,
      "ruc": "20513480441",
      "razon_social": "Consorcio Primero de Setiembre S.A.C.",
      "corta": "Primero de Set.",
      "distrito": "Comas",
      "segmentos": ["urbano"],
      "contacto_principal": {
        "id": 8,
        "nombres": "Luis",
        "apellidos": "Maraví",
        "tlf_1": "997550025"
      },
      "checkpoints_completados": 1,
      "checkpoints_total": 3,
      "hitos": [
        { "nombre": "Reporte Tributario recibido", "completado": true, "fecha": "2026-06-11T10:00:00Z" },
        { "nombre": "Sentinel positivo", "completado": false, "fecha": null },
        { "nombre": "Reunión inicial realizada", "completado": false, "fecha": null }
      ],
      "dias_sin_actividad": 8,
      "ultima_actividad_at": "2026-06-11T10:00:00Z",
      "siguiente_tarea": "Validar filtro Sentinel",
      "lista_para_convertir": false
    }
  ],
  "meta": { "page": 1, "per_page": 20, "total": 4, "total_pages": 1 }
}
```

**Notas:**
- Solo devuelve empresas con `estado_cartera = 'prospeccion'`.
- `checkpoints_completados` se calcula contando eventos con `es_hito_prospeccion = true` y `estado = 'ocurrido'` vinculados a la empresa (sin `id_oportunidad`).
- `dias_sin_actividad` se calcula desde el `MAX(fecha_ejecucion)` de tareas completadas o `MAX(fecha_ocurrencia)` de eventos ocurridos sin oportunidad.
- `lista_para_convertir` es `true` cuando `checkpoints_completados = checkpoints_total`.
- `siguiente_tarea` es la descripción de la próxima tarea pendiente de la empresa (sin oportunidad), o `null`.

---

## 17. Inicio

### GET /inicio
> Datos del panel de inicio del usuario autenticado. Una sola llamada.

**Roles:** todos

**Respuesta 200:**
```json
{
  "data": {
    "tareas_pendientes": [
      {
        "id": 1,
        "descripcion": "Revisar minuta del contrato tripartito",
        "tipo_accion": "reunion",
        "fecha_ejecucion": "2026-06-19T10:00:00Z",
        "esta_vencida": false,
        "es_hoy": true,
        "empresa": { "id": 3, "razon_social": "Transp. Negociaciones Sta. Anita S.A." },
        "id_oportunidad": 101,
        "contacto": { "id": 5, "nombres": "Hugo", "apellidos": "Rodríguez" }
      }
    ],
    "eventos_por_seguir": [
      {
        "id": 2,
        "nombre": "Contrato tripartito firmado",
        "fecha_seguimiento": "2026-06-20",
        "seguimiento_vencido": false,
        "dispara_cambio_estado": false,
        "empresa": { "id": 3, "razon_social": "Transp. Negociaciones Sta. Anita S.A." },
        "id_oportunidad": 101
      }
    ],
    "resumen_pipeline": {
      "valor_total": "3050752.00",
      "oportunidades_activas": 6,
      "cantidad_unidades": 25,
      "por_etapa": {
        "evaluacion_calidda": { "count": 3, "valor": "1980800.00", "cantidad_unidades": 15 },
        "documentos_legales": { "count": 2, "valor": "1184702.00", "cantidad_unidades": 10 },
        "facturado":          { "count": 1, "valor": "884800.00",  "cantidad_unidades": 10 }
      }
    },
    "resumen_prospeccion": {
      "total": 4,
      "listas_para_convertir": 1,
      "requieren_atencion": 2
    },
    "meta_ventas": {
      "mensual": { "tiene_meta": true, "unidades_meta": 10, "unidades_logradas": 6, "porcentaje": 60 },
      "anual": { "tiene_meta": true, "unidades_meta": 120, "unidades_logradas": 60, "porcentaje": 50 },
      "equipo": null
    }
  }
}
```

**Notas:**
- `tareas_pendientes` ordenadas por `fecha_ejecucion ASC` (vencidas primero, luego hoy, luego próximas).
- `eventos_por_seguir` ordenados por `fecha_seguimiento ASC`.
- `resumen_prospeccion.requieren_atencion` = empresas con `checkpoints = 0` y `dias_sin_actividad >= 15`.
- `meta_ventas` es `null` para roles distintos de `vendedor`/`jdv` (no venden). Cuando no hay meta `aprobada` para el periodo, `tiene_meta` es `false` y `unidades_meta`/`porcentaje` vienen `null` (`unidades_logradas` siempre se calcula). `equipo` solo viene con datos para `jdv` (agregado de vendedores activos); para `vendedor` es `null`.

---

## 18. Reportes

Todos los endpoints de reportes requieren rol `admin`, `gerente` o `jdv`. Los vendedores no tienen acceso a reportes en el MVP.

Todos aceptan `fecha_desde` y `fecha_hasta` como query params (ISO 8601 date). Si no se especifican, el default es el mes calendario actual.

---

### GET /reportes/ventas
> Ventas cerradas (oportunidades en `facturado`) en el período.

**Query params:** `fecha_desde`, `fecha_hasta`, `id_vendedor`

**Respuesta 200:**
```json
{
  "data": {
    "monto_total": "884800.00",
    "unidades_total": 10,
    "operaciones_count": 1,
    "ticket_promedio": "884800.00",
    "dcto_promedio": "4.00",
    "por_mes": [
      { "mes": "2026-06", "monto": "884800.00", "unidades": 10, "operaciones": 1 }
    ],
    "por_vendedor": [
      { "id_vendedor": 1, "nombre": "Aldo Martínez", "monto": "884800.00", "unidades": 10 }
    ],
    "por_modelo": [
      { "modelo": "KinWin K12", "unidades": 10, "monto": "884800.00" }
    ]
  }
}
```

---

### GET /reportes/pipeline
> Estado actual del pipeline.

**Respuesta 200:**
```json
{
  "data": {
    "por_etapa": [
      {
        "etapa": "evaluacion_calidda",
        "count": 3,
        "valor": "1980800.00",
        "tiempo_promedio_dias": 18,
        "oportunidades_sobre_promedio": 1
      }
    ],
    "total_activo": "3050752.00",
    "concentracion_calidda_pct": "87.50",
    "oportunidades_sin_actividad": [
      {
        "id": 106,
        "empresa": "Marova Tours S.A.C.",
        "estado": "evaluacion_calidda",
        "dias_sin_actividad": 12,
        "monto_total": "368000.00",
        "vendedor": "Aldo Martínez"
      }
    ]
  }
}
```

---

### GET /reportes/equipo
> Resumen de actividad y performance del equipo de ventas.

**Query params:** `fecha_desde`, `fecha_hasta`

**Respuesta 200:**
```json
{
  "data": [
    {
      "vendedor": { "id": 1, "nombre": "Aldo Martínez" },
      "oportunidades_activas": 6,
      "valor_pipeline": "3050752.00",
      "oportunidades_cerradas_mes": 1,
      "valor_cerrado_mes": "884800.00",
      "tareas_completadas_semana": 3,
      "tareas_vencidas": 0,
      "dias_ultimo_registro": 0,
      "dcto_promedio": "2.80",
      "velocidad_promedio_dias": 42
    }
  ]
}
```

---

### GET /reportes/velocidad-etapas
> Tiempo promedio histórico por etapa. Requiere suficiente historial para ser significativo.

**Respuesta 200:**
```json
{
  "data": [
    {
      "etapa": "evaluacion_calidda",
      "dias_promedio": 28,
      "dias_mediana": 24,
      "muestra": 5
    }
  ],
  "meta": {
    "advertencia": "Muestra reducida. Los promedios pueden no ser representativos con menos de 10 operaciones por etapa."
  }
}
```

---

### GET /reportes/prospeccion
> Embudo de conversión de prospección.

**Query params:** `fecha_desde`, `fecha_hasta`, `id_vendedor`

**Respuesta 200:**
```json
{
  "data": {
    "ingresadas": 8,
    "hito_1_completado": 6,
    "hito_2_completado": 4,
    "hito_3_completado": 3,
    "convertidas_a_oportunidad": 3,
    "tasa_conversion_pct": "37.50",
    "tiempo_promedio_conversion_dias": 21,
    "por_origen_lead": [
      { "origen": "cartera", "ingresadas": 5, "convertidas": 3, "tasa_pct": "60.00" },
      { "origen": "visita_fria", "ingresadas": 3, "convertidas": 0, "tasa_pct": "0.00" }
    ]
  }
}
```

---

### GET /reportes/descuentos
> Mix de descuentos por vendedor y tendencia.

**Query params:** `fecha_desde`, `fecha_hasta`

**Respuesta 200:**
```json
{
  "data": {
    "dcto_promedio_global": "2.80",
    "por_vendedor": [
      {
        "vendedor": "Aldo Martínez",
        "dcto_promedio": "2.80",
        "operaciones_sin_dcto": 1,
        "operaciones_con_dcto": 2,
        "dcto_maximo_aplicado": "5.00"
      }
    ]
  }
}
```

---

## 19. Notificaciones

Notifica a un usuario cuando ocurre una acción relacionada con él pero no accionada por él mismo. También cubre recordatorios de tareas y eventos (job programado, sin actor humano).

**Tipo (`tipo`):** los 16 valores reales de `TipoNotificacion` (`NotificacionEnums.kt`, migración V22/V28/V34):
- `oportunidad_cambio_estado`, `empresa_convertida`, `evento_creado`, `tarea_creada`, `tarea_colaborador_agregado`, `empresa_asignada`, `oportunidad_traspasada`, `tarea_recordatorio`, `evento_recordatorio` — el set original.
- `solicitud_creada`, `solicitud_aprobada`, `solicitud_denegada` — ciclo de vida de una Solicitud (§20).
- `meta_propuesta`, `meta_aprobada`, `meta_rechazada`, `meta_modificada` — ciclo de vida de una Meta de venta (§21).

**Entidad referenciada (`entidad_tipo`):** los 4 valores reales de `EntidadNotificacion`: `oportunidad` | `empresa` | `solicitud` | `meta_venta` — nunca una tarea/evento suelto; para tareas/eventos se referencia su oportunidad si tiene una, si no su empresa.

**DTO `Notificacion`:**
```json
{
  "id": 1,
  "tipo": "oportunidad_cambio_estado",
  "mensaje": "Carlos Pérez cambió el estado de Transportes ABC a Documentos legales",
  "entidad_tipo": "oportunidad",
  "entidad_id": 101,
  "leida": false,
  "created_at": "2026-07-09T14:30:00Z",
  "actor": { "id": 5, "nombres": "Carlos", "apellidos": "Pérez" }
}
```
`actor` es `null` para recordatorios generados por el sistema (job programado, sin actor humano).

---

### GET /notificaciones/no-leidas/count
> Cuenta las notificaciones no leídas del usuario autenticado.

**Roles:** todos

**Respuesta 200:**
```json
{ "data": { "count": 5 } }
```

---

### GET /notificaciones
> Últimas 20 notificaciones (leídas + no leídas) del usuario autenticado, más recientes primero. Sin paginación.

**Roles:** todos

**Respuesta 200:** `{ "data": [ /* NotificacionDto[] */ ] }`

---

### PATCH /notificaciones/:id/leida
> Marca una notificación propia como leída.

**Roles:** todos (solo notificaciones propias)

**Respuesta 200:** `{ "data": { "leida": true } }`

**Notas:**
- Si la notificación no existe o no pertenece al usuario autenticado → `404 NO_ENCONTRADO`.

---

### PATCH /notificaciones/leidas
> Marca todas las notificaciones no leídas del usuario autenticado como leídas.

**Roles:** todos

**Respuesta 200:** `{ "data": { "leida": true } }`

---

## 20. Solicitudes

Capa intermedia de aprobación: cuando `vendedor`/`jdv` intentan una acción por encima de su permiso (hoy: descuentos sobre su límite y reasignación de clientes por el `jdv`), envían una Solicitud en vez de aplicar el cambio directo. El aprobador (`jdv` o `gerencia`, según el caso) la aprueba o deniega; `admin` puede resolver ambas bandejas. **Los roles de apoyo (`analista`, `otro`) no crean solicitudes** (2026-08-18) — no tienen margen de descuento por ninguna vía ni reasignan clientes; ver `POST /solicitudes` abajo. Ver `gerencia_solicitudes_modelo_datos.md` y `gerencia_contrato_frontend.md` para el detalle completo.

### POST /solicitudes
> Crea una solicitud de aprobación.

**Roles:** `vendedor` `jdv` (según tipo; `gerencia`/`admin` no solicitan, ejecutan directo). **Los roles de apoyo (`analista`, `otro`) no pueden crear ninguna solicitud: `403 PERMISO_INSUFICIENTE`** (2026-08-18) — no tienen margen de descuento por ninguna vía ni reasignan clientes.

**Body (descuento):**
```json
{
  "tipo": "descuento",
  "entidad_tipo": "oportunidad",
  "entidad_id": 45,
  "dcto_solicitado": "5.00",
  "motivo": "Cliente frecuente, tercera compra del año"
}
```

**Body (reasignación de cliente — solo `jdv`):**
```json
{
  "tipo": "reasignacion_cliente",
  "entidad_tipo": "empresa",
  "entidad_id": 12,
  "id_vendedor_nuevo": 8,
  "motivo": "El vendedor actual sale de vacaciones largas"
}
```

**Respuesta 201:** el objeto solicitud (`id`, `tipo`, `estado: "pendiente"`, `rol_aprobador`, `entidad_tipo`, `entidad_id`, `entidad_descripcion`, `dcto_solicitado`, `id_vendedor_nuevo`, `motivo`, `solicitante`, `created_at`). `rol_aprobador` lo deriva el backend (nunca se acepta en el body).

**Errores:** `400 VALIDACION` (falta motivo, payload no corresponde al tipo, o el descuento está dentro del límite propio) · `403 PERMISO_INSUFICIENTE` (rol no puede solicitar ese tipo) · `404 NO_ENCONTRADO` (entidad no existe o no es visible) · `409 SOLICITUD_DUPLICADA` (ya hay una pendiente del mismo tipo sobre esa entidad).

---

### GET /solicitudes
> Lista solicitudes, paginado estándar (§4). La visibilidad la decide el backend: `admin` ve todas; `gerencia` las dirigidas a `gerencia`; `jdv` las dirigidas a `jdv` + las propias; `vendedor`/`analista`/`otro` solo las propias.

**Query params:** `estado` (`pendiente|aprobada|denegada`), `tipo`, `mias=true` (fuerza "solo las que yo creé").

---

### GET /solicitudes/:id
> Detalle. `404` si no es visible para el usuario (IDOR).

---

### PATCH /solicitudes/:id/aprobar
> Aprueba y aplica el cambio de inmediato (misma transacción). Notifica al solicitante.

**Roles:** el rol `rol_aprobador` de la solicitud, o `admin`.

**Body:** vacío.

**Errores:** `403 PERMISO_INSUFICIENTE` (no es el aprobador) · `409 SOLICITUD_YA_RESUELTA` · `409 SOLICITUD_NO_APLICABLE` (la entidad cambió y el efecto ya no aplica; el aprobador debe denegarla manualmente).

---

### PATCH /solicitudes/:id/denegar
> Deniega. El motivo es obligatorio. Notifica al solicitante.

**Roles:** el rol `rol_aprobador` de la solicitud, o `admin`.

**Body:** `{ "motivo": "El margen de este modelo no soporta ese descuento" }`

**Errores:** `400 VALIDACION` (falta motivo) · `409 SOLICITUD_YA_RESUELTA`.

---

## 21. Metas de venta

Meta de unidades vendidas (no monto) por vendedor/jdv, mensual (12 meses) + anual (calculada = suma de los meses). Una fila por `(id_empleado, año)`. El JDV propone el año completo de un vendedor (o el suyo propio); Gerencia aprueba, rechaza (con motivo) o modifica directamente (auto-aprobado). Las unidades de una oportunidad solo cuentan para el cumplimiento cuando está `facturado`; si se cancela o se elimina estando facturada, dejan de contar automáticamente (sin acción manual).

### POST /metas-venta
> Propone (jdv) o crea/sobreescribe directo y aprobado (gerencia/admin) la meta de un empleado para un año.

**Roles:** `jdv` `gerencia` `admin`

**Body:**
```json
{
  "id_empleado": 5,
  "anio": 2027,
  "meta_enero": 8, "meta_febrero": 8, "meta_marzo": 10, "meta_abril": 10,
  "meta_mayo": 10, "meta_junio": 12, "meta_julio": 12, "meta_agosto": 10,
  "meta_septiembre": 10, "meta_octubre": 10, "meta_noviembre": 12, "meta_diciembre": 12
}
```

**Respuesta 201:** el objeto meta (`id`, `id_empleado`, `empleado`, `anio`, `meta_enero`..`meta_diciembre`, `meta_anual`, `estado`, `propuesto_por`, `resolutor`, `motivo_rechazo`, `resolved_at`, `created_at`). `meta_anual` lo calcula el backend (suma de los 12 meses); nunca se acepta como input.

**Errores:** `400 VALIDACION` (falta algún mes o `id_empleado`/`anio`) · `403 PERMISO_INSUFICIENTE` (rol no puede proponer) · `404` no aplica (id_empleado inválido es `400 VALIDACION`, campo `id_empleado`) · `409 META_YA_EXISTE` (jdv sobre una fila `propuesta`/`aprobada` existente; usar `PATCH`).

---

### PATCH /metas-venta/:id
> Edita cualquier subconjunto de los 12 meses de una meta existente. Recalcula `meta_anual` y deja la meta `aprobada`.

**Roles:** `gerencia` `admin`

**Body:** cualquier subconjunto de `meta_enero`..`meta_diciembre`, por ejemplo `{ "meta_marzo": 15 }`.

**Errores:** `400 VALIDACION` · `403 PERMISO_INSUFICIENTE` · `404 NO_ENCONTRADO` · `409 META_RECHAZADA` (no se edita una rechazada; debe volver a proponerse).

---

### PATCH /metas-venta/:id/aprobar
> Aprueba una meta `propuesta` tal cual fue propuesta. Notifica al JDV proponente.

**Roles:** `gerencia` `admin`

**Body:** vacío.

**Errores:** `403 PERMISO_INSUFICIENTE` · `409 META_YA_RESUELTA`.

---

### PATCH /metas-venta/:id/rechazar
> Rechaza una meta `propuesta`. El motivo es obligatorio (ahí se especifica qué corregir). Notifica al JDV.

**Roles:** `gerencia` `admin`

**Body:** `{ "motivo": "Marzo está muy alto respecto al histórico del vendedor" }`

**Errores:** `400 VALIDACION` (falta motivo) · `403 PERMISO_INSUFICIENTE` · `409 META_YA_RESUELTA`.

---

### GET /metas-venta
> Lista metas, paginado estándar (§4). `admin`/`gerencia`/`jdv` ven todas (el jdv ve todo el equipo, incluida la suya); `vendedor`/`analista`/`otro` solo las propias (en la práctica vacío para los roles de apoyo, que no tienen meta).

**Query params:** `id_empleado`, `anio`, `estado` (`propuesta|aprobada|rechazada`).

---

### GET /metas-venta/:id
> Detalle. `404` si no es visible para el usuario (IDOR).

---

**Nota — panel de Inicio:** `GET /inicio` (§17) incluye `meta_ventas` (null para roles distintos de `vendedor`/`jdv`) con el cumplimiento mensual/anual y, para `jdv`, el agregado del equipo. Ver §17.

---

## 22. Mantenimiento

### POST /mantenimiento/carpetas-drive
> Crea las carpetas de Google Drive que faltan en empresas y oportunidades anteriores a la integración.

**Roles:** `admin`

**Query params:** `tamano_lote` (opcional). Sin él procesa **todos** los pendientes en un solo llamado.

**Respuesta 200:**

```json
{
  "data": {
    "empresas_procesadas": 12,
    "oportunidades_procesadas": 30,
    "errores": [
      { "entidad": "empresa", "id": 7, "motivo": "Google Drive no pudo crear la carpeta" }
    ],
    "pendientes_restantes": 1
  }
}
```

**Notas:**
- Idempotente y re-ejecutable. Si no hay pendientes responde todo en cero sin tocar Drive.
- Cada carpeta se persiste en su propia transacción: si la llamada se corta a la mitad, lo ya procesado queda guardado y repetir el endpoint retoma donde quedó.
- Un registro que falle no aborta el resto: se lista en `errores` y sigue pendiente. Repetir el endpoint lo reintenta.
- `pendientes_restantes > 0` significa que hace falta volver a llamarlo (por `tamano_lote` o por errores).

---

## 23. Enums

> Valores exactos que viajan en `campos` de tipo enum, en minúscula, tal cual los define PostgreSQL (migración V1 y siguientes) y los enums Kotlin del backend. Un valor fuera de esta lista responde `400 VALIDACION`. Verificado contra el schema real de producción (Supabase) el 2026-08-17 — sin deriva respecto a las migraciones locales (V1–V39).
>
> Si agregas o renombras un valor (migración nueva), actualiza esta tabla en el mismo commit.

| Enum | Usado en | Valores |
|---|---|---|
| `rol_empleado` | `Empleado.rol` | `admin`, `gerencia`, `jdv`, `vendedor`, `analista`, `otro` |
| `estado_cartera_enum` | `Empresa.estado_cartera` | `no_contactado`, `no_aplica`, `no_interesado`, `prospeccion`, `oportunidad_activa`, `cliente` |
| `segmento_enum` | `Empresa.segmento` (`empresa_segmentos`) | `urbano`, `personal`, `turismo`, `interprovincial`, `otro` |
| `origen_lead_enum` | `Empresa.origen_lead` | `cartera`, `visita_fria`, `referido_calidda`, `red_contactos`, `otro` |
| `estado_op_enum` | `Oportunidad.estado` | `evaluacion_calidda`, `documentos_legales`, `facturado`, `cerrado` |
| `aplicacion_enum` | `Modelo.aplicaciones` (`modelo_aplicaciones`) | `urbano`, `interprovincial`, `turismo`, `personal` |
| `estado_evento_enum` | `Evento.estado` | `pendiente`, `ocurrido`, `descartado` |
| `tipo_accion_enum` | `Tarea.tipo_accion` | `llamada`, `correo`, `reunion`, `whatsapp`, `otro` |
| `estado_accion_enum` | `Tarea.estado_accion` | `pendiente`, `completada`, `cancelada` |
| `tipo_solicitud_enum` | `Solicitud.tipo_solicitud` | `descuento`, `reasignacion_cliente` |
| `estado_solicitud_enum` | `Solicitud.estado` | `pendiente`, `aprobada`, `denegada` |
| `aprobador_solicitud_enum` | `Solicitud.aprobador_rol` | `jdv`, `gerencia` |
| `entidad_solicitud_enum` | `Solicitud.entidad_tipo` | `oportunidad`, `empresa` |
| `estado_meta_enum` | `MetaVenta.estado` | `propuesta`, `aprobada`, `rechazada` |
| `tipo_notificacion_enum` | `Notificacion.tipo` | `oportunidad_cambio_estado`, `empresa_convertida`, `evento_creado`, `tarea_creada`, `tarea_colaborador_agregado`, `empresa_asignada`, `oportunidad_traspasada`, `tarea_recordatorio`, `evento_recordatorio`, `solicitud_creada`, `solicitud_aprobada`, `solicitud_denegada`, `meta_propuesta`, `meta_aprobada`, `meta_rechazada`, `meta_modificada` |
| `entidad_notificacion_enum` | `Notificacion.entidad_tipo` | `oportunidad`, `empresa`, `solicitud`, `meta_venta` |

**No expuestos por la API** (uso interno, dedup del job de recordatorios — no aparecen en ningún request/response): `origen_recordatorio_enum` (`tarea`, `evento`), `umbral_recordatorio_enum` (`proximo`, `vencido`).

---

## 24. Notas operativas — Drive

> Aclaraciones sobre el flujo de archivos de Drive (§8 Empresas, §10 Oportunidades, §22 Mantenimiento) que no se desprenden de la firma de los endpoints. El equipo de frontend las traía documentadas por separado, confirmadas de palabra con backend el 2026-07-31; quedan incorporadas aquí, en el contrato oficial, el 2026-08-17 tras verificarlas contra el código actual (`EmpresaDriveController.kt`, `OportunidadDriveController.kt`, `DriveMultipartUploader.kt`, `DriveProperties.kt`, `GlobalExceptionHandler.kt`).

- **Creación de carpeta al subir sobre `drive_folder_id: null`:** `POST /empresas/:id/archivos` y `POST /oportunidades/:id/archivos` llaman primero a `asegurarCarpetaDrive`, que crea la carpeta en ese momento si `drive_folder_id` es `null` y la persiste antes de subir el archivo — no devuelve 404. El 404 solo ocurre si la entidad no existe o es ajena al usuario (chequeo de visibilidad corre antes de tocar Drive, por diseño de IDOR). Consecuencia para el cliente: tras esa primera subida, el detalle de la entidad debe refrescarse, porque `drive_folder_id` ya dejó de ser `null` en el servidor.
- **Unidad del límite de tamaño:** el límite de archivo es `app.drive.max-file-size-bytes`, por defecto **`104_857_600` bytes exactos** (100 × 1024 × 1024 = MiB, no MB decimales — ver `DriveProperties.DEFAULT_MAX_FILE_SIZE_BYTES`). El límite se aplica sobre el stream ya desenmarcado del multipart (`StreamAcotado` envuelve `parte.inputStream`, después de que `commons-fileupload2` separa boundary/headers/CRLFs): el framing nunca cuenta contra el tope. Validar contra `file.size` en el cliente es exacto y no requiere reservar margen.
- **Errores sin envelope:** el deploy (Render/Railway, ver `DEVOPS-backend.md` §6.1) no tiene nginx propio — corre detrás del proxy de borde de la plataforma, que puede cortar una petición antes de que llegue a la API Spring. En ese caso la respuesta **no trae el envelope** `{ data, meta, error }` ni `error.code`. El cliente nunca debe leer `error.code` a ciegas: si el body no parsea como el envelope esperado, cae al mensaje genérico. Cuando la petición sí llega a la API, un 413 por archivo grande **siempre** trae el envelope con `code: "ARCHIVO_DEMASIADO_GRANDE"` (`GlobalExceptionHandler.handleUploadTooLarge`). Excepción razonada para el cliente: un 413 sin envelope se puede tratar igual que `ARCHIVO_DEMASIADO_GRANDE`, porque ese status solo puede significar eso.

---

## 25. Changelog del contrato

> Registro de cambios a este contrato desde que la app está en producción (2026-08-18 en adelante — no se reconstruyen entradas retroactivas para lo anterior a esa fecha). **Todo PR que modifique la forma de un request/response, un código de error, la semántica de un campo, o agregue/quite un endpoint documentado aquí, agrega una entrada a esta tabla en el mismo PR.** Sin entrada, el PR no se considera completo aunque el código y los tests pasen.

**Breaking vs non-breaking, para este contrato:**
- **Breaking** — requiere que el frontend actualice código antes o al mismo tiempo del deploy: quitar o renombrar un campo de un response, cambiar el tipo/formato de un campo existente, cambiar un código de error ya usado, cambiar el status HTTP de un caso ya documentado, quitar un endpoint, agregar un campo *requerido* a un request.
- **Non-breaking** — el frontend puede ignorarlo hasta que lo adopte: nuevo endpoint, nuevo campo *opcional* en un response, nuevo valor de enum aditivo en un campo que el cliente ya trata con un `default`/`else`, aclaración de comportamiento no observable en la firma (como las notas de §24).

| Fecha | Endpoint(s) | Tipo | Cambio | Acción para frontend |
|---|---|---|---|---|
| 2026-08-18 | — | — | Se crea este changelog. Sin entradas retroactivas. | Ninguna |
| 2026-08-18 | `GET /oportunidades`, `GET /oportunidades/:id`, `GET /empresas`, `GET /empresas/:id`, `PATCH /oportunidades/:id/estado`, `POST /oportunidades`, `PUT /oportunidades/:id`, `POST /empresas`, `PUT /empresas/:id`, `PATCH /empresas/:id/estado-cartera`, `PATCH /empresas/:id/vendedor`, `PATCH /empresas/:id/cartera-maestra`, `POST /oportunidades/:id/archivos`, `POST /oportunidades/:id/carpeta-drive`, `POST /empresas/:id/archivos`, `POST /empresas/:id/carpeta-drive`, `POST /solicitudes` | **Breaking** | `analista` y `otro` pasan a roles de apoyo sin cartera propia: en empresas y oportunidades (los recursos que lista esta fila) los listados y el detalle solo devuelven las entidades donde el usuario colabora vía tarea (`ids_colaboradores`), y toda escritura sobre esos dos recursos (incluida la subida de archivos/creación de carpeta en Drive) responde `403 PERMISO_INSUFICIENTE` con mensaje específico; `analista` deja de poder confirmar `facturado`; ninguno de los dos aplica descuentos por ninguna vía ni crea solicitudes de aprobación. Eventos y la vinculación de contactos a empresa no tienen guard de escritura propio y heredan la visibilidad por colaboración (detalle en `matriz_permisos.md §2.3/§2.5`). Ver `matriz_permisos.md` para el detalle completo por operación. | Ocultar en el cliente las acciones de escritura y de subida de Drive para estos roles, y no asumir que "lo que veo, lo puedo editar". El 403 trae un mensaje específico que se puede mostrar tal cual. Las solicitudes históricas que estos roles ya tenían siguen siendo visibles (no hay regresión de lectura ahí). |
| 2026-08-19 | `GET /solicitudes` | Non-breaking (fix de seguridad) | El filtro de visibilidad del listado no tenía ninguna rama para el rol `otro` y devolvía todas las solicitudes de la empresa sin restricción, incluidos montos de descuento y motivos de reasignación ajenos. Corregido: `otro` ahora solo ve las solicitudes que él mismo creó, igual que `analista`. | Ninguna — el comportamiento correcto ya era el documentado; ningún cliente debía depender de la fuga. |
| 2026-08-19 | `GET /metas-venta` | Non-breaking (fix de seguridad) | El filtro de visibilidad del listado tenía la misma falla que `GET /solicitudes`: ninguna rama para el rol `otro`, que veía todas las metas del equipo sin restricción. Corregido: `otro` ahora solo ve su propia meta, igual que `analista`. | Ninguna — el comportamiento correcto ya era el documentado; ningún cliente debía depender de la fuga. |
| 2026-08-20 | `GET /contactos`, `GET /contactos/:id`, `PUT /contactos/:id` | **Breaking** | Cierre de la última fuga de visibilidad del cambio de roles de apoyo del 2026-08-18: el módulo `contactos` no se había tocado y `analista`/`otro` listaban, abrían y **editaban** nombre, teléfono y correo de todos los contactos del CRM. Ahora: (1) `GET /contactos` y `GET /contactos/:id` solo devuelven, para esos roles, los contactos vinculados a empresas donde colaboran vía tarea — el contacto sin empresa (huérfano) queda fuera; el que queda fuera de alcance en el detalle responde `404 NO_ENCONTRADO`. (2) Se agrega el query param `contexto` (`listado` \| `vincular`) a ambos GET: `vincular` levanta el filtro para que el buscador de "vincular contacto existente" siga alcanzando todo el CRM, pero recorta la respuesta a `id`/`nombres`/`apellidos` y hace que `q` busque solo por nombre, no por teléfono. Ausente ⇒ `listado`; valor desconocido ⇒ `400 VALIDACION`. (3) `PUT /contactos/:id` responde `403 PERMISO_INSUFICIENTE` para `analista`/`otro` sobre un contacto fuera de su alcance. El resto de roles no cambia en nada. Ver `matriz_permisos.md §1` y `§2.3`. | **Enviar `contexto=vincular` en el buscador de vincular contacto** — sin él ese buscador deja de encontrar contactos fuera del alcance del usuario de apoyo y el flujo se rompe para esos roles. La vista de listado no necesita cambios (el default ya es el correcto). Para `analista`/`otro` el cliente debe tolerar filas con `tlf_*`/`email_*` nulos y `empresas`/`oportunidades_count` vacíos en modo `vincular`, y un `403` con mensaje mostrable al editar. La mitigación de UI que ocultaba la sección Contactos para estos roles ya puede retirarse: el control ahora está en el backend. |
| 2026-08-20 | `POST /empresas/:id/contactos`, `PUT /empresas/:id/contactos/:contacto_id`, `DELETE /empresas/:id/contactos/:contacto_id` | **Breaking** | Hallazgo de la revisión final del cambio de visibilidad de contactos: la vinculación de contactos a empresas no tenía guard de escritura para roles de apoyo (a diferencia de la vinculación a oportunidades, que sí lo tenía desde el 2026-08-18). Combinado con el nuevo `contexto=vincular` de `GET /contactos` (que busca en todo el CRM por diseño), esto abría un camino para que `analista`/`otro` vincularan cualquier contacto a una empresa donde colaboran y luego lo vieran completo. Corregido: las tres operaciones de vinculación ahora responden `403 PERMISO_INSUFICIENTE` para `analista`/`otro`, sin excepción — mismo criterio que oportunidades. | Ocultar las acciones de vincular/editar vínculo/desvincular contacto para `analista`/`otro` en el cliente; el 403 trae mensaje mostrable. |

---

## Apéndice — Endpoints no implementados en MVP

Los siguientes endpoints se reservan para fases posteriores y **no deben implementarse** en el MVP:

- `GET|POST|PUT /buses-entregados` — gestión de entrega de unidades
- `GET /reportes/comisiones` — cálculo de comisiones
- `POST /empresas/import` — importación masiva desde Excel
- `GET /reportes/proyeccion` — proyección de ingresos ponderada por etapa
- `PUT /empleados/:id/permisos` — gestión de permisos granulares por rol
