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
22. [Tipo de cambio](#22-tipo-de-cambio)
23. [Simulaciones](#23-simulaciones)
24. [Calculadora Financiera](#24-calculadora-financiera)
25. [Mantenimiento](#25-mantenimiento)
26. [Enums](#26-enums)
27. [Notas operativas — Drive](#27-notas-operativas--drive)
28. [Changelog del contrato](#28-changelog-del-contrato)

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
| `ULTIMO_ITEM_NO_ELIMINABLE` | 409 | Una oportunidad no puede quedarse sin ítems |
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
      "estado": "documentos_legales",
      "items": [
        {
          "id": 501,
          "id_modelo": 1,
          "modelo": { "id": 1, "codigo": "KinWin K12", "precio_base": "92000.00" },
          "cantidad": 8,
          "precio_venta": "92000.00",
          "descuento": "3.00",
          "cuota_financiadora": "937.50",
          "cuota_quantum": "1234.56",
          "cuota_total": "2172.06",
          "monto_item": "713920.00",
          "advertencias": []
        }
      ],
      "monto_total": "713920.00",
      "cuota_quantum_total": "9876.48",
      "cuota_total": "17376.48",
      "cuota_diaria_total": "789.84",
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

**Notas (V42 — multi-modelo):**
- `id_modelo`, `modelo`, `cantidad`, `precio_unitario`, `dcto` **ya no viven en la raíz** de la oportunidad: cada modelo vendido es un `item` dentro de `items`. Una oportunidad con un solo modelo sigue teniendo un `items` de un solo elemento — no cambia el caso de uso simple, solo dónde vive el dato.
- `monto_total` se queda en la raíz: es la suma de `monto_item` de todos los ítems, calculado y de solo lectura (igual que antes).
- `sort=precio_unitario` **ya no es un valor válido** — un "precio unitario" no significa nada con varios modelos por oportunidad. `sort=cantidad` y `sort=monto_total` se mantienen: siguen siendo agregados sobre los ítems, con el mismo resultado observable que antes.

**Campos de cuota Quantum (`reglas_simulaciones.md` §6.2):**
- Por ítem: `cuota_quantum` es la `cuota_final` de la simulación **principal** de ese ítem si tiene una, o el cálculo efímero de §6.1 (con los parámetros por defecto del módulo Simulaciones) si no. `cuota_total` es `cuota_quantum + cuota_financiadora`. Ambos son `null` cuando no hay ninguna cuota calculable para ese ítem (ítem incompleto, o parámetros por defecto inválidos para su precio — degradación silenciosa, nunca un error).
- A nivel de oportunidad: `cuota_quantum_total` es la Σ de `cuota_quantum × cantidad` de todos los ítems; `cuota_total` (a nivel de oportunidad) es la Σ de `cuota_total_item × cantidad`; `cuota_diaria_total` es `cuota_total / dias_trabajados` (constante 22, no la de ninguna simulación en particular).
- **Los tres campos de nivel oportunidad son `null` conjuntamente si CUALQUIER ítem no tiene una cuota calculable** (`cuota_quantum` nulo, o `cantidad` nulo) — no es un error: el cliente debe tratarlo como "no se puede mostrar la cuota total todavía", nunca como "la cuota es cero". Es deliberadamente distinto del criterio de `monto_total`, donde un ítem incompleto aporta 0: omitir un ítem en silencio en una cuota subestimaría cuánto paga el cliente al mes.

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
  "descuento": 3.00,
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
- `id_modelo`, `cantidad` y `descuento` (antes `dcto`) viajan planos en este body, igual que antes — el backend construye internamente con ellos el primer `item` de la oportunidad (`items[0]` en la respuesta). Para vender más de un modelo en la misma oportunidad, se crean ítems adicionales después con `POST /oportunidades/:id/items`.
- `monto_total` NO se acepta en el body. Si viene, se ignora y se calcula.
- El `precio_venta` del ítem creado se inicializa con `modelos.precio_base` del modelo seleccionado.
- `id_vendedor` se toma de `empresas.id_vendedor` en el momento de la creación. **Excepción:** si la empresa no tiene vendedor asignado (solo la ven roles supervisores), quien crea con `gerencia`/`admin` DEBE enviar `id_vendedor` en el body — la empresa queda asignada a ese vendedor en la misma operación. Si falta → `400 VALIDACION` (`field: "id_vendedor"`).
- `id_financiadora` es opcional — si no viene, se usa la que tenga `es_default = true`.
- Si `descuento` supera el límite del rol (§5) → `422 APROBACION_REQUERIDA`: no se crea la oportunidad. Crear primero sin descuento (o dentro del límite) y solicitar el mayor después sobre el ítem ya creado.
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

**Body:** `garantia`, `finc_paralelo`, `ficha_venta`, `notas`, `fecha_cierre_estimado` — todos opcionales.

**Notas:**
- Este endpoint **ya NO acepta** `id_modelo`, `cantidad`, `precio_unitario`/`precio_venta`, `dcto`/`descuento` ni `monto_total` — esos campos viven en los ítems (V42) y se editan por `POST/PUT/DELETE /oportunidades/:id/items` (abajo). Si vienen en el body, se ignoran en silencio (no hay error): el DTO simplemente no los declara.
- `estado`, `id_empresa`, `id_vendedor` tampoco se aceptan en este endpoint.

**Respuesta 200:** la oportunidad actualizada.

---

### POST /oportunidades/:id/items
> Agrega un ítem (modelo + cantidad + condiciones comerciales) a una oportunidad existente. Así se vende más de un modelo en la misma oportunidad.

**Roles:** los mismos que editar la oportunidad: `admin` `gerencia` `jdv` `vendedor` (solo su oportunidad) — **los roles de apoyo (`analista`, `otro`) no pueden crear ítems: `403 PERMISO_INSUFICIENTE`**.

**Body:**
```json
{
  "id_modelo": 1,
  "cantidad": 4,
  "precio_venta": 92000.00,
  "descuento": 3.00,
  "cuota_financiadora": 937.50
}
```

Todos los campos son opcionales salvo `id_modelo`. Sin `precio_venta`, se inicializa con `modelos.precio_base` del modelo seleccionado (igual que en `POST /oportunidades`).

**Respuesta 201:**
```json
{
  "data": {
    "id": 502,
    "id_modelo": 1,
    "modelo": { "id": 1, "codigo": "KinWin K12", "precio_base": "92000.00" },
    "cantidad": 4,
    "precio_venta": "92000.00",
    "descuento": "3.00",
    "cuota_financiadora": "937.50",
    "monto_item": "356976.00",
    "cuota_quantum": null,
    "cuota_total": null,
    "advertencias": []
  }
}
```

**Notas:**
- `monto_item` es el subtotal del ítem: `cantidad × precio_venta × (1 − descuento/100)`. Calculado y de solo lectura.
- **`cuota_quantum` y `cuota_total` (§6.2) siempre vienen `null` en la respuesta de este endpoint** — no es "sin cuota calculable" (§6.2), es que este endpoint no las resuelve. Solo `GET /oportunidades` y `GET /oportunidades/:id` las calculan. Si el cliente necesita el valor real tras crear/editar un ítem, debe volver a pedir la oportunidad.
- Si `descuento` supera el límite del rol (§5) → `422 APROBACION_REQUERIDA`: el ítem no se crea. Crear primero sin descuento (o dentro del límite) y solicitar el mayor después sobre el ítem ya creado.
- Errores: `404 NO_ENCONTRADO` (oportunidad ajena o inexistente).

---

### PUT /oportunidades/:id/items/:item_id
> Actualiza un ítem existente. Todos los campos opcionales — solo se aplican los que vienen en el body.

**Roles:** los mismos que `POST` de arriba.

**Body:** mismos campos que `POST /oportunidades/:id/items` (`id_modelo`, `cantidad`, `precio_venta`, `descuento`, `cuota_financiadora`), todos opcionales.

**Respuesta 200:**
```json
{
  "data": {
    "id": 502,
    "id_modelo": 2,
    "modelo": { "id": 2, "codigo": "KinWin K15", "precio_base": "98000.00" },
    "cantidad": 4,
    "precio_venta": "92000.00",
    "descuento": "3.00",
    "cuota_financiadora": "937.50",
    "monto_item": "356976.00",
    "cuota_quantum": null,
    "cuota_total": null,
    "advertencias": ["El precio unitario fue editado manualmente y no se actualizó con el nuevo modelo"]
  }
}
```

**Notas (reglas_negocio.md §12.2, ahora a nivel de ítem):**
- Si cambia `id_modelo` y `precio_venta` del ítem no fue editado previamente (igual al `precio_base` del modelo anterior), se actualiza automáticamente con el nuevo `precio_base`.
- Si `precio_venta` fue editado manualmente, el backend NO lo sobreescribe y devuelve `advertencias: ["El precio unitario fue editado manualmente y no se actualizó con el nuevo modelo"]`.
- Si `descuento` supera el límite del rol (§5) → `422 APROBACION_REQUERIDA`: el resto de campos puede reintentarse sin `descuento` o dentro del límite.
- Recalcula `monto_item` (y, en cascada, el `monto_total` de la oportunidad).
- **`cuota_quantum` y `cuota_total` (§6.2) siempre vienen `null` en la respuesta de este endpoint**, por el mismo motivo que en `POST` de arriba: este endpoint no las resuelve, solo `GET /oportunidades` y `GET /oportunidades/:id` lo hacen.
- Errores: `404 NO_ENCONTRADO` (ítem u oportunidad ajenos o inexistentes).

---

### DELETE /oportunidades/:id/items/:item_id
> Elimina un ítem de la oportunidad.

**Roles:** los mismos que `POST`/`PUT` de arriba.

**Respuesta 204:** sin body.

**Errores:**
- `409 ULTIMO_ITEM_NO_ELIMINABLE` — la oportunidad debe tener al menos un ítem; para reemplazar el modelo del último ítem, usar `PUT` en vez de `DELETE` + `POST`.
- `404 NO_ENCONTRADO` (ítem u oportunidad ajenos o inexistentes).

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

Todos los endpoints de reportes requieren rol `admin`, `gerencia` o `jdv`. Los vendedores no tienen acceso a reportes en el MVP.

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

### GET /reportes/exportar-comercial
> Export en Excel de la gestión comercial completa: prospectos, pipeline e histórico de actividades. Para control interno de gerencia.

**Roles:** `admin`, `gerencia`, `jdv` — con visión total, sin filtrar por dueño del registro. El resto recibe `403`.

**Query params:**

| Param | Tipo | Req. | Descripción |
|---|---|---|---|
| `fecha_desde` | ISO 8601 date | no | Filtra por fecha de ingreso, inclusive. |
| `fecha_hasta` | ISO 8601 date | no | Filtra por fecha de ingreso, inclusive. |

**Diferencia deliberada con el resto de §18:** si no se envían fechas, este endpoint devuelve **todo el histórico**, no el mes calendario actual. Un export de control que por defecto recorta a un mes se lee como datos faltantes.

La fecha de ingreso es `oportunidades.created_at` para las filas con oportunidad y `empresas.created_at` para las filas de prospección. **Las actividades no se filtran por el rango**: una vez que la oportunidad entra en alcance, viene su histórico completo.

**Respuesta 200:** el archivo `.xlsx`, no el envelope JSON.

```
Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
Content-Disposition: attachment; filename="gestion-comercial-2026-09-08.xlsx"
```

> **Es el único endpoint del API que no devuelve `{data, meta, error}`.** El cuerpo es el archivo binario; envolverlo en JSON lo haría indescargable. Los errores (`401`, `403`) sí siguen el envelope estándar.

**Forma del archivo:** una sola hoja llamada `Gestión comercial`, 33 columnas, **una fila por actividad** repitiendo los datos del prospecto y de la oportunidad (tabla plana, pensada para filtrar y pivotar en Excel). Una oportunidad sin actividades ocupa una fila con las columnas de actividad en `-`; un prospecto sin oportunidad también.

**Ninguna celda queda vacía:** lo que el CRM no guarda se emite como `"-"`. En particular, la columna **Principal bloqueo** sale siempre `"-"` porque no existe ningún campo que la respalde — no se infiere de `notas` ni de `motivo_cierre`.

**Columnas, en orden:** RUC · Razón social · Fecha de ingreso · Responsable del prospecto · Origen del prospecto · Segmento · Estado de cartera · ID oportunidad · Vendedor de la oportunidad · Etapa / Estado actual · Modelos y unidades · Unidades totales · Monto total · Financiadora · Fecha de creación de la oportunidad · Fecha estimada de cierre · Fecha de facturación · Motivo de no cierre · Notas de la oportunidad · Siguiente acción · Principal bloqueo · Fecha de primer contacto · Fecha de última gestión · Días sin gestión · Tipo de actividad · Actividad · Tipo de acción · Estado de la actividad · Fecha de la actividad · Responsable de la actividad · Descripción · Comentarios de seguimiento · Fecha de registro.

**Mapeos que conviene conocer:** "Etapa / Estado actual" es el estado real de la oportunidad, con los **4 valores del enum** (`reglas_negocio.md` §4.1) traducidos a etiqueta de negocio — no existe la granularidad de "contacto / reunión / cotización" que se usa al hablar del embudo, ni existe un estado `perdido`. "Siguiente acción" es la próxima tarea pendiente. "Fecha de primer contacto" y "Fecha de última gestión" se derivan del `created_at` mínimo y máximo de las actividades.

---

## 19. Notificaciones

Notifica a un usuario cuando ocurre una acción relacionada con él pero no accionada por él mismo. También cubre recordatorios de tareas y eventos (job programado, sin actor humano).

**Tipo (`tipo`):** los 17 valores reales de `TipoNotificacion` (`NotificacionEnums.kt`, migración V22/V28/V34/V47):
- `oportunidad_cambio_estado`, `empresa_convertida`, `evento_creado`, `tarea_creada`, `tarea_colaborador_agregado`, `empresa_asignada`, `oportunidad_traspasada`, `tarea_recordatorio`, `evento_recordatorio` — el set original.
- `solicitud_creada`, `solicitud_aprobada`, `solicitud_denegada` — ciclo de vida de una Solicitud (§20).
- `meta_propuesta`, `meta_aprobada`, `meta_rechazada`, `meta_modificada` — ciclo de vida de una Meta de venta (§21).
- `simulacion_por_expirar` — aviso de purga de una simulación huérfana (§23).

**Entidad referenciada (`entidad_tipo`):** los 5 valores reales de `EntidadNotificacion`: `oportunidad` | `empresa` | `solicitud` | `meta_venta` | `simulacion` — nunca una tarea/evento suelto; para tareas/eventos se referencia su oportunidad si tiene una, si no su empresa.

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
  "entidad_tipo": "oportunidad_item",
  "entidad_id": 502,
  "dcto_solicitado": "5.00",
  "motivo": "Cliente frecuente, tercera compra del año"
}
```

**Nota (V42 — multi-modelo):** para `tipo: "descuento"`, `entidad_tipo` **debe** ser `oportunidad_item` — el valor `oportunidad` ya no se acepta para este tipo, porque el descuento vive en el ítem, no en la oportunidad. `entidad_id` es el `id` del **ítem** (`OportunidadItemDto.id`, el mismo que devuelve `POST/PUT /oportunidades/:id/items`), no el de la oportunidad. Un `entidad_tipo` distinto de `oportunidad_item` en una solicitud de tipo `descuento` responde `400 VALIDACION` (`field: "entidad_tipo"`).

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

## 22. Tipo de cambio

Tipo de cambio PEN/USD publicado por SUNAT, para el indicador permanente del layout global del CRM. No tiene relación con visibilidad de cartera: es un dato único, igual para todos los roles autenticados. Se actualiza una vez al día vía un job programado (14:30 UTC = 09:30 Lima) que consulta SUNAT y guarda la fila del día; si SUNAT no responde, el job no escribe nada y el endpoint sigue devolviendo el último valor guardado (nunca falla por eso). Detalle en `reglas_simulaciones.md §12`.

### GET /tipo-cambio
> Tipo de cambio vigente (la fila de fecha más reciente guardada).

**Roles:** todos

**Request:** sin body ni query params.

**Respuesta 200 (con dato guardado):**
```json
{
  "data": {
    "fecha": "2026-09-01",
    "compra": 3.750,
    "venta": 3.756
  },
  "meta": null,
  "error": null
}
```

**Respuesta 200 (sin ningún dato guardado todavía):**
```json
{
  "data": null,
  "meta": null,
  "error": null
}
```

**Notas:**
- `data: null` con status 200 (no 404) es una respuesta válida y esperada: ocurre mientras el job diario no haya poblado ninguna fila todavía, por ejemplo recién después de un deploy, antes de las 09:30 Lima del día siguiente. La ausencia del dato no es un recurso inexistente.
- No existe endpoint de escritura: el valor lo escribe únicamente el job diario (`ActualizacionTipoCambioJob`), nunca un request de cliente.

---

## 23. Simulaciones

Módulo de financiamiento propio de Quantum (`reglas_simulaciones.md`): motor de cálculo (leasing / crédito directo), persistencia de simulaciones, bitácora de versiones y purga automática de las que quedan huérfanas. Distinto de la Calculadora Financiera (§24): aquí todo se persiste.

**Permisos del módulo (§10 de `reglas_simulaciones.md`), centralizados en `SimulacionPermisos` y no repartidos por endpoint:**

| Rol | Acceso |
|---|---|
| `admin`, `gerencia`, `analista` | Total: listan, ven y editan cualquier simulación |
| `vendedor` | Sin acceso al listado (`GET /simulaciones`); sí puede crear/ver/editar la simulación de un ítem de **su propia** oportunidad, o una no enlazada que él mismo creó |
| `jdv`, `otro` | Sin acceso a ninguna función del módulo |

`analista` es de solo lectura en `oportunidades` pero tiene escritura completa aquí — es el rol dueño de este módulo. `jdv` es supervisor en `oportunidades` pero no tiene acceso aquí. Un recurso fuera del alcance del rol (una simulación ajena para `vendedor`, o cualquiera para `jdv`/`otro`) responde **`404 NO_ENCONTRADO`**, nunca `403` (CLAUDE.md regla 14) — la única excepción es el listado completo del módulo (`GET /simulaciones`) y el acceso a "alguna función", que sí son `403 PERMISO_INSUFICIENTE` porque no es una pregunta sobre si el recurso existe, sino sobre si el rol tiene la función.

**Puntos que no se deducen de las firmas de los endpoints:**

- **`cuota_final` es solo lectura.** El backend siempre la recalcula server-side (motor de cálculo) al crear, actualizar, restaurar o bifurcar; si viene en el body de cualquier request, se ignora (`reglas_simulaciones.md` §4).
- **`modo` es inmutable.** Un `PATCH /simulaciones/:id` que intente cambiarlo responde **`409 MODO_INMUTABLE`**. La única vía autorizada para cambiar de modo es `POST /simulaciones/:id/bifurcar` ("Guardar como Nueva Simulación"), que sí lo acepta y lo aplica sobre una fila **nueva** — la fila origen conserva su modo intacto.
- **El historial es una ventana, no la bitácora completa.** `GET /simulaciones/:id/historial` devuelve solo los eventos con snapshot (`creada`/`editada`/`restaurada`) de los **últimos 7 días, hasta 15**, más recientes primero (§7.2 de `reglas_simulaciones.md`). El log completo permanece en base, solo la lectura está acotada. `diff` puede venir **vacío** legítimamente: ocurre en el primer evento de la simulación, y también cuando un `PATCH` no tocó ninguno de los 10 parámetros del snapshot (por ejemplo, un `PATCH` que solo reenlaza `id_oportunidad_item`, o un `PATCH` vacío). Un diff vacío es información honesta, no un error.
- **`POST /simulaciones/:id/restaurar` recalcula `cuota_final`.** Nunca copia la que estuviera guardada en esa versión del historial — si hubo una corrección de fórmula entre medio, la cuota vieja podría estar mal.
- **`eliminacion_prevista_el` solo trae valor si la simulación no está enlazada a un ítem.** Es `created_at + 30 días` mientras `id_oportunidad_item` sea `null` (§5); en cuanto se enlaza (al crearla o en un `PATCH` posterior), pasa a `null` de forma permanente — enlazarla la salva definitivamente de la purga.
- **`id_oportunidad` en la respuesta es derivado del ítem enlazado.** No es una columna de `simulaciones`; sale de resolver `id_oportunidad_item → oportunidad` en el servidor para que el cliente pueda agrupar por oportunidad sin hacer esa resolución por su cuenta (§8.2). Es `null` si la simulación no está enlazada.

---

### POST /simulaciones
> Crea una simulación de financiamiento (persistida).

**Roles:** `admin` `gerencia` `analista` `vendedor` (`vendedor` solo puede enlazarla a un ítem de una oportunidad donde él es el vendedor asignado). `jdv` y `otro` no tienen ninguna función en el módulo: `403 PERMISO_INSUFICIENTE`.

**Request:**
```json
{
  "modo": "leasing",
  "nombre": null,
  "id_oportunidad_item": 502,
  "id_modelo": 1,
  "precio_venta": 92000.00,
  "descuento": 3.00,
  "cuota_inicial": 45000.00,
  "plazo_meses": 48,
  "tea": 14.00,
  "valor_residual": 25000.00,
  "dias_trabajados": 22,
  "comision_estructuracion": 1180.00
}
```
Solo `modo`, `precio_venta`, `cuota_inicial`, `plazo_meses` y `tea` son obligatorios. `descuento`, `valor_residual`, `dias_trabajados` y `comision_estructuracion` ausentes toman los defaults de §6.1 de `reglas_simulaciones.md`. `id_oportunidad_item` y `id_modelo` son opcionales: sin ítem, la simulación nace huérfana (ver notas del módulo arriba).

**Respuesta 201:**
```json
{
  "data": {
    "id": 87,
    "nombre": "Transportes Lima SAC · KinWin K12 · Leasing · #1",
    "nombre_es_manual": false,
    "modo": "leasing",
    "id_oportunidad_item": 502,
    "id_oportunidad": 101,
    "id_modelo": 1,
    "modelo": { "id": 1, "codigo": "KinWin K12" },
    "id_simulacion_origen": null,
    "precio_venta": "92000.00",
    "descuento": "3.00",
    "cuota_inicial": "45000.00",
    "plazo_meses": 48,
    "tea": "14.00",
    "valor_residual": "25000.00",
    "dias_trabajados": 22,
    "comision_estructuracion": "1180.00",
    "cuota_final": "1234.56",
    "es_principal": true,
    "created_at": "2026-09-07T10:00:00Z",
    "updated_at": "2026-09-07T10:00:00Z",
    "eliminacion_prevista_el": null
  },
  "meta": null,
  "error": null
}
```

**Notas:**
- Enlazarla a un ítem la convierte automáticamente en la simulación **principal** de ese ítem (desmarca la que lo era). Sin ítem, `es_principal` es siempre `false` (CHECK `chk_simulacion_principal_requiere_item`).
- `nombre_es_manual` es `false` cuando `nombre` sale autogenerado (§8.1 de `reglas_simulaciones.md`); el autogenerado nunca se persiste.
- Validaciones de §13 (`cuota_inicial < PV_efectivo`, `valor_residual < Principal`) responden `400 VALIDACION` si no se cumplen — a diferencia del cálculo efímero de §6.1 en `GET /oportunidades` (§10 de este contrato), que degrada a `null` en vez de fallar.
- Se registra el evento `creada` en la bitácora.

---

### GET /simulaciones
> Lista simulaciones del módulo, paginado estándar (§4).

**Roles:** SOLO `admin` `gerencia` `analista`. **`403 PERMISO_INSUFICIENTE` para `vendedor`, `jdv` y `otro`** — el `vendedor` llega a sus simulaciones por el simulador de su propia oportunidad (`GET /simulaciones/:id` y afines) y por la Calculadora Financiera (§24), nunca por este listado completo.

**Request — query params:**

| Param | Tipo | Descripción |
|---|---|---|
| `id_oportunidad_item` | long | Filtra por ítem enlazado |
| `id_modelo` | long | Filtra por modelo |
| `modo` | enum | `leasing` \| `credito_directo` |
| `page`, `per_page`, `sort`, `dir` | — | Paginación estándar (§4). `sort` acepta `created_at` (default), `id`, `cuota_final`, `updated_at` |

**Respuesta 200:**
```json
{
  "data": [
    {
      "id": 87,
      "nombre": "Transportes Lima SAC · KinWin K12 · Leasing · #1",
      "nombre_es_manual": false,
      "modo": "leasing",
      "id_oportunidad_item": 502,
      "id_oportunidad": 101,
      "id_modelo": 1,
      "modelo": { "id": 1, "codigo": "KinWin K12" },
      "id_simulacion_origen": null,
      "precio_venta": "92000.00",
      "descuento": "3.00",
      "cuota_inicial": "45000.00",
      "plazo_meses": 48,
      "tea": "14.00",
      "valor_residual": "25000.00",
      "dias_trabajados": 22,
      "comision_estructuracion": "1180.00",
      "cuota_final": "1234.56",
      "es_principal": true,
      "created_at": "2026-09-07T10:00:00Z",
      "updated_at": "2026-09-07T10:00:00Z",
      "eliminacion_prevista_el": null
    }
  ],
  "meta": { "page": 1, "per_page": 20, "total": 3, "total_pages": 1 },
  "error": null
}
```

**Notas:**
- Un `modo` fuera del enum responde `400 VALIDACION` (`field: "modo"`).

---

### GET /simulaciones/:id
> Detalle de una simulación.

**Roles:** `admin` `gerencia` `analista` alcanzan cualquiera; `vendedor` alcanza la que está enlazada a un ítem de su propia oportunidad, o una no enlazada que él mismo creó; `jdv`/`otro` no tienen acceso. Ajena o inexistente → **`404 NO_ENCONTRADO`**.

**Request:** sin body ni query params.

**Respuesta 200:** el mismo objeto de `POST /simulaciones` (ver arriba).

---

### GET /simulaciones/:id/cronograma
> Cronograma completo de amortización, recalculado al vuelo — nunca se persiste (§4 de `reglas_simulaciones.md`).

**Roles:** los mismos que `GET /simulaciones/:id`.

**Request:** sin body ni query params.

**Respuesta 200:**
```json
{
  "data": {
    "cuota_final": "1234.56",
    "cuota_financiera": "1046.24",
    "valor_venta": "77966.10",
    "igv": "14033.90",
    "principal": "39830.68",
    "tasa_nominal_mensual": "1.09489",
    "filas": [
      {
        "mes": 0,
        "saldo_inicial": "39830.68",
        "amortizacion": "0.00",
        "interes": null,
        "igv": null,
        "saldo_final": "39830.68",
        "cuota": null,
        "cuota_con_igv": null
      },
      {
        "mes": 1,
        "saldo_inicial": "39830.68",
        "amortizacion": "823.10",
        "interes": "436.14",
        "igv": null,
        "saldo_final": "39007.58",
        "cuota": "1046.24",
        "cuota_con_igv": null
      }
    ]
  },
  "meta": null,
  "error": null
}
```

**Notas:**
- `tasa_nominal_mensual` nunca se redondea (§3.1 de `reglas_simulaciones.md`).
- `interes`, `igv`, `cuota` y `cuota_con_igv` van `null` en la fila del mes 0 (la de la cuota inicial); `igv` va `null` en todas las filas cuando `modo = "leasing"`, que no desglosa IGV (§3.3).

---

### PATCH /simulaciones/:id
> Actualiza parcialmente los parámetros de una simulación. Solo se tocan los campos que vienen en el body.

**Roles:** los mismos que `GET /simulaciones/:id`.

**Request:** cualquier subconjunto de los campos de `POST /simulaciones` (`modo`, `nombre`, `id_oportunidad_item`, `id_modelo`, `precio_venta`, `descuento`, `cuota_inicial`, `plazo_meses`, `tea`, `valor_residual`, `dias_trabajados`, `comision_estructuracion`). Ejemplo:
```json
{
  "precio_venta": 95000.00,
  "descuento": 5.00
}
```

**Respuesta 200:** la simulación actualizada, mismo shape que `POST /simulaciones`. `cuota_final` sale siempre recalculada, incluso con un body vacío.

**Errores:**
- **`409 MODO_INMUTABLE`** — el body trae `modo` distinto del actual. Usa `POST /simulaciones/:id/bifurcar` para cambiar de modo.
- `400 VALIDACION` — el snapshot resultante no cumple §13 (`cuota_inicial >= PV_efectivo`, o `valor_residual >= Principal`).
- `404 NO_ENCONTRADO` — ajena o inexistente.

**Notas:**
- Con todos los campos nullable, "ausente" y "`null` explícito" son indistinguibles: este `PATCH` permite enlazar a un ítem pero no desenlazar.
- Se registra el evento `editada` en la bitácora.

---

### DELETE /simulaciones/:id
> Elimina definitivamente una simulación.

**Roles:** los mismos que `GET /simulaciones/:id`.

**Respuesta 204:** sin body.

**Notas:**
- Se registra el evento `eliminada` con snapshot completo en `simulacion_log` **antes** de borrar la fila — el log sobrevive porque `id_simulacion` no tiene FK.
- Distinto de la purga automática de §5: esto es un borrado manual, a pedido del usuario, sobre cualquier simulación alcanzable (enlazada o no).

---

### GET /simulaciones/:id/historial
> Bitácora de versiones de la simulación: ventana de 7 días / 15 versiones, con el diff respecto al evento anterior (§7 de `reglas_simulaciones.md`).

**Roles:** los mismos que `GET /simulaciones/:id`.

**Request:** sin body ni query params.

**Respuesta 200:**
```json
{
  "data": [
    {
      "id_evento_log": 340,
      "tipo_evento": "editada",
      "created_at": "2026-09-06T16:00:00Z",
      "created_by": 5,
      "diff": [
        { "campo": "precio_venta", "valor_anterior": "92000.00", "valor_nuevo": "95000.00" },
        { "campo": "descuento", "valor_anterior": "3.00", "valor_nuevo": "5.00" }
      ]
    },
    {
      "id_evento_log": 338,
      "tipo_evento": "creada",
      "created_at": "2026-09-05T10:00:00Z",
      "created_by": 5,
      "diff": []
    }
  ],
  "meta": null,
  "error": null
}
```

**Notas:**
- Solo eventos con snapshot: `creada`, `editada`, `restaurada`. `marcada_principal`, `enlazada_a_item` y `eliminada` no aparecen aquí.
- `diff` vacío es legítimo: es el caso del primer evento de la simulación, y también el de una escritura que no tocó ninguno de los 10 parámetros del snapshot (un `PATCH` que solo reenlaza a otro ítem, o un `PATCH` vacío).
- `created_by` es `null` cuando el evento lo generó un job programado sin actor humano (la purga de §5).
- Más recientes primero.

---

### POST /simulaciones/:id/restaurar
> Restaura los parámetros de una versión dentro de la ventana de restauración (7 días / 15 versiones).

**Roles:** los mismos que `GET /simulaciones/:id`.

**Request:**
```json
{ "id_evento_log": 338 }
```

**Respuesta 200:** la simulación restaurada, mismo shape que `POST /simulaciones`.

**Errores:**
- `404 NO_ENCONTRADO` — `id_evento_log` no existe, no pertenece a esta simulación, no está en la ventana de 7 días/15 versiones, o no es de un tipo restaurable (`creada`/`editada`/`restaurada`). Mismo mensaje para los cuatro motivos: no se filtra cuál falló.
- `400 VALIDACION` — el snapshot restaurado ya no cumple §13 (puede pasar si hubo una corrección de fórmula entre medio).

**Notas:**
- **`cuota_final` se recalcula server-side, nunca se restaura la que estuviera guardada en esa versión del historial.**
- No restaura `modo` (es inmutable, y es la misma simulación), ni `es_principal` ni `id_oportunidad_item`: no son parámetros de cálculo.
- Registra **dos** eventos: `editada` (congela el estado justo antes de restaurar, para poder deshacer el deshacer) y `restaurada` (el estado nuevo).

---

### POST /simulaciones/:id/bifurcar
> "Guardar como Nueva Simulación": crea una fila nueva a partir de otra existente, sin mutar el origen. Es la única vía autorizada para cambiar de `modo`.

**Roles:** los mismos que `GET /simulaciones/:id` (aplican sobre la simulación origen).

**Request:** mismos campos que `PATCH /simulaciones/:id`, todos opcionales — lo que no venga se hereda del origen, **excepto `nombre`**, que nunca se hereda: si no viene, la bifurcada nace sin nombre manual y autogenera el suyo (dos simulaciones no pueden compartir el mismo título autogenerado). Ejemplo (cambiando de modo):
```json
{
  "modo": "credito_directo"
}
```

**Respuesta 201:** la nueva simulación, mismo shape que `POST /simulaciones`, con `id_simulacion_origen` apuntando a la original.

**Notas:**
- A diferencia de `PATCH`, aquí `modo` **sí** se aplica — nunca responde `409 MODO_INMUTABLE`.
- Hereda el enlace a ítem del origen si lo tenía, y en ese caso nace como principal de ese ítem (desmarca la que lo era).
- Se registra el evento `creada` en la bitácora de la fila nueva; el origen no se modifica ni recibe un evento propio por esta operación.

---

### PATCH /simulaciones/:id/principal
> Marca la simulación como la principal de su ítem enlazado.

**Roles:** los mismos que `GET /simulaciones/:id`.

**Request:** cuerpo vacío.

**Respuesta 200:** la simulación actualizada, con `es_principal: true`.

**Errores:**
- `400 VALIDACION` — la simulación no está enlazada a ningún ítem (no puede ser principal sin ítem, CHECK `chk_simulacion_principal_requiere_item`).
- `404 NO_ENCONTRADO` — ajena o inexistente.

**Notas:**
- Desmarca automáticamente la que era principal de ese mismo ítem (`uq_simulacion_principal` garantiza una sola).
- Ya principal es un no-op exitoso: no reescribe la fila ni registra un evento duplicado.
- Se registra el evento `marcada_principal` en la bitácora.

---

## 24. Calculadora Financiera

Estimación rápida durante la prospección (`reglas_simulaciones.md` §9), con el **mismo motor y las mismas validaciones §13** que el módulo de Simulaciones, pero **cero persistencia**: no escribe en `simulaciones` ni en `simulacion_log`, ni siquiera auditoría. Es deliberado — no una omisión — para que el servicio pueda ofrecerse sin repositorios en absoluto.

**"Enlazar a Oportunidad" no es un endpoint de esta sección.** Convertir un cálculo de la Calculadora en una simulación real es, literalmente, `POST /simulaciones` (§23) con los mismos parámetros más el `id_oportunidad_item` elegido — recién ahí se crea la fila y su evento `creada`.

### POST /calculadora
> Calcula un cronograma de financiamiento sin persistir nada.

**Roles:** `admin` `gerencia` `analista` `vendedor` — mismo reparto que `POST /simulaciones` (§10 de `reglas_simulaciones.md`). `jdv` y `otro`: `403 PERMISO_INSUFICIENTE`.

**Request:**
```json
{
  "modo": "leasing",
  "id_empresa": 3,
  "id_modelo": 1,
  "precio_venta": 92000.00,
  "descuento": 3.00,
  "cuota_inicial": 45000.00,
  "plazo_meses": 48,
  "tea": 14.00,
  "valor_residual": 25000.00,
  "dias_trabajados": 22,
  "comision_estructuracion": 1180.00
}
```
`id_empresa` e `id_modelo` son opcionales y puramente de presentación (no participan del cálculo). No se declara `id_oportunidad_item`: antes de "Enlazar a Oportunidad" no existe ítem.

**Respuesta 200:**
```json
{
  "data": {
    "empresa": { "id": 3, "razon_social": "Transp. Negociaciones Sta. Anita S.A." },
    "modelo": { "id": 1, "codigo": "KinWin K12" },
    "cronograma": {
      "cuota_final": "1234.56",
      "cuota_financiera": "1046.24",
      "valor_venta": "77966.10",
      "igv": "14033.90",
      "principal": "39830.68",
      "tasa_nominal_mensual": "1.09489",
      "filas": [
        {
          "mes": 0,
          "saldo_inicial": "39830.68",
          "amortizacion": "0.00",
          "interes": null,
          "igv": null,
          "saldo_final": "39830.68",
          "cuota": null,
          "cuota_con_igv": null
        }
      ]
    }
  },
  "meta": null,
  "error": null
}
```

**Errores:**
- `400 VALIDACION` — §13 no se cumple (`cuota_inicial >= PV_efectivo`, o `valor_residual >= Principal`), o `modo`/`id_empresa`/`id_modelo` inválidos.
- `404 NO_ENCONTRADO` — `id_empresa` o `id_modelo` no existen.

**Notas:**
- **No persiste nada, ni siquiera un registro de auditoría.** No hay `id`, `created_at`, `es_principal` ni `nombre` en la respuesta: no existe fila que los tenga.
- `empresa` y `modelo` vienen `null` cuando el request no trajo sus ids.
- No devuelve `cuota_total` (cuota Quantum + cuota financiadora, §6.2): esa suma solo existe dentro de una oportunidad, donde hay un ítem del cual leer `cuota_financiadora`. Antes de enlazar no hay ítem.

---

## 25. Mantenimiento

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

## 26. Enums

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
| `entidad_solicitud_enum` | `Solicitud.entidad_tipo` | `oportunidad`, `empresa`, `oportunidad_item` (V42 — el valor que usa hoy `tipo: "descuento"`; `oportunidad` queda como valor legado del enum, no se acepta para ese tipo) |
| `estado_meta_enum` | `MetaVenta.estado` | `propuesta`, `aprobada`, `rechazada` |
| `tipo_notificacion_enum` | `Notificacion.tipo` | `oportunidad_cambio_estado`, `empresa_convertida`, `evento_creado`, `tarea_creada`, `tarea_colaborador_agregado`, `empresa_asignada`, `oportunidad_traspasada`, `tarea_recordatorio`, `evento_recordatorio`, `solicitud_creada`, `solicitud_aprobada`, `solicitud_denegada`, `meta_propuesta`, `meta_aprobada`, `meta_rechazada`, `meta_modificada`, `simulacion_por_expirar` |
| `entidad_notificacion_enum` | `Notificacion.entidad_tipo` | `oportunidad`, `empresa`, `solicitud`, `meta_venta`, `simulacion` |
| `modo_simulacion_enum` | `Simulacion.modo` | `leasing`, `credito_directo` |
| `tipo_evento_simulacion_enum` | `SimulacionLog.tipo_evento` | `creada`, `editada`, `restaurada`, `marcada_principal`, `enlazada_a_item`, `eliminada` |

**No expuestos por la API** (uso interno, dedup del job de recordatorios — no aparecen en ningún request/response): `origen_recordatorio_enum` (`tarea`, `evento`), `umbral_recordatorio_enum` (`proximo`, `vencido`).

---

## 27. Notas operativas — Drive

> Aclaraciones sobre el flujo de archivos de Drive (§8 Empresas, §10 Oportunidades, §25 Mantenimiento) que no se desprenden de la firma de los endpoints. El equipo de frontend las traía documentadas por separado, confirmadas de palabra con backend el 2026-07-31; quedan incorporadas aquí, en el contrato oficial, el 2026-08-17 tras verificarlas contra el código actual (`EmpresaDriveController.kt`, `OportunidadDriveController.kt`, `DriveMultipartUploader.kt`, `DriveProperties.kt`, `GlobalExceptionHandler.kt`).

- **Creación de carpeta al subir sobre `drive_folder_id: null`:** `POST /empresas/:id/archivos` y `POST /oportunidades/:id/archivos` llaman primero a `asegurarCarpetaDrive`, que crea la carpeta en ese momento si `drive_folder_id` es `null` y la persiste antes de subir el archivo — no devuelve 404. El 404 solo ocurre si la entidad no existe o es ajena al usuario (chequeo de visibilidad corre antes de tocar Drive, por diseño de IDOR). Consecuencia para el cliente: tras esa primera subida, el detalle de la entidad debe refrescarse, porque `drive_folder_id` ya dejó de ser `null` en el servidor.
- **Unidad del límite de tamaño:** el límite de archivo es `app.drive.max-file-size-bytes`, por defecto **`104_857_600` bytes exactos** (100 × 1024 × 1024 = MiB, no MB decimales — ver `DriveProperties.DEFAULT_MAX_FILE_SIZE_BYTES`). El límite se aplica sobre el stream ya desenmarcado del multipart (`StreamAcotado` envuelve `parte.inputStream`, después de que `commons-fileupload2` separa boundary/headers/CRLFs): el framing nunca cuenta contra el tope. Validar contra `file.size` en el cliente es exacto y no requiere reservar margen.
- **Errores sin envelope:** el deploy (Render/Railway, ver `DEVOPS-backend.md` §6.1) no tiene nginx propio — corre detrás del proxy de borde de la plataforma, que puede cortar una petición antes de que llegue a la API Spring. En ese caso la respuesta **no trae el envelope** `{ data, meta, error }` ni `error.code`. El cliente nunca debe leer `error.code` a ciegas: si el body no parsea como el envelope esperado, cae al mensaje genérico. Cuando la petición sí llega a la API, un 413 por archivo grande **siempre** trae el envelope con `code: "ARCHIVO_DEMASIADO_GRANDE"` (`GlobalExceptionHandler.handleUploadTooLarge`). Excepción razonada para el cliente: un 413 sin envelope se puede tratar igual que `ARCHIVO_DEMASIADO_GRANDE`, porque ese status solo puede significar eso.

---

## 28. Changelog del contrato

> Registro de cambios a este contrato desde que la app está en producción (2026-08-18 en adelante — no se reconstruyen entradas retroactivas para lo anterior a esa fecha). **Todo PR que modifique la forma de un request/response, un código de error, la semántica de un campo, o agregue/quite un endpoint documentado aquí, agrega una entrada a esta tabla en el mismo PR.** Sin entrada, el PR no se considera completo aunque el código y los tests pasen.

**Breaking vs non-breaking, para este contrato:**
- **Breaking** — requiere que el frontend actualice código antes o al mismo tiempo del deploy: quitar o renombrar un campo de un response, cambiar el tipo/formato de un campo existente, cambiar un código de error ya usado, cambiar el status HTTP de un caso ya documentado, quitar un endpoint, agregar un campo *requerido* a un request.
- **Non-breaking** — el frontend puede ignorarlo hasta que lo adopte: nuevo endpoint, nuevo campo *opcional* en un response, nuevo valor de enum aditivo en un campo que el cliente ya trata con un `default`/`else`, aclaración de comportamiento no observable en la firma (como las notas de §27).

| Fecha | Endpoint(s) | Tipo | Cambio | Acción para frontend |
|---|---|---|---|---|
| 2026-08-18 | — | — | Se crea este changelog. Sin entradas retroactivas. | Ninguna |
| 2026-08-18 | `GET /oportunidades`, `GET /oportunidades/:id`, `GET /empresas`, `GET /empresas/:id`, `PATCH /oportunidades/:id/estado`, `POST /oportunidades`, `PUT /oportunidades/:id`, `POST /empresas`, `PUT /empresas/:id`, `PATCH /empresas/:id/estado-cartera`, `PATCH /empresas/:id/vendedor`, `PATCH /empresas/:id/cartera-maestra`, `POST /oportunidades/:id/archivos`, `POST /oportunidades/:id/carpeta-drive`, `POST /empresas/:id/archivos`, `POST /empresas/:id/carpeta-drive`, `POST /solicitudes` | **Breaking** | `analista` y `otro` pasan a roles de apoyo sin cartera propia: en empresas y oportunidades (los recursos que lista esta fila) los listados y el detalle solo devuelven las entidades donde el usuario colabora vía tarea (`ids_colaboradores`), y toda escritura sobre esos dos recursos (incluida la subida de archivos/creación de carpeta en Drive) responde `403 PERMISO_INSUFICIENTE` con mensaje específico; `analista` deja de poder confirmar `facturado`; ninguno de los dos aplica descuentos por ninguna vía ni crea solicitudes de aprobación. Eventos y la vinculación de contactos a empresa no tienen guard de escritura propio y heredan la visibilidad por colaboración (detalle en `matriz_permisos.md §2.3/§2.5`). Ver `matriz_permisos.md` para el detalle completo por operación. | Ocultar en el cliente las acciones de escritura y de subida de Drive para estos roles, y no asumir que "lo que veo, lo puedo editar". El 403 trae un mensaje específico que se puede mostrar tal cual. Las solicitudes históricas que estos roles ya tenían siguen siendo visibles (no hay regresión de lectura ahí). |
| 2026-08-19 | `GET /solicitudes` | Non-breaking (fix de seguridad) | El filtro de visibilidad del listado no tenía ninguna rama para el rol `otro` y devolvía todas las solicitudes de la empresa sin restricción, incluidos montos de descuento y motivos de reasignación ajenos. Corregido: `otro` ahora solo ve las solicitudes que él mismo creó, igual que `analista`. | Ninguna — el comportamiento correcto ya era el documentado; ningún cliente debía depender de la fuga. |
| 2026-08-19 | `GET /metas-venta` | Non-breaking (fix de seguridad) | El filtro de visibilidad del listado tenía la misma falla que `GET /solicitudes`: ninguna rama para el rol `otro`, que veía todas las metas del equipo sin restricción. Corregido: `otro` ahora solo ve su propia meta, igual que `analista`. | Ninguna — el comportamiento correcto ya era el documentado; ningún cliente debía depender de la fuga. |
| 2026-08-20 | `GET /contactos`, `GET /contactos/:id`, `PUT /contactos/:id` | **Breaking** | Cierre de la última fuga de visibilidad del cambio de roles de apoyo del 2026-08-18: el módulo `contactos` no se había tocado y `analista`/`otro` listaban, abrían y **editaban** nombre, teléfono y correo de todos los contactos del CRM. Ahora: (1) `GET /contactos` y `GET /contactos/:id` solo devuelven, para esos roles, los contactos vinculados a empresas donde colaboran vía tarea — el contacto sin empresa (huérfano) queda fuera; el que queda fuera de alcance en el detalle responde `404 NO_ENCONTRADO`. (2) Se agrega el query param `contexto` (`listado` \| `vincular`) a ambos GET: `vincular` levanta el filtro para que el buscador de "vincular contacto existente" siga alcanzando todo el CRM, pero recorta la respuesta a `id`/`nombres`/`apellidos` y hace que `q` busque solo por nombre, no por teléfono. Ausente ⇒ `listado`; valor desconocido ⇒ `400 VALIDACION`. (3) `PUT /contactos/:id` responde `403 PERMISO_INSUFICIENTE` para `analista`/`otro` sobre un contacto fuera de su alcance. El resto de roles no cambia en nada. Ver `matriz_permisos.md §1` y `§2.3`. | **Enviar `contexto=vincular` en el buscador de vincular contacto** — sin él ese buscador deja de encontrar contactos fuera del alcance del usuario de apoyo y el flujo se rompe para esos roles. La vista de listado no necesita cambios (el default ya es el correcto). Para `analista`/`otro` el cliente debe tolerar filas con `tlf_*`/`email_*` nulos y `empresas`/`oportunidades_count` vacíos en modo `vincular`, y un `403` con mensaje mostrable al editar. La mitigación de UI que ocultaba la sección Contactos para estos roles ya puede retirarse: el control ahora está en el backend. |
| 2026-08-20 | `POST /empresas/:id/contactos`, `PUT /empresas/:id/contactos/:contacto_id`, `DELETE /empresas/:id/contactos/:contacto_id` | **Breaking** | Hallazgo de la revisión final del cambio de visibilidad de contactos: la vinculación de contactos a empresas no tenía guard de escritura para roles de apoyo (a diferencia de la vinculación a oportunidades, que sí lo tenía desde el 2026-08-18). Combinado con el nuevo `contexto=vincular` de `GET /contactos` (que busca en todo el CRM por diseño), esto abría un camino para que `analista`/`otro` vincularan cualquier contacto a una empresa donde colaboran y luego lo vieran completo. Corregido: las tres operaciones de vinculación ahora responden `403 PERMISO_INSUFICIENTE` para `analista`/`otro`, sin excepción — mismo criterio que oportunidades. | Ocultar las acciones de vincular/editar vínculo/desvincular contacto para `analista`/`otro` en el cliente; el 403 trae mensaje mostrable. |
| 2026-09-01 | `GET /tipo-cambio` | Non-breaking | Nuevo endpoint: expone el tipo de cambio PEN/USD vigente. El dato sale de SUNAT, consultado por un job diario (09:30 Lima) que guarda una fila por fecha; si SUNAT no responde ese día, el endpoint sigue devolviendo el último valor guardado (fallback silencioso, sin error). Sin restricción de rol — visible para cualquier usuario autenticado. | Consumirlo para el indicador permanente del layout global. Tolerar `data: null` (HTTP 200) mientras el job no haya poblado la primera fila todavía — por ejemplo justo después del deploy, antes de las 09:30 Lima del día siguiente. |
| 2026-09-03 | `GET /oportunidades`, `GET /oportunidades/:id`, `POST /oportunidades`, `PUT /oportunidades/:id`, `POST /oportunidades/:id/items`, `PUT /oportunidades/:id/items/:item_id`, `DELETE /oportunidades/:id/items/:item_id`, `POST /solicitudes` | **Breaking** | Rediseño de oportunidades multi-modelo (V42, `oportunidad_items`): una oportunidad ahora puede vender varios modelos a la vez. (1) `GET/POST/PUT /oportunidades` y `GET /oportunidades/:id`: el response ya NO tiene `id_modelo`, `modelo`, `cantidad`, `precio_unitario`, `dcto` en la raíz — se movieron a `items: [OportunidadItemDto]`, uno por modelo vendido. `monto_total` se queda en la raíz, sigue siendo calculado, ahora como la suma de `monto_item` de todos los ítems. (2) `POST /oportunidades` sigue aceptando `id_modelo`, `cantidad`, `descuento` (renombrado de `dcto`) planos en el body — crea internamente el primer ítem — para no romper el flujo de alta de un solo modelo. (3) `PUT /oportunidades/:id` **ya no acepta** `id_modelo`, `cantidad`, `precio_unitario`/`precio_venta`, `dcto`/`descuento` ni `monto_total`; esos campos se ignoran en silencio si vienen (el DTO no los declara) y el error `400 MONTO_NO_EDITABLE` desaparece del contrato (el guard ya no existe: no hay campo que rechazar). Solo quedan `garantia`, `finc_paralelo`, `ficha_venta`, `notas`, `fecha_cierre_estimado`. (4) Endpoints nuevos para editar ítems: `POST /oportunidades/:id/items` (crea uno más), `PUT /oportunidades/:id/items/:item_id` (edita uno existente — misma regla de "no pisar precio editado a mano" de `reglas_negocio.md §12.2`, ahora aplicada al ítem), `DELETE /oportunidades/:id/items/:item_id` (elimina uno — `409 ULTIMO_ITEM_NO_ELIMINABLE` si es el único ítem que le queda a la oportunidad; una oportunidad no puede quedarse sin ítems). (5) `GET /oportunidades?sort=precio_unitario` deja de ser un valor válido de `sort` — un precio unitario no significa nada con varios modelos por oportunidad; `sort=cantidad` y `sort=monto_total` se mantienen, con el mismo resultado observable de antes. (6) `POST /solicitudes` con `tipo: "descuento"`: `entidad_tipo` ahora debe ser `oportunidad_item` (no `oportunidad`) y `entidad_id` es el `id` del ítem, no de la oportunidad. Roles y visibilidad no cambian: los ítems heredan exactamente el mismo reparto de permisos que hoy tiene editar la oportunidad (ver `matriz_permisos.md §2.4`). | **Dejar de leer `id_modelo`/`modelo`/`cantidad`/`precio_unitario`/`dcto` de la raíz de la oportunidad** y leerlos de `items[]`; para una oportunidad de un solo modelo, `items` sigue teniendo un elemento. **Empezar a usar `POST/PUT/DELETE /oportunidades/:id/items` para agregar, editar o quitar modelos** — `PUT /oportunidades/:id` ya no sirve para eso. Actualizar el formulario de edición de oportunidad para no enviar los campos viejos (se ignoran, no rompen, pero ya no hacen nada). Quitar `precio_unitario` de cualquier selector de orden del listado. Para el flujo de solicitar descuento, enviar `entidad_tipo: "oportunidad_item"` con el `id` del ítem, no de la oportunidad. |
| 2026-09-04 | `GET /reportes/ventas`, `GET /reportes/pipeline`, `GET /reportes/equipo`, `GET /reportes/descuentos`, `GET /oportunidades` | Non-breaking | La forma del contrato no cambia — ningún campo se agrega, quita ni renombra en ningún DTO. Cambia la **fuente de datos**: estos reportes y el listado de oportunidades dejaron de leer las columnas planas `cantidad`/`precio_unitario`/`dcto`/`monto_total`/`id_modelo` de `oportunidades` (retiradas por V46) y ahora leen `oportunidad_items` directamente. Con los datos de hoy (ninguna oportunidad tiene más de un ítem en producción) los números no cambian. El día que una oportunidad tenga varios ítems, `porModelo` de `GET /reportes/ventas` y los reportes con descuento empezarán a reflejar cada ítem individualmente (por ejemplo, una oportunidad de 2 modelos aparecerá en dos entradas de `porModelo` en vez de una) — es el comportamiento correcto, no una regresión. | Ninguna acción requerida. Solo estar al tanto de que, a futuro, los números granulares de `porModelo`/descuentos pueden reflejar ítems en vez de oportunidades cuando haya oportunidades multi-modelo. |
| 2026-09-07 | `POST /simulaciones`, `GET /simulaciones`, `GET /simulaciones/:id`, `GET /simulaciones/:id/cronograma`, `PATCH /simulaciones/:id`, `DELETE /simulaciones/:id`, `GET /simulaciones/:id/historial`, `POST /simulaciones/:id/restaurar`, `POST /simulaciones/:id/bifurcar`, `PATCH /simulaciones/:id/principal`, `POST /calculadora`, `GET /oportunidades`, `GET /oportunidades/:id` | Non-breaking | Cierre del módulo de financiamiento propio de Quantum (`reglas_simulaciones.md`). (1) Se documentan los 11 endpoints nuevos: los 10 de `/simulaciones` (§23) — CRUD, cronograma, historial con diff, restaurar, bifurcar ("Guardar como Nueva Simulación") y marcar principal — y `POST /calculadora` (§24), la Calculadora Financiera de estimación rápida sin persistencia. Ya estaban implementados (Planes D y E); esta entrada salda la deuda de contrato. (2) `GET /oportunidades` y `GET /oportunidades/:id` ganan 5 campos opcionales (`reglas_simulaciones.md` §6.2): por ítem, `cuota_quantum` y `cuota_total`; a nivel de oportunidad, `cuota_quantum_total`, `cuota_total` y `cuota_diaria_total`. Los tres campos de nivel oportunidad son `null` conjuntamente si algún ítem no tiene una cuota calculable — no es un error, es "todavía no se puede mostrar la cuota total". (3) Dos valores nuevos, aditivos, en enums existentes: `tipo_notificacion_enum` gana `simulacion_por_expirar` y `entidad_notificacion_enum` gana `simulacion` (aviso 3 días antes de que una simulación huérfana se purgue a los 30 días). | Sin acción requerida salvo adoptar los campos y endpoints nuevos cuando el frontend lo necesite. Los campos de cuota son opcionales y pueden venir `null`; los dos valores de enum son aditivos y se ignoran con un `default`/`else` como cualquier otro. |
| 2026-09-08 | `GET /actividades`, `GET /actividades/{tipo}/{id}/comentarios`, `POST /actividades/{tipo}/{id}/comentarios`, `GET /actividades/{tipo}/{id}/auditoria`, `GET /oportunidades/:id/eventos`, `GET /empresas/:id/eventos`, `PUT /eventos/:id` | Non-breaking | Nueva vista de supervisión "historial de actividades" (§29). (1) Cuatro endpoints nuevos bajo `/actividades`: el historial unificado de tareas + eventos de un empleado (`id_empleado` obligatorio; filtros por rango de `created_at`, tipo, empresa y oportunidad), los comentarios de seguimiento de una actividad (listar y crear, append-only en tabla propia — nunca pisan `descripcion`) y la auditoría de ediciones (una fila por campo cambiado, con valor anterior y nuevo). (2) **Ningún permiso cambia:** `admin`, `gerencia` y `jdv` ya veían todas las tareas y eventos; lo nuevo es poder filtrar esa visión por empleado. Un rol no supervisor solo puede pedir su propio historial (`403 PERMISO_INSUFICIENTE` si pide el de otro). (3) `EventoDto` gana dos campos opcionales, `created_by` y `created_at`, que aparecen en todos los endpoints que ya devolvían eventos — aditivos, ningún campo se quita ni se renombra. Son necesarios porque un evento no tiene asignado y se atribuye a quien lo registró. Ver `matriz_permisos.md §1`. | Construir la vista nueva del side bar consumiendo `GET /actividades`. **Tratar `fecha_hora` y `fecha_dia` como dos campos distintos y no unificarlos**: `fecha_dia` es un día del calendario (columna `DATE`) y darle hora lo desplaza. Los dos campos nuevos de `EventoDto` se pueden ignorar hasta que se necesiten. |
| 2026-09-08 | `GET /reportes/exportar-comercial` | Non-breaking | Endpoint nuevo: export en Excel (`.xlsx`) de la gestión comercial para control interno de gerencia — prospectos, pipeline e histórico completo de actividades en una sola hoja de 33 columnas, una fila por actividad. Roles `admin`, `gerencia`, `jdv` con visión total (el mismo reparto que los otros 6 reportes; ver `matriz_permisos.md §2.10`). **Es el único endpoint del contrato que no devuelve el envelope `{data, meta, error}`:** el cuerpo es el archivo binario, con `Content-Disposition: attachment`. Dos comportamientos que se apartan del resto de §18, a propósito: (1) sin `fecha_desde`/`fecha_hasta` devuelve todo el histórico, no el mes calendario actual; (2) el rango filtra la fecha de ingreso del prospecto/oportunidad, no las actividades, que vienen completas. Ninguna celda va vacía: lo que el CRM no guarda se emite como `"-"`, incluida la columna "Principal bloqueo", que no tiene campo que la respalde y sale siempre así. En la misma entrada se corrige una deriva de §18, que seguía diciendo `gerente` en vez de `gerencia` (el rol se renombró en V25). | Añadir el botón de descarga donde corresponda para `admin`/`gerencia`/`jdv`. **Tratar la respuesta como binario, no como JSON** — es la excepción al envelope. Si se ofrecen filtros de fecha en la UI, dejar claro que vacío significa "todo el histórico". |

## 29. Actividades (historial de supervisión)

> Vista unificada de tareas y eventos de un empleado. **No amplía ni reduce ninguna visibilidad existente:** `admin`, `gerencia` y `jdv` ya veían todas las tareas y todos los eventos (ver `matriz_permisos.md §1`). Lo que estos endpoints agregan es la forma de filtrar esa visión por empleado, leerla en un solo sitio, y dejar comentarios y rastro de ediciones.

### GET /actividades

Historial de actividades (tareas + eventos) de un empleado, ordenado por `created_at` descendente.

**Roles:** cualquier usuario autenticado para su propio historial. Para el de otro empleado: solo `admin`, `gerencia`, `jdv` — el resto recibe `403 PERMISO_INSUFICIENTE`.

**Query params:**

| Param | Tipo | Req. | Descripción |
|---|---|---|---|
| `id_empleado` | long | **sí** | De quién es el historial. Sin él, `400`. |
| `desde` | ISO-8601 | no | Filtra por `created_at` >= valor. |
| `hasta` | ISO-8601 | no | Filtra por `created_at` <= valor. |
| `tipo` | enum | no | `tarea` o `evento`. Ausente ⇒ ambos. Valor desconocido ⇒ `400 VALIDACION`. |
| `id_empresa` | long | no | Solo actividades de esa empresa. |
| `id_oportunidad` | long | no | Solo actividades de esa oportunidad. |
| `page`, `per_page` | int | no | Paginación estándar (§4). `per_page` máx. 100. |

**Notas sobre el rango de fechas:** `desde`/`hasta` filtran por **cuándo se registró** la actividad (`created_at`), no por cuándo está planificada. Es el único campo TIMESTAMP presente y no nulo en ambas entidades.

**Response `data[]` — `ActividadDto`:**

| Campo | Tipo | Descripción |
|---|---|---|
| `tipo` | string | `tarea` o `evento`. |
| `id` | long | Id dentro de su propia tabla. |
| `titulo` | string | `tipo_accion` de la tarea, o el nombre del evento. |
| `descripcion` | string? | |
| `estado` | string | `estado_accion` de la tarea, o `estado` del evento. |
| `fecha_hora` | Instant? | Columnas TIMESTAMP: `fecha_ejecucion` (tarea) o `fecha_ocurrencia` (evento). |
| `fecha_dia` | date? | Columna DATE: `fecha_estimada` del evento. **Siempre `null` en una tarea.** |
| `id_empresa` | long? | `null` en un evento que cuelga de una oportunidad. |
| `empresa` | object? | `{id, razon_social, distrito}`. `null` si `id_empresa` es null. |
| `id_oportunidad` | long? | |
| `id_empleado` | long? | Asignado de la tarea, o **creador** del evento (un evento no tiene asignado). |
| `empleado` | object? | `{id, nombres, apellidos}`. |
| `comentarios` | int | Cuántos comentarios de seguimiento tiene. |
| `created_at` | Instant | Eje de orden del historial. |

> **`fecha_hora` y `fecha_dia` son dos campos y no uno a propósito.** `fecha_estimada` de un evento es un día del calendario de Lima (columna `DATE`), no un instante; darle una hora la desplaza. No los unifiques en el cliente: muestra `fecha_dia` como fecha suelta y `fecha_hora` como fecha y hora.

### GET /actividades/{tipo}/{id}/comentarios

Comentarios de seguimiento de una actividad, del más antiguo al más reciente.

**Roles:** todos, con el filtro de visibilidad del recurso — una actividad fuera de alcance responde `404 NO_ENCONTRADO`.

`{tipo}` es `tarea` o `evento`; cualquier otro valor ⇒ `400 VALIDACION` con `field: "tipo"`.

**Response `data[]`:** `{id, tipo, id_actividad, texto, created_at, created_by, autor: {id, nombres, apellidos}?}`

### POST /actividades/{tipo}/{id}/comentarios

Agrega un comentario. **`201 Created`.**

**Body:** `{"texto": "..."}` — obligatorio, no vacío tras recortar espacios, máximo 5000 caracteres.

**Errores:** `400 VALIDACION` (texto vacío o desmedido, tipo desconocido), `404 NO_ENCONTRADO` (actividad fuera de alcance).

> Los comentarios son **append-only**: no existe editar ni borrar, y un comentario nuevo nunca sobrescribe la `descripcion` de la tarea o el evento.

### GET /actividades/{tipo}/{id}/auditoria

Cambios registrados sobre una actividad, del más reciente al más antiguo. Una fila **por campo modificado**.

**Roles:** todos, con el filtro de visibilidad del recurso (`404` si queda fuera).

**Response `data[]`:** `{id, campo, valor_anterior, valor_nuevo, changed_at, changed_by, autor: {id, nombres, apellidos}?}`

`campo` viene en snake_case, con el mismo nombre que expone este contrato: `descripcion`, `fecha_ejecucion`, `tipo_accion`, `id_contacto`, `id_asignado` (tareas); `descripcion`, `fecha_estimada`, `fecha_seguimiento` (eventos).

> Se auditan **todas** las ediciones, también las que hace el propio dueño de la actividad. Las transiciones de estado (`completada`, `cancelada`, `ocurrido`, `descartado`) **no** se auditan aquí: ya quedan reflejadas en el `estado` de la propia actividad.

---

## Apéndice — Endpoints no implementados en MVP

Los siguientes endpoints se reservan para fases posteriores y **no deben implementarse** en el MVP:

- `GET|POST|PUT /buses-entregados` — gestión de entrega de unidades
- `GET /reportes/comisiones` — cálculo de comisiones
- `POST /empresas/import` — importación masiva desde Excel
- `GET /reportes/proyeccion` — proyección de ingresos ponderada por etapa
- `PUT /empleados/:id/permisos` — gestión de permisos granulares por rol
