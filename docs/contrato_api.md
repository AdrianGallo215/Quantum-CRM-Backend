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

---

## 1. Convenciones generales

```
Base URL:      /api/v1
Content-Type:  application/json
Auth header:   Authorization: Bearer {jwt_token}
Fechas:        ISO 8601 — "2026-06-19T14:30:00Z" para timestamps, "2026-06-19" para fechas
Montos:        NUMERIC como string en JSON para evitar pérdida de precisión — "45000.00"
Enums:         snake_case — "evaluacion_calidda", "no_contactado"
IDs:           Long (número entero)
```

Todo endpoint salvo `/auth/login` y `/auth/refresh` requiere token JWT válido.

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

---

## 3. Códigos de error

| Código | HTTP | Descripción |
|---|---|---|
| `RUC_DUPLICADO` | 409 | El RUC ya existe en el sistema |
| `MOTIVO_CIERRE_REQUERIDO` | 400 | Se intentó cerrar una oportunidad sin motivo |
| `MODELO_SIN_APLICACIONES` | 400 | Se intentó crear un modelo sin aplicaciones |
| `FINANCIADORA_DEFAULT_INEXISTENTE` | 500 | No hay financiadora con `es_default = true` |
| `ESTADO_INVALIDO` | 400 | Transición de estado no permitida |
| `PERMISO_INSUFICIENTE` | 403 | El rol no tiene acceso a esta operación |
| `NO_ENCONTRADO` | 404 | El recurso no existe |
| `CONTACTO_VINCULADO` | 409 | No se puede eliminar un contacto vinculado a una empresa |
| `MONTO_NO_EDITABLE` | 400 | Se intentó enviar `monto_total` en el body |
| `VALIDACION` | 400 | Error genérico de validación de campos |

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

| Recurso | admin | gerente | jdv | vendedor | analista |
|---|---|---|---|---|---|
| Ver todas las empresas | ✓ | ✓ | ✓ | Solo asignadas | Solo asignadas |
| Ver todas las oportunidades | ✓ | ✓ | ✓ | Solo propias | Solo propias |
| Ver todas las tareas | ✓ | ✓ | ✓ | Solo propias | Solo propias |
| Reasignar empresa | ✓ | ✓ | ✓ | — | — |
| Traspasar oportunidad | ✓ | ✓ | ✓ | — | — |
| Validar paso a Facturado | ✓ | ✓ | — | — | ✓ |
| Crear empleado | ✓ | — | — | — | — |
| Modificar catálogo de eventos | ✓ | — | — | — | — |
| Modificar financiadoras | ✓ | ✓ | — | — | — |
| Modificar modelos | ✓ | ✓ | — | — | — |

`vendedor` filtra por `id_vendedor = usuario_actual` en empresas y por `id_vendedor = usuario_actual` en oportunidades. `analista` aplica el mismo filtro que `vendedor` en el MVP.

---

## 6. Auth

### POST /auth/login
> Autentica al usuario y devuelve un par de tokens.

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
    "access_token": "eyJ...",
    "refresh_token": "eyJ...",
    "expires_in": 3600,
    "empleado": {
      "id": 1,
      "nombres": "Aldo",
      "apellidos": "Martínez",
      "email": "aldo.martinez@quantum.pe",
      "rol": "jdv",
      "area": "Comercial",
      "puesto": "Jefe de Ventas"
    }
  }
}
```

**Notas:**
- `access_token` expira en 1 hora. `refresh_token` expira en 7 días.
- Responde `401` si las credenciales son inválidas, sin indicar si el error es en email o contraseña.

---

### POST /auth/refresh
> Renueva el access token usando el refresh token.

**Body:**
```json
{ "refresh_token": "eyJ..." }
```

**Respuesta 200:** misma estructura que `/auth/login` pero sin `empleado`.

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

**Respuesta 200:** un objeto `empleado` como el de arriba.

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
| `estado_cartera` | enum | Filtrar por estado de cartera |
| `id_vendedor` | long | Filtrar por vendedor (solo admin/gerente/jdv) |
| `segmento` | string | Filtrar por segmento |
| `distrito` | string | Filtrar por distrito |

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
      "contactos_count": 1
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

**Roles:** todos

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

**Respuesta 201:** el objeto empresa completo.

**Notas:**
- `segmentos` se inserta en `empresa_segmentos` de forma atómica.
- El backend valida el RUC antes de insertar. Si ya existe → `409 RUC_DUPLICADO`.
- `estado_cartera` siempre nace como `no_contactado`. No es aceptado como campo de entrada.

---

### PUT /empresas/:id
> Actualiza datos de una empresa. No actualiza `estado_cartera` ni `id_vendedor`.

**Roles:** todos (solo su empresa si es vendedor/analista)

**Body:** mismos campos que POST, todos opcionales. Si `segmentos` viene en el body, reemplaza completamente los segmentos actuales.

**Respuesta 200:** el objeto empresa completo actualizado.

---

### PATCH /empresas/:id/estado-cartera
> Cambia el estado de cartera manualmente. Solo acepta estados manuales.

**Roles:** todos (solo su empresa si es vendedor/analista)

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

**Roles:** `admin` `gerente` `jdv`

**Body:** `{ "id_vendedor": 2 }`

**Respuesta 200:** `{ "data": { "id_vendedor": 2 } }`

---

## 9. Contactos

### GET /contactos
> Busca contactos. Usado para vincular un contacto existente a una empresa.

**Roles:** todos

**Query params:** `q` (nombre o teléfono), `id_empresa` (contactos de una empresa específica)

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
      "empresas": [
        { "id": 3, "razon_social": "Transp. Negociaciones Sta. Anita S.A.", "cargo": "Gerente" }
      ]
    }
  ]
}
```

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

