# Export Excel de gestión comercial — Encargo para el FrontEnd

> Documento dirigido al agente/equipo de FrontEnd. El backend de este endpoint está **terminado, con tests en verde** (rama `feature/export-excel-comercial`, plan-15). Extiende `contrato_api.md`: las convenciones generales (base `/api/v1`, auth por cookie httpOnly, envelope `{data, meta, error}` para JSON) aplican igual y no se repiten acá salvo donde este endpoint es la excepción.

**Fecha:** 2026-09-08 · **Estado del backend:** implementado, pendiente de mergear a `main`.
**Alcance:** un botón de descarga en dos pantallas. Es chico a propósito — no hay UI nueva que diseñar, ni Stitch, ni flujo de varios pasos.

---

## 0. Resumen ejecutivo

1. Nace `GET /reportes/exportar-comercial`: descarga un **único archivo `.xlsx`** con toda la gestión comercial — prospectos, pipeline e histórico completo de actividades — para control interno de gerencia.
2. Roles: `admin`, `gerencia`, `jdv`. Mismo reparto que los otros 6 reportes (`matriz_permisos.md §2.10`); nada nuevo que resolver en permisos si ya tenés el guard de `/reportes` hecho.
3. **Es el único endpoint del contrato que no devuelve el envelope JSON.** El cuerpo 200 es el archivo binario. Esto es lo único genuinamente distinto que hay que programar.
4. **Importante para dónde poner el botón:** el archivo es **uno solo, siempre completo** — no hay una versión "solo prospección" y otra "solo pipeline". Ver §2 antes de decidir la ubicación exacta.

---

## 1. Fuentes de verdad

| Documento | Para qué |
|---|---|
| `docs/contrato_api.md` §18, entrada `GET /reportes/exportar-comercial` | Contrato completo: params, roles, forma de la respuesta, las 33 columnas del archivo. |
| `docs/contrato_api.md` §28, fila `2026-09-08` (la de este endpoint) | Resumen del cambio y qué debe hacer el frontend. |
| `docs/matriz_permisos.md` §2.10 | Confirma que el reparto de roles es igual al resto de `/reportes`. |

---

## 2. El endpoint

```
GET /api/v1/reportes/exportar-comercial
GET /api/v1/reportes/exportar-comercial?fecha_desde=2026-01-01&fecha_hasta=2026-06-30
```

- **Roles:** `admin`, `gerencia`, `jdv`. Cualquier otro rol recibe `403`.
- **Query params, ambos opcionales:** `fecha_desde`, `fecha_hasta` (ISO 8601, `YYYY-MM-DD`). Filtran por la fecha de ingreso del prospecto/oportunidad — **no** acotan las actividades, que siempre vienen completas una vez que la fila entra en alcance.
- **Sin fechas, trae todo el histórico** — a diferencia del resto de `/reportes`, donde el default es el mes calendario actual. Si en la UI ofrecés los mismos selectores de fecha que ya tenés en otros reportes, dejá claro (placeholder o texto de ayuda) que vacío significa "todo el histórico", no "el mes actual".
- **Respuesta 200:**
  ```
  Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
  Content-Disposition: attachment; filename="gestion-comercial-2026-09-08.xlsx"
  ```
  El cuerpo es el archivo. **No hay envelope `{data, meta, error}`** en la respuesta exitosa — es la única excepción de todo el contrato. Los errores (`401`, `403`) sí siguen el envelope estándar como cualquier otro endpoint.
- **El archivo trae UN solo dataset**, siempre: prospectos + pipeline + histórico de actividades, en una sola hoja de 33 columnas, una fila por actividad. No hay un modo "recortado a pipeline" ni "recortado a prospección" — eso no existe en el backend y no está planeado.

### Sobre "dónde va el botón"

Dado que asumiste que bastaría con un botón en Prospección y otro en Pipeline: eso funciona, pero con una aclaración para no generar una expectativa equivocada en el usuario. **Los dos botones dispararían exactamente la misma descarga** — el mismo archivo completo, con las mismas 33 columnas y las mismas filas, sin importar desde qué pantalla se pidió. No hay forma de que "Exportar" desde Pipeline traiga solo pipeline.

Dos caminos igual de válidos, a tu criterio de UX:
- **Un botón en cada pantalla** (lo que ya asumiste) — es lo más simple de implementar y es razonable si el usuario espera encontrar "Exportar" en el lugar donde está mirando. Solo cuidá que el texto del botón no prometa un recorte que no existe (ej. evitá "Exportar pipeline" si el archivo trae de todo; "Exportar gestión comercial" es más honesto).
- **Un solo botón**, en el lugar que tenga más sentido para gerencia (por ejemplo el layout global, o donde ya estén los otros reportes), si preferís no duplicar el mismo disparador en dos pantallas.

No es una decisión que bloquee nada — cualquiera de las dos es correcta contra el contrato. Elegí la que mejor calce con el resto de la navegación.

---

## 3. Cómo consumirlo

- La descarga es un `GET` autenticado como cualquier otro endpoint (cookie httpOnly, igual que el resto de `/reportes`) — no hay token nuevo que pasar ni cabecera especial.
- Como el 200 es binario y no JSON, el cliente HTTP que ya usás para el resto del CRM necesita manejarlo como blob/array de bytes en este endpoint puntual, en vez de parsear `{data, meta, error}`. El nombre de archivo sugerido va en `Content-Disposition`; usalo si tu librería lo expone, o generá uno propio si es más simple con lo que ya tenés armado.
- Un `401`/`403` en este endpoint sigue viniendo como JSON con el envelope de siempre — el manejo de esos dos casos es igual al de cualquier otro endpoint de `/reportes`, no hace falta un camino especial.

---

## 4. Errores a manejar

| Código HTTP | Cuándo | Qué hacer en la UI |
|---|---|---|
| 401 | Sesión vencida o inexistente | Igual que en cualquier otro endpoint: redirigir a login. |
| 403 | Rol sin acceso (`vendedor`, `analista`, `otro`) | No debería ocurrir si el botón solo se muestra a `admin`/`gerencia`/`jdv`. Es la red de seguridad, no el control primario de acceso. |

No hay códigos de error específicos de este endpoint (no valida nada del lado del negocio; un rango de fechas sin resultados simplemente devuelve un archivo con solo la cabecera, no un error).

---

## 5. Qué NO hace falta construir

- No hay que armar el Excel en el cliente ni pedirle nada al backend fila por fila — el archivo ya sale completo y formateado.
- No hay filtros adicionales que ofrecer más allá de `fecha_desde`/`fecha_hasta` — el backend no tiene ningún otro parámetro de este endpoint.
- No hay una vista de previsualización que construir: es descarga directa, no hay pantalla intermedia que muestre el contenido del Excel dentro del CRM.
