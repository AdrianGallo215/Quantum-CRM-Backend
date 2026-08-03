# Eventos a nivel de Empresa — cierre de gaps

> Origen: `solicitud-backend-eventos-empresa.md` (equipo frontend). Estado al momento de este spec: los endpoints `GET/POST /empresas/:id/eventos` ya existían implementados (controller, service, repo, migración V21) desde una sesión previa. Este documento cubre únicamente los gaps detectados frente a la solicitud.

## Contexto

`EmpresaEventoController` (`GET/POST /api/v1/empresas/{idEmpresa}/eventos`) y `EventoService.listarPorEmpresa`/`crearEnEmpresa` ya están implementados. La migración V21 ya agregó `id_empresa` a `eventos` con su CHECK de vínculo. Faltan 2 comportamientos pedidos explícitamente por el frontend, cobertura de tests (el módulo tiene cero tests hoy) y la actualización de `contrato_api.md`.

## Cambios

### 1. Exponer `es_hito_prospeccion` en `EventoDto`

- Agregar `esHitoProspeccion: Boolean` a `EventoDto` (`EventoDtos.kt`).
- Poblarlo en `Evento.toDto()` desde `entrada?.esHitoProspeccion ?: false`, igual que `esRecomendado`/`etapaAsociada`. Aplica a todos los eventos (oportunidad y empresa), no solo los de empresa.

### 2. Rechazar eventos de pipeline sobre una empresa suelta

- En `EventoServiceImpl.crear()`, rama "del catálogo": si `idEmpresa != null` y `catalogo.etapaAsociada != null` → `ValidacionException` (400 `VALIDACION`, field `id_catalogo_evento`), mensaje: "Este evento pertenece a una etapa del pipeline y debe registrarse en una oportunidad, no en una empresa".
- Verificado contra el seed V18: los 3 hitos de prospección tienen `etapa_asociada = NULL`, no se bloquea ningún caso legítimo.

### 3. Confirmación de reutilización (sin cambio de código)

- `PATCH /eventos/:id/ocurrido`, `PATCH /eventos/:id/descartado`, `PUT /eventos/:id` ya operan correctamente sobre eventos de empresa vía `visible()`. `sugerencia` ya sale `null` cuando `disparaCambioEstado=false` (caso de todos los hitos). Se agrega un test que lo confirme explícitamente.

### 4. Flag opcional `aplica_a_empresa` — no implementado

- Se confirma al frontend que la heurística `etapa_asociada === null || es_hito_prospeccion === true` es correcta con los datos actuales (todo evento con `etapa_asociada = null` es un hito de prospección). No se agrega el flag explícito en este ciclo.

## Testing (TDD, mocks directos sin base de datos — patrón `SinBaseDeDatosMocks`)

- Crear evento de catálogo con `etapa_asociada` no nula sobre una empresa → 400 `VALIDACION`.
- Crear hito de prospección sobre una empresa → 201, `id_oportunidad = null`.
- `EventoDto` de un hito trae `es_hito_prospeccion = true`; de un evento de pipeline trae `false`.
- `GET /empresas/:id/eventos` no devuelve eventos con `id_oportunidad` no nulo.
- IDOR: empresa ajena/inexistente → 404 (vía `vinculoVisible`).

## Contrato API

Agregar a `contrato_api.md`: sección `GET/POST /empresas/:id/eventos` (calcada de §11, sin oportunidad) y el campo `es_hito_prospeccion` en el shape de evento.

## Fuera de alcance

- No se toca `oportunidad_estados_log` ni `actualizarEstadoCartera`.
- No se agrega el flag `aplica_a_empresa` en catálogo (sección 4, opcional).
- No se amplía cobertura de tests a funcionalidad preexistente no relacionada (listar/crear/actualizar oportunidades, etc.) — solo lo tocado por este cambio.