**Roles:** todos

**Body:** `nombres`, `apellidos`, `email_1`, `email_2`, `tlf_1`, `tlf_2`, `notas` — todos opcionales.

**Respuesta 200:** el contacto actualizado.

---

### DELETE /contactos/:id
> Elimina un contacto. Solo si no está vinculado a ninguna empresa.

**Roles:** `admin` `gerente` `jdv`

**Respuesta 204:** sin body.

**Notas:**
- Si está vinculado a alguna empresa → `409 CONTACTO_VINCULADO`.

---

### POST /empresas/:id/contactos
> Vincula un contacto existente a una empresa.

**Roles:** todos (solo su empresa si es vendedor/analista)

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

**Roles:** todos (solo su empresa si es vendedor/analista)

**Body:** `{ "cargo": "Socio", "toma_decision": false, "es_principal": false }`

**Respuesta 200:** la vinculación actualizada.

---

### DELETE /empresas/:id/contactos/:contacto_id
> Desvincula un contacto de una empresa. No elimina el contacto.

**Roles:** todos (solo su empresa si es vendedor/analista)

**Respuesta 204:** sin body.

---

## 10. Oportunidades

### GET /oportunidades
> Lista de oportunidades con filtros.

**Roles:** todos (con filtro automático por rol)

**Query params:**

| Param | Tipo | Descripción |
|---|---|---|
| `estado` | enum | Filtrar por etapa del pipeline |
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

**Roles:** todos (la empresa debe estar asignada al vendedor si es vendedor/analista)

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
  ]
}
```

**Respuesta 201:** el objeto oportunidad completo.

**Notas:**
- `monto_total` NO se acepta en el body. Si viene, se ignora y se calcula.
- `precio_unitario` se inicializa con `modelos.precio_base` del modelo seleccionado.
- `id_vendedor` se toma de `empresas.id_vendedor` en el momento de la creación.
- `id_financiadora` es opcional — si no viene, se usa la que tenga `es_default = true`.
- Se inserta el primer registro en `oportunidad_estados_log`.
- Se llama a `actualizarEstadoCartera` en la misma transacción.

---

### PUT /oportunidades/:id
> Actualiza campos negociables de la oportunidad. No cambia el estado.

**Roles:** todos (solo su oportunidad si es vendedor/analista)

**Body:** `id_modelo`, `cantidad`, `precio_unitario`, `dcto`, `garantia`, `finc_paralelo`, `ficha_venta`, `notas`, `fecha_cierre_estimado` — todos opcionales.

**Notas:**
- `monto_total` NO se acepta. Si viene → `400 MONTO_NO_EDITABLE`.
- `estado`, `id_empresa`, `id_vendedor` NO se aceptan en este endpoint.
- Si cambia `id_modelo` y `precio_unitario` no fue editado previamente (igual al `precio_base` del modelo anterior), se actualiza automáticamente con el nuevo `precio_base`.
- Si `precio_unitario` fue editado manualmente, el backend devuelve en la respuesta: `"advertencias": ["El precio unitario fue editado manualmente y no se actualizó con el nuevo modelo"]`.
- Recalcula y persiste `monto_total`.

**Respuesta 200:** la oportunidad actualizada.

---

### PATCH /oportunidades/:id/estado
> Cambia el estado de una oportunidad.

**Roles:** todos con restricción: el paso a `facturado` solo lo pueden confirmar `admin`, `gerente` y `analista`.

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

### PATCH /oportunidades/:id/vendedor
> Traspasa la oportunidad a otro vendedor (traspaso activo).

**Roles:** `admin` `gerente` `jdv`

**Body:** `{ "id_vendedor": 2 }`

**Respuesta 200:** `{ "data": { "id_vendedor": 2 } }`

**Notas:**
- Modifica `oportunidades.id_vendedor` directamente. No duplica la oportunidad.
- El vendedor anterior deja de ver la oportunidad en su pipeline.

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

**Roles:** todos (solo su oportunidad si es vendedor/analista)

**Body:** `{ "id_contacto": 5, "rol_en_oportunidad": "Contacto Principal" }`

**Respuesta 201:** la vinculación creada.

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
        "etapa_asociada": "documentos_legales"
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

**Roles:** todos (solo su oportunidad si es vendedor/analista)

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

**Roles:** todos (solo eventos de su oportunidad si es vendedor/analista)

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

**Roles:** todos (solo eventos de su oportunidad si es vendedor/analista)

**Body:** `{ "descripcion": "Evento ya no aplica" }` (opcional)

**Respuesta 200:** `{ "data": { "estado": "descartado" } }`

---

### PUT /eventos/:id
> Actualiza fechas o descripción de un evento pendiente.

**Roles:** todos (solo eventos de su oportunidad si es vendedor/analista)

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
| `id_asignado` | long | Por asignado (solo admin/gerente/jdv) |
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
      "tipo_accion": "reunion",
      "estado_accion": "pendiente",
      "descripcion": "Revisar minuta del contrato tripartito",
      "fecha_ejecucion": "2026-06-19T10:00:00Z",
      "created_at": "2026-06-15T09:00:00Z"
    }
  ]
}
```

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

---

### PATCH /tareas/:id/completada
> Marca una tarea como completada.

**Roles:** todos (solo tareas asignadas a sí mismo si es vendedor/analista)

**Body:** `{ "descripcion": null }` (descripción adicional opcional)

**Respuesta 200:** `{ "data": { "estado_accion": "completada" } }`

---

### PATCH /tareas/:id/cancelada
> Marca una tarea como cancelada.

**Roles:** todos (solo tareas asignadas a sí mismo si es vendedor/analista)

**Respuesta 200:** `{ "data": { "estado_accion": "cancelada" } }`

---

### PUT /tareas/:id
> Actualiza una tarea pendiente.

**Roles:** todos (solo tareas asignadas a sí mismo si es vendedor/analista)

**Body:** `tipo_accion`, `descripcion`, `fecha_ejecucion`, `id_contacto`, `id_asignado` — todos opcionales.

**Notas:** Solo se pueden editar tareas con `estado_accion = 'pendiente'`.

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
      "por_etapa": {
        "evaluacion_calidda": { "count": 3, "valor": "1980800.00" },
        "documentos_legales": { "count": 2, "valor": "1184702.00" },
        "facturado":          { "count": 1, "valor": "884800.00"  }
      }
    },
    "resumen_prospeccion": {
      "total": 4,
      "listas_para_convertir": 1,
      "requieren_atencion": 2
    }
  }
}
```

**Notas:**
- `tareas_pendientes` ordenadas por `fecha_ejecucion ASC` (vencidas primero, luego hoy, luego próximas).
- `eventos_por_seguir` ordenados por `fecha_seguimiento ASC`.
- `resumen_prospeccion.requieren_atencion` = empresas con `checkpoints = 0` y `dias_sin_actividad >= 15`.

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

## Apéndice — Endpoints no implementados en MVP

Los siguientes endpoints se reservan para fases posteriores y **no deben implementarse** en el MVP:

- `GET|POST|PUT /buses-entregados` — gestión de entrega de unidades
- `GET /reportes/comisiones` — cálculo de comisiones
- `POST /empresas/import` — importación masiva desde Excel
- `GET /reportes/proyeccion` — proyección de ingresos ponderada por etapa
- `PUT /empleados/:id/permisos` — gestión de permisos granulares por rol
